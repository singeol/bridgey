import XCTest
@testable import BridgeyMac

final class RemoteInputProtocolTests: XCTestCase {
    private let session = "6f1c2a7e-4b1d-4c8e-9a51-0c2f6f7d1e0b"

    private func data(_ fields: [String: Any], sequence: Any = 2, version: Any = 1) -> Data {
        var object = fields
        object["version"] = version
        object["inputSessionId"] = session
        object["sequence"] = sequence
        return try! JSONSerialization.data(withJSONObject: object)
    }

    private func parse(_ kind: String, _ fields: [String: Any], sequence: Any = 2) -> RemoteInputCommand? {
        RemoteInputMessage.parse(kind: kind, data: data(fields, sequence: sequence))?.command
    }

    // MARK: Message validation and bounds

    func testEnvelopeNeedsVersionSessionAndPositiveSequence() {
        XCTAssertEqual(parse("input.session.heartbeat", [:]), .heartbeat)
        XCTAssertEqual(RemoteInputMessage.parse(kind: "input.session.heartbeat", data: data([:]))?.inputSessionID, session)
        XCTAssertNil(RemoteInputMessage.parse(kind: "input.session.heartbeat", data: data([:], version: 2)))
        XCTAssertNil(RemoteInputMessage.parse(kind: "input.session.heartbeat", data: data([:], version: true)))
        for bad: Any in [0, -1, 1.5, true, "3", 9_007_199_254_740_992 as Int64] {
            XCTAssertNil(parse("input.session.heartbeat", [:], sequence: bad), "\(bad)")
        }
        var object: [String: Any] = ["version": 1, "inputSessionId": "not-a-uuid", "sequence": 1]
        XCTAssertNil(RemoteInputMessage.parse(kind: "input.session.heartbeat", data: try! JSONSerialization.data(withJSONObject: object)))
        object["inputSessionId"] = session
        XCTAssertNil(RemoteInputMessage.parse(kind: "input.unknown", data: try! JSONSerialization.data(withJSONObject: object)))
        XCTAssertNil(RemoteInputMessage.parse(kind: "input.pointer", data: Data("[1,2]".utf8)))
        XCTAssertNil(RemoteInputMessage.parse(kind: "input.pointer", data: Data("not json".utf8)))
    }

    func testPlaintextOverTwoKilobytesIsRejectedBeforeParsing() {
        let padding = String(repeating: "x", count: 2_100)
        XCTAssertNil(RemoteInputMessage.parse(kind: "input.session.heartbeat", data: data(["pad": padding])))
    }

    func testStartModesAndStopReasons() {
        XCTAssertEqual(parse("input.session.start", ["mode": "touchpad"]), .start(mode: .touchpad))
        XCTAssertEqual(parse("input.session.start", ["mode": "presentation"]), .start(mode: .presentation))
        XCTAssertEqual(parse("input.session.start", ["mode": "screen"]), .start(mode: nil))
        XCTAssertEqual(parse("input.session.stop", ["reason": "screen_off"]), .stop(reason: .screenOff))
        XCTAssertEqual(parse("input.session.stop", ["reason": "left_screen"]), .stop(reason: .leftScreen))
        // A controller cannot claim a host reason, and an unknown reason still stops.
        XCTAssertEqual(parse("input.session.stop", ["reason": "local_stop"]), .stop(reason: .user))
        XCTAssertEqual(parse("input.session.stop", ["reason": "whatever"]), .stop(reason: .user))
        XCTAssertEqual(parse("input.session.stop", [:]), .stop(reason: .user))
    }

    func testPointerAndDragDeltasAreBounded() {
        XCTAssertEqual(parse("input.pointer", ["dx": 512, "dy": -512]), .pointer(dx: 512, dy: -512))
        XCTAssertEqual(parse("input.pointer", ["dx": 0, "dy": 3]), .pointer(dx: 0, dy: 3))
        XCTAssertNil(parse("input.pointer", ["dx": 513, "dy": 0]))
        XCTAssertNil(parse("input.pointer", ["dx": 0, "dy": -513]))
        XCTAssertNil(parse("input.pointer", ["dx": 0, "dy": 0]))
        XCTAssertNil(parse("input.pointer", ["dx": 1.5, "dy": 0]))
        XCTAssertNil(parse("input.pointer", ["dx": true, "dy": 0]))
        XCTAssertNil(parse("input.pointer", ["dx": "4", "dy": 0]))
        XCTAssertNil(parse("input.pointer", ["dx": 4]))
        XCTAssertEqual(parse("input.drag", ["phase": "start"]), .dragStart)
        XCTAssertEqual(parse("input.drag", ["phase": "move", "dx": -4, "dy": 9]), .dragMove(dx: -4, dy: 9))
        XCTAssertEqual(parse("input.drag", ["phase": "end"]), .dragEnd)
        XCTAssertNil(parse("input.drag", ["phase": "move", "dx": 600, "dy": 0]))
        XCTAssertNil(parse("input.drag", ["phase": "fling"]))
    }

