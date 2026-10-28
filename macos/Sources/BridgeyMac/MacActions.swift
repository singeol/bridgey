import AppKit
import Foundation
import UniformTypeIdentifiers

// Mac actions (docs/protocol.md, "Mac actions"). The phone picks one entry from the list this Mac
// published; the Mac maps it to a fixed local operation. No shell, no paths, no names and no
// arguments from the phone: the only operand is the 0...100 level of `volume_set`.

/// A local switch in Settings. All are off until the Mac user turns them on.
enum MacActionGroup: String, CaseIterable, Codable, Identifiable {
    case lock
    case displaySleep = "display_sleep"
    case sleep
    case screensaver
    case volume

    var id: String { rawValue }
    var title: String {
        switch self {
        case .lock: L10n.text("actions.lock", fallback: "Lock screen")
        case .displaySleep: L10n.text("actions.displaySleep", fallback: "Turn off display")
        case .sleep: L10n.text("actions.sleep", fallback: "Sleep")
        case .screensaver: L10n.text("actions.screensaver", fallback: "Start screen saver")
        case .volume: L10n.text("actions.volume", fallback: "Volume")
        }
    }
}

enum MacSystemAction: String, CaseIterable {
    case lock
    case displaySleep = "display_sleep"
    case sleep
    case screensaver
    case volumeUp = "volume_up"
    case volumeDown = "volume_down"
    case volumeMute = "volume_mute"
    case volumeSet = "volume_set"

    var group: MacActionGroup {
        switch self {
        case .lock: .lock
        case .displaySleep: .displaySleep
        case .sleep: .sleep
        case .screensaver: .screensaver
        case .volumeUp, .volumeDown, .volumeMute, .volumeSet: .volume
        }
    }

    var title: String {
        switch self {
        case .lock, .displaySleep, .sleep, .screensaver: group.title
        case .volumeUp: L10n.text("actions.volumeUp", fallback: "Volume up")
        case .volumeDown: L10n.text("actions.volumeDown", fallback: "Volume down")
        case .volumeMute: L10n.text("actions.volumeMute", fallback: "Mute")
        case .volumeSet: L10n.text("actions.volumeSet", fallback: "Set volume")
        }
    }
}

/// One identifier from the wire, parsed. Anything else is not an action.
enum MacActionID: Equatable {
    case system(MacSystemAction)
    case app(handle: String)
    case shortcut(handle: String)

    init?(wire: String) {
        guard wire.utf8.count <= 64 else { return nil }
        if let action = MacSystemAction(rawValue: wire) {
            self = .system(action)
        } else if wire.hasPrefix("app:"), isMacActionHandle(String(wire.dropFirst(4))) {
            self = .app(handle: String(wire.dropFirst(4)))
        } else if wire.hasPrefix("shortcut:"), isMacActionHandle(String(wire.dropFirst(9))) {
            self = .shortcut(handle: String(wire.dropFirst(9)))
        } else {
            return nil
        }
    }

    var wire: String {
        switch self {
        case let .system(action): action.rawValue
        case let .app(handle): "app:\(handle)"
        case let .shortcut(handle): "shortcut:\(handle)"
        }
    }

    /// The diagnostics token: the group only, never a handle.
    var diagnosticsEvent: String {
        switch self {
        case let .system(action): action.group.rawValue
        case .app: "app"
        case .shortcut: "shortcut"
        }
    }
}

/// A random local handle (lowercase UUID) made when the Mac user adds an app or Shortcut.
func isMacActionHandle(_ value: String) -> Bool {
    value.count == 36 && value == value.lowercased() && UUID(uuidString: value) != nil
}

/// A title for the list: no control characters, whitespace collapsed, at most 64 characters.
func macActionTitle(_ value: String) -> String {
    let scalars = value.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) ? " " : $0 }
    let text = String(String.UnicodeScalarView(scalars))
        .components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
    return String(text.prefix(64))
}

struct MacActionApp: Codable, Equatable, Identifiable {
    let id: String
    let bundleID: String
    let name: String
}

struct MacActionShortcut: Codable, Equatable, Identifiable {
    let id: String
    let name: String
}

/// What this Mac lets the phone run. Nothing is allowed by default.
struct MacActionsConfig: Codable, Equatable {
    static let maximumApps = 16
    static let maximumShortcuts = 16

