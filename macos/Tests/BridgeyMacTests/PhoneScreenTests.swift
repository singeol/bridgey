import Foundation
import XCTest
@testable import BridgeyMac

/// The frame header, reassembly, Annex B, the session sequence and message bounds.
final class PhoneScreenProtocolTests: XCTestCase {
    private let stream = "6c2e9a14-3b5d-4e7f-8a90-1b2c3d4e5f60"
    private let sps = Data([0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x78])
    private let pps = Data([0x68, 0xEB, 0xE3, 0xCB, 0x22, 0xC0])
    private let idr = Data([0x65, 0x88, 0x84, 0x21])

    private func part(_ frame: UInt32, _ index: Int, last: Bool, key: Bool = false, sequence: Int = 1, payload: Data? = nil) -> ScreenFramePart {
        ScreenFramePart(streamID: stream, sequence: sequence, frame: frame, part: index, key: key, parameterSets: key, last: last,
                        captureMicros: 5, payload: payload ?? (index == 0 ? Data([0, 0, 0, 1, 0x41, UInt8(frame & 0xFF)]) : Data([UInt8(index)])))
    }

    func testHeaderRoundTripsAndChecksItsLimits() {
        let original = part(7, 0, last: true, key: true, sequence: 42)
        let encoded = original.encoded()
        XCTAssertEqual(encoded.count, 40 + original.payload.count)
        XCTAssertEqual(encoded[0], 1)
        XCTAssertEqual(encoded[1], 0x07)
        XCTAssertEqual(ScreenFramePart.decode(encoded), original)
        var badVersion = encoded
        badVersion[0] = 2
        XCTAssertNil(ScreenFramePart.decode(badVersion))
        XCTAssertNil(ScreenFramePart.decode(encoded.prefix(40)), "header only")
        XCTAssertNil(ScreenFramePart.decode(part(7, 64, last: true).encoded()), "at most 64 parts")
        XCTAssertNil(ScreenFramePart.decode(part(7, 0, last: true, sequence: 0).encoded()))
        XCTAssertNil(ScreenFramePart.decode(part(0, 0, last: true).encoded()))
        XCTAssertEqual(ScreenFramePart.decode(part(7, 1, last: false, payload: Data([0xAA])).encoded())?.payload, Data([0xAA]),
                       "later parts need no start code")
        XCTAssertNil(ScreenFramePart.decode(part(7, 0, last: true, payload: Data([0x41, 0x9A])).encoded()), "part 0 starts with a start code")
        XCTAssertNotNil(ScreenFramePart.decode(part(7, 1, last: true, payload: Data(count: 32_768)).encoded()))
        XCTAssertNil(ScreenFramePart.decode(part(7, 1, last: true, payload: Data(count: 32_769)).encoded()))
    }

    func testAssemblerJoinsPartsInOrderAndLosesFramesOutOfOrder() {
        var assembler = ScreenFrameAssembler()
        XCTAssertEqual(assembler.add(part(1, 0, last: false, key: true)), .pending)
        XCTAssertEqual(assembler.add(part(1, 1, last: false)), .pending)
        guard case let .complete(frame) = assembler.add(part(1, 2, last: true)) else { return XCTFail("frame 1") }
        XCTAssertEqual(frame.frame, 1)
        XCTAssertTrue(frame.key)
        XCTAssertEqual(frame.data, Data([0, 0, 0, 1, 0x41, 1, 1, 2]))
        XCTAssertEqual(assembler.add(part(1, 0, last: true)), .ignored, "a part of a finished frame")
        XCTAssertEqual(assembler.add(part(2, 1, last: true)), .lost(frame: 2), "part 1 before part 0")
        XCTAssertEqual(assembler.add(part(2, 0, last: true)), .ignored, "a lost frame stays lost")
        XCTAssertEqual(assembler.add(part(3, 0, last: false)), .pending)
        XCTAssertEqual(assembler.add(part(4, 0, last: true)), .lost(frame: 4), "3 was lost and 4 is not a key frame")
        XCTAssertEqual(assembler.add(part(5, 0, last: false, key: true)), .pending)
        guard case let .complete(next) = assembler.add(part(5, 1, last: true)) else { return XCTFail("frame 5") }
        XCTAssertTrue(next.key)
    }

    func testAKeyFrameAfterALostOneStillDecodes() {
        var assembler = ScreenFrameAssembler()
        XCTAssertEqual(assembler.add(part(1, 0, last: false)), .pending)
        guard case let .complete(frame) = assembler.add(part(2, 0, last: true, key: true)) else { return XCTFail("key frame") }
        XCTAssertEqual(frame.frame, 2)
    }