    func testButtonsAndScrollUnits() {
        XCTAssertEqual(parse("input.button", ["button": "left", "action": "down"]), .button(.left, down: true))
        XCTAssertEqual(parse("input.button", ["button": "right", "action": "up"]), .button(.right, down: false))
        XCTAssertEqual(parse("input.button", ["button": "middle", "action": "down"]), .button(.middle, down: true))
        XCTAssertNil(parse("input.button", ["button": "back", "action": "down"]))
        XCTAssertNil(parse("input.button", ["button": "left", "action": "click"]))
        XCTAssertEqual(parse("input.scroll", ["dx": 0, "dy": -1000, "unit": "pixel"]), .scroll(dx: 0, dy: -1000, unit: .pixel))
        XCTAssertNil(parse("input.scroll", ["dx": 0, "dy": 1001, "unit": "pixel"]))
        XCTAssertEqual(parse("input.scroll", ["dx": 10, "dy": 0, "unit": "line"]), .scroll(dx: 10, dy: 0, unit: .line))
        XCTAssertNil(parse("input.scroll", ["dx": 11, "dy": 0, "unit": "line"]))
        XCTAssertNil(parse("input.scroll", ["dx": 1, "dy": 1, "unit": "page"]))
        XCTAssertNil(parse("input.scroll", ["dx": 0, "dy": 0, "unit": "pixel"]))
    }

    func testTextIsBoundedAndHasNoControlCharacters() {
        XCTAssertEqual(parse("input.text", ["text": "Hello, Привет 👋"]), .text("Hello, Привет 👋"))
        XCTAssertEqual(parse("input.text", ["text": String(repeating: "a", count: 256)]), .text(String(repeating: "a", count: 256)))
        XCTAssertNil(parse("input.text", ["text": String(repeating: "a", count: 257)]))
        XCTAssertNil(parse("input.text", ["text": ""]))
        // 256 four-byte scalars are exactly 1,024 UTF-8 bytes, the most a message may carry.
        XCTAssertTrue(isValidRemoteText(String(repeating: "𝄞", count: 256)))
        XCTAssertFalse(isValidRemoteText(String(repeating: "𝄞", count: 257)))
        for control in ["a\nb", "a\rb", "a\tb", "\u{1B}[31m", "a\u{0}b", "a\u{7F}", "a\u{85}b"] {
            XCTAssertNil(parse("input.text", ["text": control]), control.debugDescription)
        }
        XCTAssertNil(parse("input.text", ["text": 42]))
    }

    func testPresentationActionsAndLaserBounds() {
        for action in ["next", "previous", "start", "end", "black", "laser_off"] {
            XCTAssertEqual(parse("input.present", ["action": action]), .present(PresentAction(rawValue: action)!, x: nil, y: nil))
        }
        XCTAssertEqual(parse("input.present", ["action": "laser_on", "x": 0, "y": 10_000]), .present(.laserOn, x: 0, y: 10_000))
        XCTAssertEqual(parse("input.present", ["action": "laser_move", "x": 5_000, "y": 2_500]), .present(.laserMove, x: 5_000, y: 2_500))
        XCTAssertNil(parse("input.present", ["action": "laser_on"]))
        XCTAssertNil(parse("input.present", ["action": "laser_move", "x": 10_001, "y": 0]))
        XCTAssertNil(parse("input.present", ["action": "laser_move", "x": -1, "y": 0]))
        XCTAssertNil(parse("input.present", ["action": "applescript"]))
    }

    func testRateClassesAndAuditTypes() {
        XCTAssertEqual(RemoteInputCommand.pointer(dx: 1, dy: 0).rateClass, .motion)
        XCTAssertEqual(RemoteInputCommand.scroll(dx: 1, dy: 0, unit: .pixel).rateClass, .motion)
        XCTAssertEqual(RemoteInputCommand.dragMove(dx: 1, dy: 0).rateClass, .motion)
        XCTAssertEqual(RemoteInputCommand.present(.laserMove, x: 1, y: 1).rateClass, .motion)
        XCTAssertEqual(RemoteInputCommand.present(.next, x: nil, y: nil).rateClass, .discrete)
        XCTAssertEqual(RemoteInputCommand.text("a").rateClass, .discrete)
        XCTAssertEqual(RemoteInputCommand.key(.tab, shift: false).rateClass, .discrete)
        XCTAssertEqual(RemoteInputCommand.dragStart.rateClass, .discrete)
        XCTAssertEqual(RemoteInputCommand.heartbeat.rateClass, .control)
        XCTAssertNil(RemoteInputCommand.heartbeat.eventType)
        XCTAssertEqual(RemoteInputCommand.dragEnd.eventType, .drag)
    }

    // MARK: Key allowlist