    var groups: Set<MacActionGroup> = []
    var apps: [MacActionApp] = []
    var shortcuts: [MacActionShortcut] = []

    /// The entries the phone may pick now, in display order.
    var entries: [(id: MacActionID, title: String)] {
        var result: [(id: MacActionID, title: String)] = MacSystemAction.allCases
            .filter { groups.contains($0.group) }
            .map { (id: .system($0), title: $0.title) }
        result += apps.map { (id: .app(handle: $0.id), title: $0.name) }
        result += shortcuts.map { (id: .shortcut(handle: $0.id), title: macActionTitle($0.name)) }
        return result
    }

    func allows(_ id: MacActionID) -> Bool {
        switch id {
        case let .system(action): groups.contains(action.group)
        case let .app(handle): apps.contains { $0.id == handle }
        case let .shortcut(handle): shortcuts.contains { $0.id == handle }
        }
    }

    func title(of id: MacActionID) -> String {
        switch id {
        case let .system(action): action.title
        case let .app(handle): apps.first { $0.id == handle }?.name ?? ""
        case let .shortcut(handle): shortcuts.first { $0.id == handle }.map { macActionTitle($0.name) } ?? ""
        }
    }

    mutating func addApp(bundleID: String, name: String, handle: String = UUID().uuidString.lowercased()) -> Bool {
        let title = macActionTitle(name)
        guard !title.isEmpty, !bundleID.isEmpty, bundleID.utf8.count <= 255, isMacActionHandle(handle),
              apps.count < Self.maximumApps, !apps.contains(where: { $0.bundleID == bundleID }) else { return false }
        apps.append(MacActionApp(id: handle, bundleID: bundleID, name: title))
        apps.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        return true
    }

    mutating func addShortcut(named name: String, handle: String = UUID().uuidString.lowercased()) -> Bool {
        guard !macActionTitle(name).isEmpty, name.utf8.count <= 1024, isMacActionHandle(handle),
              shortcuts.count < Self.maximumShortcuts, !shortcuts.contains(where: { $0.name == name }) else { return false }
        shortcuts.append(MacActionShortcut(id: handle, name: name))
        shortcuts.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        return true
    }
}

struct MacVolumeState: Equatable {
    var level: Int
    var muted: Bool
    var payload: [String: Any] { ["level": min(max(level, 0), 100), "muted": muted] }
}

enum MacActionRefusal: String, Equatable {
    case notAllowed = "not_allowed"
    case invalid
    case rateLimited = "rate_limited"
    case busy
    case permissionRequired = "permission_required"
    case unavailable
    case failed
}

struct MacActionResult: Equatable {
    var refusal: MacActionRefusal?
    var volume: MacVolumeState?
    static let done = MacActionResult()
    static func refused(_ reason: MacActionRefusal) -> MacActionResult { MacActionResult(refusal: reason) }

    var payload: [String: Any] {
        var value: [String: Any] = ["accepted": refusal == nil]
        if let refusal { value["reason"] = refusal.rawValue }
        if let volume { value["volume"] = volume.payload }
        return value
    }
}

/// The `volume_set` operand: one to three ASCII digits, 0...100. Every other action takes no value.
func macActionVolumeLevel(_ value: String) -> Int? {
    guard (1...3).contains(value.count), value.allSatisfy({ $0.isASCII && $0.isNumber }),
          let level = Int(value), (0...100).contains(level) else { return nil }
    return level
}

struct TokenBucket: Equatable {
    let ratePerSecond: Double
    let capacity: Double
    private var tokens: Double
    private var updatedAt: TimeInterval?

    init(ratePerSecond: Double, capacity: Double) {
        self.ratePerSecond = ratePerSecond
        self.capacity = capacity
        tokens = capacity
    }

    mutating func allow(at now: TimeInterval) -> Bool {
        if let updatedAt { tokens = min(capacity, tokens + max(0, now - updatedAt) * ratePerSecond) }
        updatedAt = now
        guard tokens >= 1 else { return false }
        tokens -= 1
        return true
    }
}

/// Volume: 10 at once, then 5 a second. Everything else: 3 at once, then one every 2 seconds.
struct MacActionRateLimiter: Equatable {
    private var volume = TokenBucket(ratePerSecond: 5, capacity: 10)
    private var other = TokenBucket(ratePerSecond: 0.5, capacity: 3)

