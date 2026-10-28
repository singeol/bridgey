import AppKit
import ApplicationServices
import Foundation

/// The visible side of a session: the pill with Stop and the stop shortcut.
@MainActor
protocol RemoteControlIndicating: AnyObject {
    func show(deviceName: String, mode: RemoteInputMode, onStop: @escaping () -> Void)
    func hide()
}

/**
 * The Mac side of remote input. One session at a time, from the paired phone that the user
 * allowed. Messages arrive decrypted from the coordinator; this checks the session, the sequence,
 * the grant and the rate limits, injects, keeps the indicator up and writes the audit log.
 */
@MainActor
final class RemoteInputHost: ObservableObject {
    struct ActiveSession: Equatable {
        let inputSessionID: String
        let deviceID: String
        let deviceName: String
        let mode: RemoteInputMode
        let startedAt: Date
    }

    @Published private(set) var active: ActiveSession?
    /// macOS lets Bridgey inject input (Accessibility). Refreshed when Settings asks.
    @Published private(set) var accessibilityTrusted = false
    let audit: RemoteControlAuditLog

    /// Sends a host-to-controller payload to a device; false when it is not connected.
    var send: (String, String, [String: Any]) -> Bool = { _, _, _ in false }
    /// Remote control is on here, granted to this device, and on for the phone (both advertise the key).
    var isAllowed: (String) -> Bool = { _ in false }

    private let injector: RemoteInputInjecting
    private let indicator: RemoteControlIndicating
    private let accessibility: () -> Bool
    private let requestAccessibility: () -> Void
    private let clock: () -> Date
    private let schedulesTimer: Bool

    private final class Session {
        let inputSessionID: String
        let deviceID: String
        let deviceName: String
        let mode: RemoteInputMode
        let startedAt: Date
        var lastActivity: Date
        var sequence = RemoteInputSequence()
        var hostSequence = 0
        var limiter = RemoteInputRateLimiter()
        var counts: [RemoteInputEventType: Int] = [:]
        var dropped = 0
        var rejected = 0
        var held = Set<PointerButton>()

        init(inputSessionID: String, deviceID: String, deviceName: String, mode: RemoteInputMode, now: Date) {
            self.inputSessionID = inputSessionID
            self.deviceID = deviceID
            self.deviceName = deviceName
            self.mode = mode
            startedAt = now
            lastActivity = now
        }

        func nextHostSequence() -> Int {
            hostSequence += 1
            return hostSequence
        }
    }

    private var session: Session?
    private var endedSessionIDs: [String] = []
    private var lastStartAt: [String: Date] = [:]
    private var ticker: DispatchWorkItem?

    init(
        injector: RemoteInputInjecting,
        indicator: RemoteControlIndicating,
        audit: RemoteControlAuditLog,
        accessibility: @escaping () -> Bool,
        requestAccessibility: @escaping () -> Void = {},
        clock: @escaping () -> Date = Date.init,
        schedulesTimer: Bool = true
    ) {
        self.injector = injector
        self.indicator = indicator
        self.audit = audit
        self.accessibility = accessibility
        self.requestAccessibility = requestAccessibility
        self.clock = clock
        self.schedulesTimer = schedulesTimer
    }

    /// The app's host: CGEvents, the pill, the audit file and the real Accessibility check.
    static func live() -> RemoteInputHost {
        RemoteInputHost(
            injector: CGEventInjector(),
            indicator: RemoteControlIndicator(),
            audit: RemoteControlAuditLog(),
            accessibility: AccessibilityAccess.isTrusted,
            requestAccessibility: AccessibilityAccess.request
        )
    }

    // MARK: Messages

