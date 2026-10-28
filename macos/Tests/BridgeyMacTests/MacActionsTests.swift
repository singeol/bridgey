import XCTest
@testable import BridgeyMac

@MainActor
private final class FakeSystem: MacSystemActing {
    var accessibilityTrusted = true
    var calls: [String] = []
    var volumeState: MacVolumeState? = MacVolumeState(level: 40, muted: false)
    var installed: Set<String> = ["com.example.Editor"]
    var shortcutUpdates: [(ShortcutRunOutcome) -> Void] = []

    func requestAccessibility() { calls.append("request_accessibility") }
    func lockScreen() -> Bool { calls.append("lock"); return true }
    func displaySleep() -> Bool { calls.append("display_sleep"); return true }
    func sleepSystem() -> Bool { calls.append("sleep"); return true }
    func startScreenSaver() -> Bool { calls.append("screensaver"); return true }
    func volume() -> MacVolumeState? { volumeState }
    func stepVolume(up: Bool) -> MacVolumeState? { calls.append(up ? "up" : "down"); return volumeState }
    func toggleMute() -> MacVolumeState? { calls.append("mute"); return volumeState }
    func setVolume(_ level: Int) -> MacVolumeState? {
        calls.append("set \(level)")
        volumeState?.level = level
        return volumeState
    }
    func openApp(bundleID: String, completion: @escaping (Bool?) -> Void) {
        calls.append("open \(bundleID)")
        completion(installed.contains(bundleID) ? true : nil)
    }
    func runShortcut(named name: String, update: @escaping (ShortcutRunOutcome) -> Void) {
        calls.append("shortcut \(name)")
        shortcutUpdates.append(update)
    }
    func listShortcuts(completion: @escaping ([String]?) -> Void) { completion(["Focus", "Lights off"]) }
}

@MainActor
private final class Harness {
    let system = FakeSystem()
    var now = Date(timeIntervalSince1970: 1_000)
    var records: [String] = []
    private(set) var actions: MacActions!

    init(defaults: UserDefaults? = nil) {
        actions = MacActions(system: system, defaults: defaults, clock: { [unowned self] in self.now }, sleepDelay: 60)
        actions.available = { true }
        actions.record = { [unowned self] event, outcome in self.records.append("\(event)/\(outcome)") }
    }

    func perform(_ action: String, value: String = "") -> MacActionResult? {
        var result: MacActionResult?
        actions.perform(action: action, value: value) { result = $0 }
        return result
    }
}

final class MacActionsTests: XCTestCase {
    func testWireIdentifiersAreFixedOrOpaqueHandles() {
        let handle = "3f2c9a10-1b2c-4d5e-8f90-a1b2c3d4e5f6"
        XCTAssertEqual(MacActionID(wire: "lock"), .system(.lock))
        XCTAssertEqual(MacActionID(wire: "display_sleep"), .system(.displaySleep))
        XCTAssertEqual(MacActionID(wire: "app:\(handle)"), .app(handle: handle))
        XCTAssertEqual(MacActionID(wire: "shortcut:\(handle)"), .shortcut(handle: handle))
        for wire in ["", "Lock", "lock ", "app:com.apple.Safari", "app:/Applications/Safari.app",
                     "app:\(handle.uppercased())", "shortcut:Focus", "shortcut:../\(handle)",
                     "do shell script \"open /tmp\"", "app:\(handle);rm", String(repeating: "a", count: 100)] {
            XCTAssertNil(MacActionID(wire: wire), wire)
        }
    }

    @MainActor func testNothingIsAllowedByDefault() {
        let h = Harness()
        XCTAssertTrue(h.actions.config.entries.isEmpty)
        XCTAssertEqual((h.actions.statePayload()["actions"] as? [Any])?.count, 0)
        XCTAssertNil(h.actions.statePayload()["volume"])
        for action in ["lock", "sleep", "screensaver", "volume_up", "volume_set"] {
            XCTAssertEqual(h.perform(action, value: action == "volume_set" ? "10" : "")?.refusal, .notAllowed, action)
        }
        XCTAssertTrue(h.system.calls.isEmpty)
    }