    func testOnlyNamedKeysWithAtMostShift() {
        for key in RemoteKey.allCases {
            XCTAssertEqual(parse("input.key", ["key": key.rawValue]), .key(key, shift: false), key.rawValue)
            XCTAssertEqual(parse("input.key", ["key": key.rawValue, "modifiers": []]), .key(key, shift: false), key.rawValue)
        }
        XCTAssertEqual(parse("input.key", ["key": "tab", "modifiers": ["shift"]]), .key(.tab, shift: true))
        XCTAssertEqual(parse("input.key", ["key": "left", "modifiers": ["shift"]]), .key(.left, shift: true))
        // No other modifier exists on the wire, and the one shortcut cannot take Shift.
        for modifiers in [["cmd"], ["command"], ["control"], ["ctrl"], ["option"], ["alt"], ["meta"], ["fn"], ["shift", "cmd"], ["shift", "shift"]] {
            XCTAssertNil(parse("input.key", ["key": "tab", "modifiers": modifiers]), modifiers.description)
        }
        XCTAssertNil(parse("input.key", ["key": "previous_app", "modifiers": ["shift"]]))
        XCTAssertNil(parse("input.key", ["key": "tab", "modifiers": "shift"]))
        for key in ["q", "w", "a", "c", "v", "delete", "f1", "f12", "power", "cmd+tab", "space ", "Return"] {
            XCTAssertNil(parse("input.key", ["key": key]), key)
        }
    }

    // MARK: Sequence and replay

    func testSequenceAcceptsOnlyIncreasingValues() {
        var sequence = RemoteInputSequence()
        XCTAssertTrue(sequence.accept(1))
        XCTAssertTrue(sequence.accept(2))
        XCTAssertFalse(sequence.accept(2), "replay")
        XCTAssertFalse(sequence.accept(1), "older")
        XCTAssertTrue(sequence.accept(10), "gaps are fine; TCP keeps order")
        XCTAssertFalse(sequence.accept(9))
        XCTAssertFalse(sequence.accept(0))
        XCTAssertFalse(sequence.accept(-5))
        XCTAssertFalse(sequence.accept(RemoteInputLimits.maximumSequence + 1))
        XCTAssertTrue(sequence.accept(RemoteInputLimits.maximumSequence))
        XCTAssertFalse(sequence.accept(RemoteInputLimits.maximumSequence))
    }

    // MARK: Rate limiter

    func testBucketAllowsOneSecondBurstThenRefillsAtTheRate() {
        var bucket = TokenBucket(ratePerSecond: 120)
        let start: TimeInterval = 1_000
        XCTAssertEqual((0..<200).filter { _ in bucket.allow(at: start) }.count, 120)
        XCTAssertFalse(bucket.allow(at: start + 0.001))
        XCTAssertTrue(bucket.allow(at: start + 0.01), "1.2 tokens refilled after 10 ms")
        XCTAssertFalse(bucket.allow(at: start + 0.01))
        // Never more than a second's worth, however long the pause.
        XCTAssertEqual((0..<500).filter { _ in bucket.allow(at: start + 3_600) }.count, 120)
    }

    func testBucketSustainsItsRateAndIgnoresAClockStepBack() {
        var bucket = TokenBucket(ratePerSecond: 20)
        var accepted = 0
        // 100 messages a second for ten seconds: 20 a second pass, plus the first second's burst.
        for index in 0..<1_000 where bucket.allow(at: Double(index) / 100) { accepted += 1 }
        XCTAssertTrue((216...222).contains(accepted), "\(accepted)")
        var stepped = TokenBucket(ratePerSecond: 20)
        for _ in 0..<20 { _ = stepped.allow(at: 50) }
        XCTAssertFalse(stepped.allow(at: 10), "a clock that steps back refills nothing")
    }

    func testLimiterKeepsClassesApart() {
        var limiter = RemoteInputRateLimiter()
        let now: TimeInterval = 5
        XCTAssertEqual((0..<150).filter { _ in limiter.allow(.motion, at: now) }.count, 120)
        XCTAssertEqual((0..<50).filter { _ in limiter.allow(.discrete, at: now) }.count, 20)
        XCTAssertEqual((0..<50).filter { _ in limiter.allow(.control, at: now) }.count, 10)
    }

    // MARK: Replies

    func testRepliesCarryVersionSessionAndSequence() throws {
        let started = RemoteInputReply.started(inputSessionID: session, sequence: 1)
        XCTAssertEqual(started["version"] as? Int, 1)
        XCTAssertEqual(started["inputSessionId"] as? String, session)
        XCTAssertEqual(started["sequence"] as? Int, 1)
        XCTAssertEqual(started["idleSeconds"] as? Int, 6)
        XCTAssertEqual((started["limits"] as? [String: Int])?["motion"], 120)
        let stop = RemoteInputReply.stop(inputSessionID: session, sequence: 2, reason: .permissionRequired)
        XCTAssertEqual(stop["reason"] as? String, "permission_required")
        XCTAssertNoThrow(try JSONSerialization.data(withJSONObject: stop))
    }
}