    /// A decrypted `input.*` payload from an authenticated device session.
    func receive(kind: String, payload: Data, deviceID: String, deviceName: String) {
        guard let message = RemoteInputMessage.parse(kind: kind, data: payload) else {
            if session?.deviceID == deviceID { session?.rejected += 1 }
            return
        }
        if case let .start(mode) = message.command {
            start(message, mode: mode, deviceID: deviceID, deviceName: deviceName)
            return
        }
        guard let current = session, current.deviceID == deviceID, current.inputSessionID == message.inputSessionID else { return }
        guard current.sequence.accept(message.sequence) else {
            current.rejected += 1
            return
        }
        if case let .stop(reason) = message.command {
            // An authenticated stop always ends the session; stopping is never rate limited.
            end(reason, notify: false)
            return
        }
        guard isAllowed(deviceID) else {
            end(.disabled, notify: true)
            return
        }
        guard message.command.eventType != .present else {
            // Presentation actions are not handled by this host yet: dropped and counted.
            current.rejected += 1
            return
        }
        let now = clock()
        guard current.limiter.allow(message.command.rateClass, at: now.timeIntervalSinceReferenceDate) else {
            current.dropped += 1
            return
        }
        current.lastActivity = now
        perform(message.command, in: current)
        if let type = message.command.eventType { current.counts[type, default: 0] += 1 }
    }

    private func start(_ message: RemoteInputMessage, mode: RemoteInputMode?, deviceID: String, deviceName: String) {
        let id = message.inputSessionID
        // A start is used once: a replay, or a repeat of the running session, changes nothing.
        guard !endedSessionIDs.contains(id), session?.inputSessionID != id else { return }
        let now = clock()
        if let last = lastStartAt[deviceID], now.timeIntervalSince(last) < RemoteInputLimits.minimumStartInterval { return }
        lastStartAt[deviceID] = now
        guard let mode else { return refuse(id, deviceID: deviceID, deviceName: deviceName, mode: nil, reason: .invalid) }
        if let current = session {
            guard current.deviceID == deviceID else {
                return refuse(id, deviceID: deviceID, deviceName: deviceName, mode: mode, reason: .busy)
            }
            end(.replaced, notify: false)
        }
        guard isAllowed(deviceID) else { return refuse(id, deviceID: deviceID, deviceName: deviceName, mode: mode, reason: .disabled) }
        guard accessibility() else {
            accessibilityTrusted = false
            return refuse(id, deviceID: deviceID, deviceName: deviceName, mode: mode, reason: .permissionRequired)
        }
        accessibilityTrusted = true
        let created = Session(inputSessionID: id, deviceID: deviceID, deviceName: deviceName, mode: mode, now: now)
        _ = created.sequence.accept(message.sequence)
        session = created
        active = ActiveSession(inputSessionID: id, deviceID: deviceID, deviceName: deviceName, mode: mode, startedAt: now)
        indicator.show(deviceName: deviceName, mode: mode) { [weak self] in self?.stopLocally() }
        _ = send(deviceID, "input.session.started", RemoteInputReply.started(inputSessionID: id, sequence: created.nextHostSequence()))
        scheduleTick()
        NSLog("PLUGIN remote input session started")
    }

    private func refuse(_ id: String, deviceID: String, deviceName: String, mode: RemoteInputMode?, reason: RemoteInputStopReason) {
        _ = send(deviceID, "input.session.stop", RemoteInputReply.stop(inputSessionID: id, sequence: 1, reason: reason))
        remember(id)
        let now = clock()
        audit.record(RemoteControlAuditEntry(
            id: UUID().uuidString.lowercased(), deviceID: deviceID, deviceName: deviceName, mode: mode?.rawValue,
            accepted: false, startedAt: now, endedAt: now, endReason: reason.rawValue, counts: [:], dropped: 0, rejected: 0
        ))
        NSLog("PLUGIN remote input start refused reason=%@", reason.rawValue)
    }

    private func perform(_ command: RemoteInputCommand, in current: Session) {
        switch command {
        case .start, .stop, .heartbeat:
            break
        case let .pointer(dx, dy), let .dragMove(dx, dy):
            injector.move(dx: dx, dy: dy, held: current.held)
        case let .button(button, down):
            // A press while held, or a release without a press, is ignored: no stuck or phantom clicks.
            if down {
                guard current.held.insert(button).inserted else { return }
            } else {
                guard current.held.remove(button) != nil else { return }
            }
            injector.button(button, down: down)
        case let .scroll(dx, dy, unit):
            injector.scroll(dx: dx, dy: dy, unit: unit)
        case .dragStart:
            guard current.held.insert(.left).inserted else { return }
            injector.button(.left, down: true)
        case .dragEnd:
            guard current.held.remove(.left) != nil else { return }
            injector.button(.left, down: false)
        case let .text(text):
            injector.type(text)
        case let .key(key, shift):
            injector.press([key.stroke(shift: shift)])
        case .present:
            break
        }
    }