    func testAnnexBSplitsNALUnitsAndBuildsAVCC() {
        var accessUnit = Data([0, 0, 0, 1])
        accessUnit.append(sps)
        accessUnit.append(contentsOf: [0, 0, 0, 1])
        accessUnit.append(pps)
        accessUnit.append(contentsOf: [0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1])
        accessUnit.append(idr)
        accessUnit.append(contentsOf: [0, 0])
        XCTAssertEqual(AnnexB.nalUnits(accessUnit), [sps, pps, Data([0x09, 0xF0]), idr])
        let split = AnnexB.avcc(accessUnit)
        XCTAssertEqual(split.sps, sps)
        XCTAssertEqual(split.pps, [pps])
        XCTAssertEqual(split.avcc, Data([0, 0, 0, 4]) + idr, "parameter sets and the delimiter are left out")
        XCTAssertTrue(annexBStartsWithStartCode(Data([0, 0, 1, 0x65])))
        XCTAssertTrue(annexBStartsWithStartCode(Data([0, 0, 0, 1, 0x65])))
        XCTAssertFalse(annexBStartsWithStartCode(Data([0, 0, 2, 1])))
    }

    func testSessionSequenceOnlyGoesUp() {
        var sequence = ScreenSequence()
        XCTAssertFalse(sequence.accept(0))
        XCTAssertTrue(sequence.accept(1))
        XCTAssertFalse(sequence.accept(1), "a replay")
        XCTAssertTrue(sequence.accept(10), "gaps are fine")
        XCTAssertFalse(sequence.accept(9))
        XCTAssertTrue(sequence.accept(ScreenLimits.maximumSequence))
        XCTAssertFalse(sequence.accept(ScreenLimits.maximumSequence + 1))
    }

    func testJSONMessagesAreStrict() throws {
        func data(_ object: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: object) }
        let start: [String: Any] = ["version": 1, "streamId": stream.uppercased(), "sequence": 1, "codec": "h264", "width": 720,
                                    "height": 1600, "fps": 30, "bitrate": 4_000_000]
        XCTAssertEqual(ScreenStart.decode(try data(start))?.header, ScreenMessageHeader(streamID: stream, sequence: 1))
        for (key, value) in [("codec", "h265"), ("width", 721), ("height", 8), ("fps", 61), ("bitrate", 99_999), ("version", 2),
                             ("sequence", 0), ("sequence", 1.5), ("width", true), ("streamId", "nope")] as [(String, Any)] {
            var bad = start
            bad[key] = value
            XCTAssertNil(ScreenStart.decode(try data(bad)), "\(key) = \(value)")
        }
        var padded = start
        padded["padding"] = String(repeating: "x", count: 1024)
        XCTAssertNil(ScreenStart.decode(try data(padded)), "over 1,024 bytes")
        XCTAssertNil(ScreenStart.decode(Data("{not json".utf8)))
        let stop = try data(["version": 1, "streamId": stream, "sequence": 3, "reason": "later"])
        XCTAssertEqual(ScreenStop.decode(stop)?.reason, .failed, "an unknown reason reads as failed")
        let stats = try data(["version": 1, "streamId": stream, "sequence": 4, "latencyMs": 48, "rttMs": 4, "bitrate": 4_000_000,
                              "sentBitrate": 2_100_000, "fps": 30, "dropped": 0])
        XCTAssertEqual(ScreenStats.decode(stats)?.latencyMs, 48)
        XCTAssertEqual(screenStatsLine(ScreenStats.decode(stats)), "48 ms, 2.1 Mb/s, 30 fps")
        XCTAssertNil(screenAckPayload(streamID: stream, frame: 3, decodeMs: 4, keyframe: false)["keyframe"])
        XCTAssertEqual(screenAckPayload(streamID: stream, frame: 3, decodeMs: 4, keyframe: true)["keyframe"] as? Bool, true)
        XCTAssertEqual(screenAckPayload(streamID: stream, frame: 3, decodeMs: 90_000, keyframe: false)["decodeMs"] as? Int, 60_000)
    }
}

/// Starts, refusals, stops and timeouts.
@MainActor
final class PhoneScreenTests: XCTestCase {
    private var now = Date(timeIntervalSince1970: 1_000_000)
    private var sent: [(kind: String, payload: [String: Any])] = []
    private var sequence = ScreenSequence()
    private var presenter: FakePresenter!
    private let streamID = "6c2e9a14-3b5d-4e7f-8a90-1b2c3d4e5f60"

    private func make() -> PhoneScreen {
        let screen = PhoneScreen(clock: { [unowned self] in self.now }, usesTimer: false)
        screen.send = { [unowned self] kind, payload in self.sent.append((kind, payload)); return true }
        presenter = FakePresenter()
        screen.presenter = presenter
        return screen
    }

