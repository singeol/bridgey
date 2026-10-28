import XCTest
@testable import BridgeyMac

/// Records what would have been injected. Nothing reaches the system.
@MainActor
private final class FakeInjector: RemoteInputInjecting {
    enum Call: Equatable {
        case move(Int, Int, Set<PointerButton>)
        case button(PointerButton, Bool)
        case scroll(Int, Int, ScrollUnit)
        case type(String)
        case press([KeyStroke])
    }

    var calls: [Call] = []
    func move(dx: Int, dy: Int, held: Set<PointerButton>) { calls.append(.move(dx, dy, held)) }
    func button(_ button: PointerButton, down: Bool) { calls.append(.button(button, down)) }
    func scroll(dx: Int, dy: Int, unit: ScrollUnit) { calls.append(.scroll(dx, dy, unit)) }
    func type(_ text: String) { calls.append(.type(text)) }
    func press(_ strokes: [KeyStroke]) { calls.append(.press(strokes)) }
}

@MainActor
private final class FakeIndicator: RemoteControlIndicating {
    var shown: (name: String, mode: RemoteInputMode)?
    var onStop: (() -> Void)?

    func show(deviceName: String, mode: RemoteInputMode, onStop: @escaping () -> Void) {
        shown = (deviceName, mode)
        self.onStop = onStop
    }

    func hide() { shown = nil }
}

final class RemoteInputHostTests: XCTestCase {
    private var injector: FakeInjector!
    private var indicator: FakeIndicator!
    private var host: RemoteInputHost!
    private var now = Date(timeIntervalSince1970: 1_800_000_000)
    private var allowed: Set<String> = ["phone"]
    private var trusted = true
    private var sent: [(device: String, kind: String, payload: [String: Any])] = []
    private var accessibilityRequests = 0
    private var directory: URL!
    private let sessionID = "0b9a54a2-6a4c-4b9e-8f45-3c2a1d0e9f11"
    private var sequence = 0

    @MainActor private func makeHost() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("remote-audit-\(UUID().uuidString)", isDirectory: true)
        injector = FakeInjector()
        indicator = FakeIndicator()
        let audit = RemoteControlAuditLog(store: RemoteControlAuditStore(fileURL: directory.appendingPathComponent("audit.json")), clock: { [unowned self] in self.now })
        host = RemoteInputHost(
            injector: injector,
            indicator: indicator,
            audit: audit,
            accessibility: { [unowned self] in self.trusted },
            requestAccessibility: { [unowned self] in self.accessibilityRequests += 1 },
            clock: { [unowned self] in self.now },
            schedulesTimer: false
        )
        host.isAllowed = { [unowned self] in self.allowed.contains($0) }
        host.send = { [unowned self] device, kind, payload in
            self.sent.append((device, kind, payload))
            return true
        }
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    @MainActor private func message(_ kind: String, _ fields: [String: Any] = [:], session: String? = nil, sequence explicit: Int? = nil,
                         device: String = "phone") {
        sequence += 1
        var object = fields
        object["version"] = 1
        object["inputSessionId"] = session ?? sessionID
        object["sequence"] = explicit ?? sequence
        host.receive(kind: kind, payload: try! JSONSerialization.data(withJSONObject: object), deviceID: device, deviceName: device == "phone" ? "OnePlus 11" : "Pixel Tablet")
    }

    @MainActor private func start(mode: String = "touchpad", device: String = "phone", session: String? = nil) {
        sequence = 0
        message("input.session.start", ["mode": mode], session: session, device: device)
    }

    // MARK: Sessions

    @MainActor func testStartShowsTheIndicatorAndAnswersStarted() {
        makeHost()
        start()
        XCTAssertEqual(host.active?.deviceName, "OnePlus 11")
        XCTAssertEqual(host.active?.mode, .touchpad)
        XCTAssertEqual(indicator.shown?.name, "OnePlus 11")
        XCTAssertEqual(sent.last?.kind, "input.session.started")
        XCTAssertEqual(sent.last?.payload["inputSessionId"] as? String, sessionID)
        XCTAssertEqual(sent.last?.payload["sequence"] as? Int, 1)
    }

    @MainActor func testStartIsRefusedWhenNotAllowedWithoutAccessibilityOrWhenBusy() {
        makeHost()
        allowed = []
        start()
        XCTAssertNil(host.active)
        XCTAssertNil(indicator.shown)
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "disabled")