    // MARK: Ending

    /// Stop in the pill, the Control-Option-Escape shortcut or Settings.
    func stopLocally() {
        end(.localStop, notify: true)
    }

    /// The transport session closed or was replaced: whatever was running ends.
    func connectionClosed() {
        lastStartAt.removeAll()
        end(.disconnected, notify: false)
    }

    /// Settings changed: a session whose grant is gone ends at once.
    func policyChanged() {
        guard let current = session, !isAllowed(current.deviceID) else { return }
        end(.disabled, notify: true)
    }

    /// Once a second during a session: the idle timeout, the grant and Accessibility.
    func tick() {
        guard let current = session else { return }
        if clock().timeIntervalSince(current.lastActivity) >= RemoteInputLimits.idleSeconds {
            end(.timeout, notify: true)
        } else if !isAllowed(current.deviceID) {
            end(.disabled, notify: true)
        } else if !accessibility() {
            accessibilityTrusted = false
            end(.permissionRequired, notify: true)
        }
    }

    private func end(_ reason: RemoteInputStopReason, notify: Bool) {
        guard let current = session else { return }
        session = nil
        active = nil
        ticker?.cancel()
        ticker = nil
        // Nothing stays pressed.
        for button in PointerButton.allCases where current.held.contains(button) {
            injector.button(button, down: false)
        }
        indicator.hide()
        if notify {
            _ = send(current.deviceID, "input.session.stop",
                     RemoteInputReply.stop(inputSessionID: current.inputSessionID, sequence: current.nextHostSequence(), reason: reason))
        }
        remember(current.inputSessionID)
        audit.record(RemoteControlAuditEntry(
            id: current.inputSessionID, deviceID: current.deviceID, deviceName: current.deviceName, mode: current.mode.rawValue,
            accepted: true, startedAt: current.startedAt, endedAt: clock(), endReason: reason.rawValue,
            counts: Dictionary(uniqueKeysWithValues: current.counts.map { ($0.key.rawValue, $0.value) }),
            dropped: current.dropped, rejected: current.rejected
        ))
        NSLog("PLUGIN remote input session ended reason=%@", reason.rawValue)
    }

    private func remember(_ id: String) {
        endedSessionIDs.append(id)
        if endedSessionIDs.count > RemoteInputLimits.rememberedEndedSessions {
            endedSessionIDs.removeFirst(endedSessionIDs.count - RemoteInputLimits.rememberedEndedSessions)
        }
    }

    private func scheduleTick() {
        guard schedulesTimer else { return }
        ticker?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.session != nil else { return }
            self.tick()
            if self.session != nil { self.scheduleTick() }
        }
        ticker = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: work)
    }

    // MARK: Accessibility

    func refreshAccessibility() {
        let trusted = accessibility()
        if accessibilityTrusted != trusted { accessibilityTrusted = trusted }
    }

    /// The user just turned remote control on: ask macOS for Accessibility once, if it is missing.
    /// Never called at launch or for an incoming session.
    func remoteControlEnabled() {
        refreshAccessibility()
        if !accessibilityTrusted { requestAccessibility() }
    }
}

/// Remote control is usable: on for this Mac and granted to at least one device.
func remoteControlEffectivelyEnabled(globalEnabled: Bool, deviceGrants: [String: Bool]) -> Bool {
    globalEnabled && deviceGrants.values.contains(true)
}

/// macOS Accessibility, which CGEvent posting needs. Asked for only when the user turns remote
/// control on.
enum AccessibilityAccess {
    static func isTrusted() -> Bool { AXIsProcessTrusted() }

    /// Shows macOS's own prompt, which leads to System Settings > Privacy & Security > Accessibility.
    static func request() {
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
    }

    static let settingsURL = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!
}