    private func json(_ fields: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: fields) }

    private func start(_ fields: [String: Any] = [:]) -> Data {
        var payload: [String: Any] = ["version": 1, "streamId": streamID, "sequence": 1, "codec": "h264", "width": 720, "height": 1600,
                                      "fps": 30, "bitrate": 4_000_000]
        payload.merge(fields) { $1 }
        return json(payload)
    }

    private func deliver(_ screen: PhoneScreen, _ kind: String, _ data: Data, enabled: Bool = true) {
        screen.receive(kind: kind, data, phoneName: "Pixel", enabled: enabled) { self.sequence.accept($0) }
    }

    func testAStartOpensTheWindowAndAnswersStarted() {
        let screen = make()
        deliver(screen, "screen.start", start())
        XCTAssertEqual(presenter.opened, ["Pixel"])
        XCTAssertEqual(presenter.size, [720, 1600])
        XCTAssertEqual(screen.showing, "Pixel")
        XCTAssertEqual(sent.last?.kind, "screen.started")
        XCTAssertEqual(sent.last?.payload["streamId"] as? String, streamID)
        screen.stopShowing()
        XCTAssertEqual(sent.last?.kind, "screen.stop")
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "closed")
        XCTAssertNil(screen.showing)
        XCTAssertEqual(presenter.closed.count, 1)
    }

    func testAReplayedStartIsDropped() {
        let screen = make()
        deliver(screen, "screen.start", start())
        screen.stopShowing()
        sent.removeAll()
        deliver(screen, "screen.start", start())
        XCTAssertTrue(sent.isEmpty, "the same session sequence is never accepted twice")
        XCTAssertEqual(presenter.opened.count, 1)
    }

    func testStartsAreRefusedWhenOffOrInvalid() {
        let screen = make()
        deliver(screen, "screen.start", start(), enabled: false)
        XCTAssertEqual(sent.last?.kind, "screen.stop")
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "disabled")
        deliver(screen, "screen.start", start(["sequence": 2, "codec": "h265"]))
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "invalid")
        XCTAssertTrue(presenter.opened.isEmpty)
        sent.removeAll()
        deliver(screen, "screen.start", Data("{not json".utf8))
        XCTAssertTrue(sent.isEmpty, "nothing to answer")
    }

    func testThePhoneStopsTheStreamWithoutAnAnswer() {
        let screen = make()
        deliver(screen, "screen.start", start())
        deliver(screen, "screen.stop", json(["version": 1, "streamId": UUID().uuidString, "sequence": 2, "reason": "user"]))
        XCTAssertEqual(screen.showing, "Pixel", "another stream's stop changes nothing")
        deliver(screen, "screen.stop", json(["version": 1, "streamId": streamID, "sequence": 3, "reason": "screen_off"]))
        XCTAssertNil(screen.showing)
        XCTAssertEqual(presenter.closed.last ?? nil, "The screen of Pixel turned off")
        XCTAssertEqual(sent.last?.kind, "screen.started")
    }

    func testStatsReachTheWindowAndStaleOnesAreDropped() {
        let screen = make()
        deliver(screen, "screen.start", start())
        func stats(_ sequence: Int, _ latency: Int) -> Data {
            json(["version": 1, "streamId": streamID, "sequence": sequence, "latencyMs": latency, "rttMs": 4,
                  "bitrate": 4_000_000, "sentBitrate": 2_100_000, "fps": 30, "dropped": 0])
        }
        deliver(screen, "screen.stats", stats(3, 48))
        deliver(screen, "screen.stats", stats(2, 99))
        XCTAssertEqual(presenter.stats.map(\.latencyMs), [48])
        deliver(screen, "screen.stats", stats(4, 50), enabled: false)
        XCTAssertEqual(presenter.stats.count, 1, "nothing is read while the setting is off")
    }

    func testTimeoutSwitchingOffAndDisconnectEndTheStream() {
        let screen = make()
        deliver(screen, "screen.start", start())
        now = now.addingTimeInterval(PhoneScreen.streamTimeout - 1)
        screen.tick()
        XCTAssertNotNil(screen.showing)
        now = now.addingTimeInterval(1)
        screen.tick()
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "timeout")
        XCTAssertNil(screen.showing)

        deliver(screen, "screen.start", start(["sequence": 2, "streamId": UUID().uuidString]))
        screen.disabledHere()
        XCTAssertEqual(sent.last?.payload["reason"] as? String, "disabled")
        XCTAssertNil(screen.showing)

        deliver(screen, "screen.start", start(["sequence": 3, "streamId": UUID().uuidString]))
        sent.removeAll()
        screen.unavailable(disconnected: true)
        XCTAssertTrue(sent.isEmpty, "nothing is sent to a phone that is gone")
        XCTAssertEqual(presenter.closed.last ?? nil, "Pixel disconnected")
    }
}

@MainActor
private final class FakePresenter: PhoneScreenPresenter {
    var opened: [String] = []
    var size: [Int] = []
    var closed: [String?] = []
    var stats: [ScreenStats] = []

    func open(phoneName: String, width: Int, height: Int, onStop: @escaping () -> Void) -> ScreenDisplaySink? {
        opened.append(phoneName)
        size = [width, height]
        return nil
    }

    func update(stats: ScreenStats) { self.stats.append(stats) }
    func resize(width: Int, height: Int) { size = [width, height] }
    func close(message: String?) { closed.append(message) }
}