        allowed = ["phone", "tablet"]
        trusted = false
        now += 2
        start(session: UUID().uuidString)
        XCTAssertNil(host.active)
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "permission_required")
        XCTAssertEqual(accessibilityRequests, 0, "an incoming session never prompts for Accessibility")

        trusted = true
        now += 2
        start(session: UUID().uuidString)
        XCTAssertNotNil(host.active)
        start(device: "tablet", session: UUID().uuidString)
        XCTAssertEqual(host.active?.deviceID, "phone", "one session at a time")
        XCTAssertEqual(sent.last?.device, "tablet")
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "busy")

        let refused = host.audit.entries.filter { !$0.accepted }.map(\.endReason)
        XCTAssertEqual(refused.sorted(), ["busy", "disabled", "permission_required"])
    }

    @MainActor func testInvalidModeIsRefusedAndStartsAreLimitedToOneASecond() {
        makeHost()
        start(mode: "screen_share")
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "invalid")
        start(session: UUID().uuidString)
        XCTAssertNil(host.active, "a second start within one second is ignored")
        now += 1
        start(session: UUID().uuidString)
        XCTAssertNotNil(host.active)
    }

    @MainActor func testAStartFromTheSameDeviceReplacesItsSession() {
        makeHost()
        start()
        message("input.button", ["button": "left", "action": "down"])
        now += 1.5
        let second = UUID().uuidString.lowercased()
        start(session: second)
        XCTAssertEqual(host.active?.inputSessionID, second)
        XCTAssertTrue(injector.calls.contains(.button(.left, false)), "the old session's button is released")
        XCTAssertEqual(host.audit.entries.first?.endReason, "replaced")
    }

    @MainActor func testInputInjectsInOrderAndIsCounted() {
        makeHost()
        start()
        message("input.pointer", ["dx": 12, "dy": -3])
        message("input.button", ["button": "left", "action": "down"])
        message("input.button", ["button": "left", "action": "up"])
        message("input.scroll", ["dx": 0, "dy": 40, "unit": "pixel"])
        message("input.text", ["text": "correct horse battery staple"])
        message("input.key", ["key": "return"])
        message("input.key", ["key": "previous_app"])
        XCTAssertEqual(injector.calls, [
            .move(12, -3, []),
            .button(.left, true),
            .button(.left, false),
            .scroll(0, 40, .pixel),
            .type("correct horse battery staple"),
            .press([KeyStroke(keyCode: KeyStroke.Code.returnKey)]),
            .press([KeyStroke(keyCode: KeyStroke.Code.tab, modifiers: .command)]),
        ])
        message("input.session.stop", ["reason": "user"])
        let entry = host.audit.entries.first!
        XCTAssertEqual(entry.counts, ["pointer": 1, "button": 2, "scroll": 1, "text": 1, "key": 2])
        XCTAssertEqual(entry.endReason, "user")
        XCTAssertTrue(entry.accepted)
    }

    @MainActor func testPresentationActionsAreNotHandledYet() {
        makeHost()
        start(mode: "presentation")
        XCTAssertEqual(host.active?.mode, .presentation)
        message("input.present", ["action": "next"])
        message("input.present", ["action": "laser_on", "x": 5000, "y": 5000])
        XCTAssertTrue(injector.calls.isEmpty)
        host.stopLocally()
        XCTAssertEqual(host.audit.entries.first?.rejected, 2)
        XCTAssertEqual(host.audit.entries.first?.counts, [:])
    }

    @MainActor func testDragHoldsTheLeftButtonAndMovesAsADrag() {
        makeHost()
        start()
        message("input.drag", ["phase": "start"])
        message("input.drag", ["phase": "move", "dx": 5, "dy": 6])
        message("input.pointer", ["dx": 1, "dy": 1])
        message("input.drag", ["phase": "end"])
        message("input.drag", ["phase": "end"])
        XCTAssertEqual(injector.calls, [.button(.left, true), .move(5, 6, [.left]), .move(1, 1, [.left]), .button(.left, false)])
    }

    @MainActor func testReleasesWithoutPressesAndDoublePressesAreIgnored() {
        makeHost()
        start()
        message("input.button", ["button": "right", "action": "up"])
        message("input.button", ["button": "right", "action": "down"])
        message("input.button", ["button": "right", "action": "down"])
        XCTAssertEqual(injector.calls, [.button(.right, true)])
    }

    // MARK: Sequence and replay

    @MainActor func testReplayedAndOlderMessagesAreDroppedAndCounted() {
        makeHost()
        start()
        message("input.pointer", ["dx": 1, "dy": 0], sequence: 5)
        message("input.pointer", ["dx": 2, "dy": 0], sequence: 5)
        message("input.pointer", ["dx": 3, "dy": 0], sequence: 4)
        message("input.pointer", ["dx": 4, "dy": 0], sequence: 6)
        XCTAssertEqual(injector.calls, [.move(1, 0, []), .move(4, 0, [])])
        message("input.session.stop", [:], sequence: 7)
        XCTAssertEqual(host.audit.entries.first?.rejected, 2)
    }

    @MainActor func testMessagesForAnotherSessionOrDeviceAreIgnored() {
        makeHost()
        start()
        message("input.pointer", ["dx": 1, "dy": 0], session: UUID().uuidString)
        message("input.pointer", ["dx": 1, "dy": 0], device: "tablet")
        XCTAssertTrue(injector.calls.isEmpty)
    }

    @MainActor func testAnEndedSessionCannotBeStartedAgain() {
        makeHost()
        start()
        message("input.session.stop", ["reason": "user"])
        now += 5
        start()
        XCTAssertNil(host.active, "a replayed start of an ended session")
    }

    @MainActor func testMalformedInputIsCountedNotInjected() {
        makeHost()
        start()
        message("input.key", ["key": "q", "modifiers": ["cmd"]])
        message("input.pointer", ["dx": 9999, "dy": 0])
        XCTAssertTrue(injector.calls.isEmpty)
        host.stopLocally()
        XCTAssertEqual(host.audit.entries.first?.rejected, 2)
    }

    // MARK: Rate limits

    @MainActor func testMotionOverOneHundredTwentyASecondIsDroppedAndCounted() {
        makeHost()
        start()
        for _ in 0..<150 { message("input.pointer", ["dx": 1, "dy": 0]) }
        XCTAssertEqual(injector.calls.count, 120)
        for _ in 0..<30 { message("input.key", ["key": "space"]) }
        XCTAssertEqual(injector.calls.count, 140, "keys have their own limit of 20 a second")
        now += 1
        message("input.pointer", ["dx": 1, "dy": 0])
        XCTAssertEqual(injector.calls.count, 141)
        message("input.session.stop", [:])
        XCTAssertEqual(host.audit.entries.first?.dropped, 40)
        XCTAssertEqual(host.audit.entries.first?.counts["pointer"], 121)
    }

    // MARK: Ending

    @MainActor func testIdleTimeoutEndsTheSessionAndHeartbeatsKeepItAlive() {
        makeHost()
        start()
        now += 4
        message("input.session.heartbeat")
        now += 4
        host.tick()
        XCTAssertNotNil(host.active)
        now += 2
        host.tick()
        XCTAssertNil(host.active)
        XCTAssertNil(indicator.shown)
        XCTAssertEqual(sent.last?.kind, "input.session.stop")
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "timeout")
        XCTAssertEqual(sent.last?.payload["sequence"] as? Int, 2, "the host's own sequence")
    }

    @MainActor func testLocalStopReleasesButtonsAndTellsThePhone() {
        makeHost()
        start()
        message("input.button", ["button": "left", "action": "down"])
        indicator.onStop?()
        XCTAssertNil(host.active)
        XCTAssertEqual(injector.calls.last, .button(.left, false))
        XCTAssertNil(indicator.shown)
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "local_stop")
        XCTAssertEqual(host.audit.entries.first?.endReason, "local_stop")
        XCTAssertEqual(host.audit.entries.first?.mode, "touchpad")
    }

    @MainActor func testRevokedGrantOrAccessibilityEndsTheSession() {
        makeHost()
        start()
        allowed = []
        message("input.pointer", ["dx": 1, "dy": 0])
        XCTAssertNil(host.active)
        XCTAssertTrue(injector.calls.isEmpty)
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "disabled")

        allowed = ["phone"]
        now += 2
        start(session: UUID().uuidString)
        host.policyChanged()
        XCTAssertNotNil(host.active)
        trusted = false
        host.tick()
        XCTAssertNil(host.active)
        XCTAssertEqual(host.audit.entries.first?.endReason, "permission_required")
    }

    @MainActor func testDisconnectEndsQuietly() {
        makeHost()
        start()
        message("input.button", ["button": "left", "action": "down"])
        let before = sent.count
        host.connectionClosed()
        XCTAssertNil(host.active)
        XCTAssertEqual(injector.calls.last, .button(.left, false))
        XCTAssertEqual(sent.count, before, "nothing to send on a closed session")
        XCTAssertEqual(host.audit.entries.first?.endReason, "disconnected")
    }

    // MARK: Accessibility

    @MainActor func testAccessibilityIsAskedOnlyWhenTheUserTurnsRemoteControlOn() {
        makeHost()
        trusted = false
        host.refreshAccessibility()
        XCTAssertEqual(accessibilityRequests, 0)
        host.remoteControlEnabled()
        XCTAssertEqual(accessibilityRequests, 1)
        trusted = true
        host.remoteControlEnabled()
        XCTAssertEqual(accessibilityRequests, 1, "never once it is allowed")
        XCTAssertTrue(host.accessibilityTrusted)
    }
}