    @MainActor func testUnavailableFeatureRefusesEvenListedActions() {
        let h = Harness()
        h.actions.setGroup(.screensaver, enabled: true)
        h.actions.available = { false }
        XCTAssertEqual(h.perform("screensaver")?.refusal, .notAllowed)
        XCTAssertTrue(h.system.calls.isEmpty)
    }

    @MainActor func testVolumeSetAcceptsOnlyPlainIntegersAndOtherActionsTakeNoValue() {
        let h = Harness()
        h.actions.setGroup(.volume, enabled: true)
        h.actions.setGroup(.screensaver, enabled: true)
        let result = h.perform("volume_set", value: "55")
        XCTAssertNil(result?.refusal)
        XCTAssertEqual(result?.volume?.level, 55)
        XCTAssertEqual(result?.payload["accepted"] as? Bool, true)
        for value in ["", "101", "-1", "+5", " 5", "5.0", "0x10", "1e2", "\u{0665}"] {
            XCTAssertEqual(h.perform("volume_set", value: value)?.refusal, .invalid, value)
        }
        XCTAssertEqual(h.perform("screensaver", value: "now")?.refusal, .invalid)
        XCTAssertEqual(h.system.calls, ["set 55"])
    }

    @MainActor func testRateLimitRefusesInsteadOfQueueing() {
        let h = Harness()
        h.actions.setGroup(.screensaver, enabled: true)
        h.actions.setGroup(.volume, enabled: true)
        for _ in 0..<3 { XCTAssertNil(h.perform("screensaver")?.refusal) }
        XCTAssertEqual(h.perform("screensaver")?.refusal, .rateLimited)
        for _ in 0..<10 { XCTAssertNil(h.perform("volume_up")?.refusal) }
        XCTAssertEqual(h.perform("volume_down")?.refusal, .rateLimited)
        h.now = h.now.addingTimeInterval(2)
        XCTAssertNil(h.perform("screensaver")?.refusal)
        XCTAssertEqual(h.perform("screensaver")?.refusal, .rateLimited)
    }

    @MainActor func testLockNeedsAccessibilityAndAsksOnlyWhenTurnedOn() {
        let h = Harness()
        h.system.accessibilityTrusted = false
        h.actions.setGroup(.lock, enabled: true)
        XCTAssertEqual(h.system.calls, ["request_accessibility"])
        let refused = h.perform("lock")
        XCTAssertEqual(refused?.refusal, .permissionRequired)
        XCTAssertEqual(refused?.payload["reason"] as? String, "permission_required")
        h.system.accessibilityTrusted = true
        XCTAssertNil(h.perform("lock")?.refusal)
        XCTAssertEqual(h.system.calls, ["request_accessibility", "lock"])
    }

    @MainActor func testSleepAnswersBeforeSleeping() {
        let h = Harness()
        h.actions.setGroup(.sleep, enabled: true)
        XCTAssertNil(h.perform("sleep")?.refusal)
        XCTAssertFalse(h.system.calls.contains("sleep"))
    }

