import CoreFoundation
import Foundation

/*
 * Remote input, feature `remote_input` (docs/protocol.md, "Remote input"). The Mac is the
 * host: a paired phone moves the pointer, clicks, scrolls, drags, types bounded text, presses a
 * small set of named keys and drives presentations. Everything here is pure, so it is unit tested
 * without injecting a single event.
 */

enum RemoteInputLimits {
    static let maximumPlaintextBytes = 2_048
    static let maximumPointerDelta = 512
    static let maximumPixelScroll = 1_000
    static let maximumLineScroll = 10
    static let maximumTextScalars = 256
    static let maximumTextBytes = 1_024
    /// Laser coordinates are integers from 0 to this, a fraction of the display.
    static let laserScale = 10_000
    static let motionPerSecond = 120.0
    static let discretePerSecond = 20.0
    static let controlPerSecond = 10.0
    static let heartbeatSeconds = 2
    /// The session ends after this long without a valid input message or heartbeat.
    static let idleSeconds: TimeInterval = 6
    /// At most one start a second from one device.
    static let minimumStartInterval: TimeInterval = 1
    static let rememberedEndedSessions = 64
    static let maximumSequence = 9_007_199_254_740_991
}

enum RemoteInputMode: String, Codable, Equatable {
    case touchpad, presentation
}

/// Why a session ended. `disconnected` and `replaced` are recorded locally and never sent.
enum RemoteInputStopReason: String, Codable, CaseIterable, Equatable {
    case user
    case leftScreen = "left_screen"
    case screenOff = "screen_off"
    case localStop = "local_stop"
    case timeout
    case disabled
    case permissionRequired = "permission_required"
    case busy
    case invalid
    case unsupported
    case disconnected
    case replaced

    /// Reasons a controller may give when it stops; anything else still stops, as `user`.
    static let fromController: Set<RemoteInputStopReason> = [.user, .leftScreen, .screenOff]
}

enum PointerButton: String, Equatable, CaseIterable {
    case left, right, middle
}

enum ScrollUnit: String, Equatable {
    case pixel, line
}

/// The named keys a controller may press. Shift is the only modifier on the wire, and it may not
/// combine with `previousApp`; the host maps `previousApp` to Command-Tab itself.
enum RemoteKey: String, CaseIterable, Equatable {
    case left, right, up, down
    case returnKey = "return"
    case escape, tab, space, backspace
    case pageUp = "page_up"
    case pageDown = "page_down"
    case home, end, f5
    case previousApp = "previous_app"
}

enum PresentAction: String, CaseIterable, Equatable {
    case next, previous, start, end, black
    case laserOn = "laser_on"
    case laserMove = "laser_move"
    case laserOff = "laser_off"

    var carriesPosition: Bool { self == .laserOn || self == .laserMove }
}

enum RemoteInputRateClass: Equatable {
    case motion, discrete, control
}

/// Message types counted in the audit log. Counts only: never text, keys or positions.
enum RemoteInputEventType: String, Codable, CaseIterable {
    case pointer, button, scroll, drag, key, text, present
}

enum RemoteInputCommand: Equatable {
    /// `mode` is nil when the start named an unknown mode; the host refuses it as `invalid`.
    case start(mode: RemoteInputMode?)
    case heartbeat
    case stop(reason: RemoteInputStopReason)
    case pointer(dx: Int, dy: Int)
    case button(PointerButton, down: Bool)
    case scroll(dx: Int, dy: Int, unit: ScrollUnit)
    case dragStart
    case dragMove(dx: Int, dy: Int)
    case dragEnd
    case text(String)
    case key(RemoteKey, shift: Bool)
    case present(PresentAction, x: Int?, y: Int?)

    var rateClass: RemoteInputRateClass {
        switch self {
        case .start, .heartbeat, .stop: .control
        case .pointer, .scroll, .dragMove: .motion
        case let .present(action, _, _): action == .laserMove ? .motion : .discrete
        case .button, .dragStart, .dragEnd, .text, .key: .discrete
        }
    }