    mutating func allow(_ id: MacActionID, at now: TimeInterval) -> Bool {
        if case let .system(action) = id, action.group == .volume { return volume.allow(at: now) }
        return other.allow(at: now)
    }
}

enum ShortcutRunOutcome: Equatable { case running, done, failed }

/// The operating system side, behind a protocol so tests never lock, sleep or change the volume.
@MainActor
protocol MacSystemActing: AnyObject {
    var accessibilityTrusted: Bool { get }
    func requestAccessibility()
    func lockScreen() -> Bool
    func displaySleep() -> Bool
    func sleepSystem() -> Bool
    func startScreenSaver() -> Bool
    func volume() -> MacVolumeState?
    func stepVolume(up: Bool) -> MacVolumeState?
    func toggleMute() -> MacVolumeState?
    func setVolume(_ level: Int) -> MacVolumeState?
    /// Completes with nil when the app is no longer installed.
    func openApp(bundleID: String, completion: @escaping (Bool?) -> Void)
    /// Reports `running` if the Shortcut is still going after five seconds, then `done` or `failed`.
    func runShortcut(named name: String, update: @escaping (ShortcutRunOutcome) -> Void)
    func listShortcuts(completion: @escaping ([String]?) -> Void)
}

struct MacActionLogEntry: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let refusal: MacActionRefusal?
    let at: Date
}

@MainActor
final class MacActions: ObservableObject {
    @Published private(set) var config: MacActionsConfig
    /// The last 20 outcomes, in memory only, newest first.
    @Published private(set) var recent: [MacActionLogEntry] = []
    /// Names from the Shortcuts app, loaded when Settings asks; nil until then.
    @Published private(set) var availableShortcuts: [String]?

    /// `mac_actions` is available with the connected phone.
    var available: () -> Bool = { false }
    /// The list changed: send a fresh `mac_actions.state`.
    var onChange: () -> Void = {}
    /// Diagnostics: the action group and the outcome, nothing else.
    var record: (String, String) -> Void = { _, _ in }

    private let system: MacSystemActing
    private let defaults: UserDefaults?
    private let clock: () -> Date
    private let sleepDelay: TimeInterval
    private var limiter = MacActionRateLimiter()
    private var shortcutRunning = false
    private static let defaultsKey = "settings.macActions.config"

    init(system: MacSystemActing, defaults: UserDefaults? = .standard,
         clock: @escaping () -> Date = Date.init, sleepDelay: TimeInterval = 1) {
        self.system = system
        self.defaults = defaults
        self.clock = clock
        self.sleepDelay = sleepDelay
        config = defaults?.data(forKey: Self.defaultsKey)
            .flatMap { try? JSONDecoder().decode(MacActionsConfig.self, from: $0) } ?? MacActionsConfig()
    }

    static func live() -> MacActions { MacActions(system: LiveMacSystemActions()) }

    var accessibilityTrusted: Bool { system.accessibilityTrusted }

    /// The `mac_actions.state` payload.
    func statePayload() -> [String: Any] {
        var payload: [String: Any] = [
            "version": 1,
            "actions": config.entries.prefix(48).map { ["id": $0.id.wire, "title": $0.title] },
        ]
        if config.groups.contains(.volume), let volume = system.volume() { payload["volume"] = volume.payload }
        return payload
    }

    // MARK: Settings

    func setGroup(_ group: MacActionGroup, enabled: Bool) {
        if enabled { config.groups.insert(group) } else { config.groups.remove(group) }
        if group == .lock, enabled, !system.accessibilityTrusted { system.requestAccessibility() }
        changed()
    }

    func chooseApps() {
        let panel = NSOpenPanel()
        panel.title = L10n.text("actions.apps.choose", fallback: "Choose apps the phone can open")
        panel.prompt = L10n.text("actions.apps.allow", fallback: "Allow")
        panel.allowedContentTypes = [.application]
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.directoryURL = URL(fileURLWithPath: "/Applications", isDirectory: true)
        guard panel.runModal() == .OK else { return }
        var added = false
        for url in panel.urls {
            guard let bundle = Bundle(url: url), let bundleID = bundle.bundleIdentifier else { continue }
            let name = FileManager.default.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
            if config.addApp(bundleID: bundleID, name: name) { added = true }
        }
        if added { changed() }
    }