    @MainActor func testAppsOpenOnlyByTheirLocalHandleAndStateHidesBundleIDs() throws {
        var config = MacActionsConfig()
        XCTAssertTrue(config.addApp(bundleID: "com.example.Editor", name: "Editor\n\u{7}", handle: "11111111-2222-4333-8444-555555555555"))
        XCTAssertFalse(config.addApp(bundleID: "com.example.Editor", name: "Again"))
        XCTAssertTrue(config.addApp(bundleID: "com.example.Gone", name: "Gone", handle: "21111111-2222-4333-8444-555555555555"))
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "MacActionsTests.\(UUID().uuidString)"))
        defaults.set(try JSONEncoder().encode(config), forKey: "settings.macActions.config")
        let h = Harness(defaults: defaults)
        let titles = (h.actions.statePayload()["actions"] as? [[String: Any]])?.compactMap { $0["title"] as? String }
        XCTAssertEqual(titles, ["Editor", "Gone"])
        let state = String(decoding: try JSONSerialization.data(withJSONObject: h.actions.statePayload()), as: UTF8.self)
        XCTAssertFalse(state.contains("com.example"))
        XCTAssertNil(h.perform("app:11111111-2222-4333-8444-555555555555")?.refusal)
        XCTAssertEqual(h.perform("app:21111111-2222-4333-8444-555555555555")?.refusal, .unavailable)
        XCTAssertEqual(h.perform("app:31111111-2222-4333-8444-555555555555")?.refusal, .notAllowed)
        XCTAssertEqual(h.system.calls, ["open com.example.Editor", "open com.example.Gone"])
    }

    @MainActor func testShortcutsRunByLocalNameOneAtATime() {
        let h = Harness()
        h.actions.addShortcut(named: "Lights off")
        let handle = h.actions.config.shortcuts[0].id
        var first: MacActionResult?
        h.actions.perform(action: "shortcut:\(handle)", value: "") { first = $0 }
        XCTAssertNil(first)
        XCTAssertEqual(h.system.calls, ["shortcut Lights off"])
        XCTAssertEqual(h.perform("shortcut:\(handle)")?.refusal, .busy)
        h.system.shortcutUpdates[0](.running)
        XCTAssertNotNil(first)
        XCTAssertNil(first?.refusal)
        h.now = h.now.addingTimeInterval(10)
        XCTAssertEqual(h.perform("shortcut:\(handle)")?.refusal, .busy)
        h.system.shortcutUpdates[0](.failed)
        XCTAssertNil(first?.refusal, "A later outcome does not answer twice")
        var second: MacActionResult?
        h.actions.perform(action: "shortcut:\(handle)", value: "") { second = $0 }
        h.system.shortcutUpdates[1](.failed)
        XCTAssertEqual(second?.refusal, .failed)
    }

    @MainActor func testStateListsOnlyEnabledEntriesWithVolume() {
        let h = Harness()
        h.actions.setGroup(.volume, enabled: true)
        h.actions.setGroup(.lock, enabled: true)
        let payload = h.actions.statePayload()
        let ids = (payload["actions"] as? [[String: Any]])?.compactMap { $0["id"] as? String }
        XCTAssertEqual(ids, ["lock", "volume_up", "volume_down", "volume_mute", "volume_set"])
        XCTAssertEqual((payload["volume"] as? [String: Any])?["level"] as? Int, 40)
        XCTAssertEqual(payload["version"] as? Int, 1)
    }

    @MainActor func testDiagnosticsAndRecentLogHoldNoIdentifiers() {
        let h = Harness()
        h.actions.addShortcut(named: "Secret project")
        let handle = h.actions.config.shortcuts[0].id
        _ = h.perform("shortcut:\(handle)")
        h.system.shortcutUpdates[0](.done)
        _ = h.perform("app:\(handle)")
        _ = h.perform("bogus")
        XCTAssertEqual(h.records, ["shortcut/done", "app/not_allowed", "unknown/not_allowed"])
        XCTAssertEqual(h.actions.recent.count, 3)
        XCTAssertEqual(h.actions.recent.last?.title, "Secret project")
        h.actions.clearRecent()
        XCTAssertTrue(h.actions.recent.isEmpty)
    }

    func testTitlesAndVolumeStepsAreBounded() {
        XCTAssertEqual(macActionTitle("  Two\n\tlines\u{0} "), "Two lines")
        XCTAssertEqual(macActionTitle(String(repeating: "x", count: 100)).count, 64)
        XCTAssertEqual(steppedVolume(0.5, up: true), 9.0 / 16)
        XCTAssertEqual(steppedVolume(0, up: false), 0)
        XCTAssertEqual(steppedVolume(1, up: true), 1)
    }

    func testNewFeatureFailsClosedForOldPeersAndHasItsOwnSequence() {
        XCTAssertFalse(featureEnabledByLegacyPeer(.macActions))
        var sequence = QuickRequestSequence()
        XCTAssertTrue(sequence.accept(feature: "mac_actions", sequence: 1))
        XCTAssertFalse(sequence.accept(feature: "mac_actions", sequence: 1))
        XCTAssertTrue(sequence.accept(feature: "media", sequence: 1))
    }
}