    var eventType: RemoteInputEventType? {
        switch self {
        case .start, .heartbeat, .stop: nil
        case .pointer: .pointer
        case .button: .button
        case .scroll: .scroll
        case .dragStart, .dragMove, .dragEnd: .drag
        case .text: .text
        case .key: .key
        case .present: .present
        }
    }
}

/// One decrypted, validated message from a controller.
struct RemoteInputMessage: Equatable {
    let inputSessionID: String
    let sequence: Int
    let command: RemoteInputCommand

    static let kinds: Set<String> = [
        "input.session.start", "input.session.heartbeat", "input.session.stop",
        "input.pointer", "input.button", "input.scroll", "input.drag", "input.text", "input.key", "input.present",
    ]

    /// Parses and bounds-checks a plaintext payload; nil for anything malformed or out of range.
    static func parse(kind: String, data: Data) -> RemoteInputMessage? {
        guard data.count <= RemoteInputLimits.maximumPlaintextBytes, kinds.contains(kind),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              jsonInteger(object["version"]) == 1,
              let sessionID = object["inputSessionId"] as? String, UUID(uuidString: sessionID) != nil,
              let sequence = jsonInteger(object["sequence"]), (1...RemoteInputLimits.maximumSequence).contains(sequence),
              let command = command(kind: kind, object) else { return nil }
        return RemoteInputMessage(inputSessionID: sessionID.lowercased(), sequence: sequence, command: command)
    }

    private static func command(kind: String, _ object: [String: Any]) -> RemoteInputCommand? {
        switch kind {
        case "input.session.start":
            return .start(mode: (object["mode"] as? String).flatMap(RemoteInputMode.init(rawValue:)))
        case "input.session.heartbeat":
            return .heartbeat
        case "input.session.stop":
            let reason = (object["reason"] as? String).flatMap(RemoteInputStopReason.init(rawValue:))
            return .stop(reason: reason.flatMap { RemoteInputStopReason.fromController.contains($0) ? $0 : nil } ?? .user)
        case "input.pointer":
            guard let move = delta(object, limit: RemoteInputLimits.maximumPointerDelta) else { return nil }
            return .pointer(dx: move.dx, dy: move.dy)
        case "input.button":
            guard let button = (object["button"] as? String).flatMap(PointerButton.init(rawValue:)),
                  let action = object["action"] as? String, action == "down" || action == "up" else { return nil }
            return .button(button, down: action == "down")
        case "input.scroll":
            guard let unit = (object["unit"] as? String).flatMap(ScrollUnit.init(rawValue:)),
                  let amount = delta(object, limit: unit == .pixel ? RemoteInputLimits.maximumPixelScroll : RemoteInputLimits.maximumLineScroll)
            else { return nil }
            return .scroll(dx: amount.dx, dy: amount.dy, unit: unit)
        case "input.drag":
            switch object["phase"] as? String {
            case "start": return .dragStart
            case "end": return .dragEnd
            case "move":
                guard let move = delta(object, limit: RemoteInputLimits.maximumPointerDelta) else { return nil }
                return .dragMove(dx: move.dx, dy: move.dy)
            default: return nil
            }
        case "input.text":
            guard let text = object["text"] as? String, isValidRemoteText(text) else { return nil }
            return .text(text)
        case "input.key":
            guard let key = (object["key"] as? String).flatMap(RemoteKey.init(rawValue:)),
                  let shift = allowedShift(object["modifiers"], key: key) else { return nil }
            return .key(key, shift: shift)
        case "input.present":
            guard let action = (object["action"] as? String).flatMap(PresentAction.init(rawValue:)) else { return nil }
            guard action.carriesPosition else { return .present(action, x: nil, y: nil) }
            let range = 0...RemoteInputLimits.laserScale
            guard let x = jsonInteger(object["x"]), let y = jsonInteger(object["y"]), range.contains(x), range.contains(y) else { return nil }
            return .present(action, x: x, y: y)
        default:
            return nil
        }
    }

    /// dx and dy within -limit...limit, not both zero.
    private static func delta(_ object: [String: Any], limit: Int) -> (dx: Int, dy: Int)? {
        guard let dx = jsonInteger(object["dx"]), let dy = jsonInteger(object["dy"]),
              (-limit...limit).contains(dx), (-limit...limit).contains(dy), dx != 0 || dy != 0 else { return nil }
        return (dx: dx, dy: dy)
    }