    func removeApp(_ id: String) {
        config.apps.removeAll { $0.id == id }
        changed()
    }

    func loadShortcuts() {
        system.listShortcuts { [weak self] names in self?.availableShortcuts = names ?? [] }
    }

    func addShortcut(named name: String) {
        if config.addShortcut(named: name) { changed() }
    }

    func removeShortcut(_ id: String) {
        config.shortcuts.removeAll { $0.id == id }
        changed()
    }

    func clearRecent() { recent = [] }

    private func changed() {
        if let data = try? JSONEncoder().encode(config) { defaults?.set(data, forKey: Self.defaultsKey) }
        onChange()
    }

    // MARK: Requests

    /// A `quick.request` for `mac_actions`, already decrypted, replay- and sequence-checked.
    func perform(action wire: String, value: String, completion: @escaping (MacActionResult) -> Void) {
        let parsed = MacActionID(wire: wire)
        let finish: (MacActionResult) -> Void = { [weak self] result in
            self?.finished(parsed, result: result)
            completion(result)
        }
        guard available(), let id = parsed, config.allows(id) else { finish(.refused(.notAllowed)); return }
        var level: Int?
        if id == .system(.volumeSet) {
            guard let parsedLevel = macActionVolumeLevel(value) else { finish(.refused(.invalid)); return }
            level = parsedLevel
        } else if !value.isEmpty {
            finish(.refused(.invalid)); return
        }
        guard limiter.allow(id, at: clock().timeIntervalSince1970) else { finish(.refused(.rateLimited)); return }
        run(id, level: level ?? 0, completion: finish)
    }

    private func run(_ id: MacActionID, level: Int, completion: @escaping (MacActionResult) -> Void) {
        switch id {
        case .system(.lock):
            guard system.accessibilityTrusted else { completion(.refused(.permissionRequired)); return }
            completion(system.lockScreen() ? .done : .refused(.failed))
        case .system(.displaySleep):
            completion(system.displaySleep() ? .done : .refused(.failed))
        case .system(.sleep):
            // Answer first: the network goes away when the Mac sleeps.
            completion(.done)
            let system = system
            DispatchQueue.main.asyncAfter(deadline: .now() + sleepDelay) { _ = system.sleepSystem() }
        case .system(.screensaver):
            completion(system.startScreenSaver() ? .done : .refused(.failed))
        case .system(.volumeUp), .system(.volumeDown):
            completion(volumeResult(system.stepVolume(up: id == .system(.volumeUp))))
        case .system(.volumeMute):
            completion(volumeResult(system.toggleMute()))
        case .system(.volumeSet):
            completion(volumeResult(system.setVolume(level)))
        case let .app(handle):
            guard let app = config.apps.first(where: { $0.id == handle }) else { completion(.refused(.notAllowed)); return }
            system.openApp(bundleID: app.bundleID) { opened in
                switch opened {
                case true?: completion(.done)
                case false?: completion(.refused(.failed))
                case nil: completion(.refused(.unavailable))
                }
            }
        case let .shortcut(handle):
            guard let shortcut = config.shortcuts.first(where: { $0.id == handle }) else { completion(.refused(.notAllowed)); return }
            guard !shortcutRunning else { completion(.refused(.busy)); return }
            shortcutRunning = true
            var answered = false
            system.runShortcut(named: shortcut.name) { [weak self] outcome in
                if outcome != .running { self?.shortcutRunning = false }
                guard !answered else { return }
                answered = true
                completion(outcome == .failed ? .refused(.failed) : .done)
            }
        }
    }

    private func volumeResult(_ state: MacVolumeState?) -> MacActionResult {
        state.map { MacActionResult(volume: $0) } ?? .refused(.unavailable)
    }

    private func finished(_ id: MacActionID?, result: MacActionResult) {
        record(id?.diagnosticsEvent ?? "unknown", result.refusal?.rawValue ?? "done")
        let title = id.map { config.title(of: $0) } ?? ""
        recent = Array(([MacActionLogEntry(title: title.isEmpty ? L10n.text("actions.unknown", fallback: "Unknown action") : title,
                                           refusal: result.refusal, at: clock())] + recent).prefix(20))
    }
}