    /// Absent or empty: no Shift. `["shift"]`: Shift, except with `previous_app`. Anything else: invalid.
    private static func allowedShift(_ value: Any?, key: RemoteKey) -> Bool? {
        guard let value else { return false }
        guard let list = value as? [Any] else { return nil }
        if list.isEmpty { return false }
        guard list.count == 1, list.first as? String == "shift", key != .previousApp else { return nil }
        return true
    }
}

/// 1 to 256 Unicode scalars, at most 1,024 UTF-8 bytes, no control characters (Return and Tab are keys).
func isValidRemoteText(_ text: String) -> Bool {
    let scalars = text.unicodeScalars
    guard (1...RemoteInputLimits.maximumTextScalars).contains(scalars.count),
          text.utf8.count <= RemoteInputLimits.maximumTextBytes else { return false }
    return !scalars.contains { $0.properties.generalCategory == .control }
}

/// An integer from JSON. Booleans (which bridge to NSNumber) and fractions are not integers.
func jsonInteger(_ value: Any?) -> Int? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    let double = number.doubleValue
    guard double.isFinite, double.rounded() == double, abs(double) <= Double(RemoteInputLimits.maximumSequence) else { return nil }
    return number.intValue
}

/// A per-session high-water mark: each accepted sequence must be higher than the last.
struct RemoteInputSequence: Equatable {
    private(set) var last = 0

    mutating func accept(_ sequence: Int) -> Bool {
        guard (1...RemoteInputLimits.maximumSequence).contains(sequence), sequence > last else { return false }
        last = sequence
        return true
    }
}

/// A token bucket: `ratePerSecond` tokens a second, holding at most one second's worth.
struct TokenBucket: Equatable {
    let ratePerSecond: Double
    let capacity: Double
    private var tokens: Double
    private var updatedAt: TimeInterval?

    init(ratePerSecond: Double, capacity: Double? = nil) {
        self.ratePerSecond = ratePerSecond
        self.capacity = capacity ?? ratePerSecond
        tokens = capacity ?? ratePerSecond
    }

    mutating func allow(at now: TimeInterval) -> Bool {
        if let updatedAt {
            // A clock that steps back refills nothing.
            if now > updatedAt {
                tokens = min(capacity, tokens + (now - updatedAt) * ratePerSecond)
                self.updatedAt = now
            }
        } else {
            updatedAt = now
        }
        guard tokens >= 1 else { return false }
        tokens -= 1
        return true
    }
}

/// The three buckets of one session.
struct RemoteInputRateLimiter: Equatable {
    private var motion = TokenBucket(ratePerSecond: RemoteInputLimits.motionPerSecond)
    private var discrete = TokenBucket(ratePerSecond: RemoteInputLimits.discretePerSecond)
    private var control = TokenBucket(ratePerSecond: RemoteInputLimits.controlPerSecond)

    mutating func allow(_ rateClass: RemoteInputRateClass, at now: TimeInterval) -> Bool {
        switch rateClass {
        case .motion: motion.allow(at: now)
        case .discrete: discrete.allow(at: now)
        case .control: control.allow(at: now)
        }
    }
}

/// Host-to-controller payloads.
enum RemoteInputReply {
    static func started(inputSessionID: String, sequence: Int) -> [String: Any] {
        [
            "version": 1, "inputSessionId": inputSessionID, "sequence": sequence,
            "heartbeatSeconds": RemoteInputLimits.heartbeatSeconds,
            "idleSeconds": Int(RemoteInputLimits.idleSeconds),
            "limits": [
                "motion": Int(RemoteInputLimits.motionPerSecond),
                "discrete": Int(RemoteInputLimits.discretePerSecond),
                "text": RemoteInputLimits.maximumTextScalars,
            ],
        ]
    }

    static func stop(inputSessionID: String, sequence: Int, reason: RemoteInputStopReason) -> [String: Any] {
        ["version": 1, "inputSessionId": inputSessionID, "sequence": sequence, "reason": reason.rawValue]
    }
}
