import Foundation
import XCTest
@testable import BridgeyMac

/// Property-based fuzzing of the frame reader, the envelope decoder and the payload decoders: random bytes,
/// mutated, truncated, oversized and deeply nested frames, wrong types, unknown kinds and replayed IDs. Each
/// property checks that nothing crashes, that the reader's memory stays bounded, that whatever is accepted
/// stays within the contract's bounds, and that the reader finds the next frame afterwards.
///
/// Runs are deterministic: 300 cases per property from a fixed seed. For longer runs set
/// BRIDGEY_FUZZ_ITERATIONS and, to explore other inputs, BRIDGEY_FUZZ_SEED; a failure prints its seed.
/// Android (ProtocolFuzzTest.kt) runs the same properties.
final class ProtocolFuzzTests: XCTestCase {
    private let iterations = ProcessInfo.processInfo.environment["BRIDGEY_FUZZ_ITERATIONS"].flatMap(Int.init) ?? 300
    private let seed = ProcessInfo.processInfo.environment["BRIDGEY_FUZZ_SEED"].flatMap(UInt64.init) ?? 20_261_028
    private lazy var random = SplitMix64(seed: seed)
    private var validFrames: [String] = []
    private var samples: [Data] = []
    private var sessionID = ""

    override func setUpWithError() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../protocol/test-vectors/protocol-v1.json")
        let vectors = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        sessionID = try XCTUnwrap(vectors["sessionId"] as? String)
        validFrames = try XCTUnwrap(vectors["frames"] as? [[String: Any]])
            .filter { $0["expect"] is [String: Any] && $0["lineEnding"] == nil }
            .compactMap { $0["line"] as? String }
            .map { $0.trimmingCharacters(in: .whitespaces) }
        for group in ["featuresUpdate", "batteryUpdate", "clipboardRich"] {
            for vector in try XCTUnwrap(vectors[group] as? [[String: Any]]) {
                if let raw = vector["plaintextString"] as? String { samples.append(Data(raw.utf8)) }
                else if let object = vector["plaintext"], let data = try? JSONSerialization.data(withJSONObject: object, options: .fragmentsAllowed) { samples.append(data) }
            }
        }
    }

    func testRandomBytesNeverCrashTheReaderAndMemoryStaysBounded() {
        property("random bytes", scale: 10) {
            var reader = ProtocolFrameReader()
            var stream = random.bytes(Int(random.next(below: UInt64(3 * maximumProtocolFrameBytes))))
            for _ in 0..<random.next(below: 64) where !stream.isEmpty { stream[Int(random.next(below: UInt64(stream.count)))] = 0x0A }
            var offset = 0
            reading: while offset < stream.count {
                // Chunks of the sizes a socket delivers, at most 65,536 bytes each.
                let size = min(stream.count - offset, 1 + Int(random.next(below: UInt64(maximumProtocolFrameBytes))))
                reader.append(stream.subdata(in: offset..<offset + size))
                offset += size
                while true {
                    do {
                        guard let message = try reader.next() else { break }
                        assertWithinLimits(message)
                    } catch {
                        // A bad frame closes the session; this stream is done.
                        break reading
                    }
                }
                if reader.overLimit { break }
                XCTAssertLessThanOrEqual(reader.buffer.count, maximumProtocolFrameBytes + 1)
            }
        }
    }

    func testMutatedFramesDecodeOrCloseCleanly() {
        property("mutated frames") {
            if let message = decode(mutate(Data(validFrames.randomElement(using: &random)!.utf8))) { assertWithinLimits(message) }
        }
    }

    func testTruncatedFramesNeverDecode() {
        property("truncated frames") {
            let frame = Data(validFrames.randomElement(using: &random)!.utf8)
            let cut = Int(random.next(below: UInt64(frame.count)))
            XCTAssertNil(decode(frame.prefix(cut)))
        }
    }

    func testWrongTypesAndUnknownKindsAreHandled() {
        property("structured envelopes") {
            var object: [String: Any] = [:]
            for field in ["kind", "sessionId", "messageId", "deviceId", "deviceName", "publicKey", "nonce", "ciphertext", "transferId", "sequence", "extra"]
            where random.next(below: 3) != 0 {
                object[field] = randomJSON(depth: 0)
            }
            if random.bool() { object["kind"] = randomKind() }
            if random.bool() { object["sessionId"] = sessionID }
            guard let data = try? JSONSerialization.data(withJSONObject: object) else { return }
            if let message = decode(data) {
                assertWithinLimits(message)
                XCTAssertEqual(object["kind"] as? String, message.kind)
            }
        }
    }

    func testOversizedAndDeepFramesClose() {
        property("oversized and deep frames", scale: 20) {
            let size = maximumProtocolFrameBytes + 1 + Int(random.next(below: 4096))
            var reader = ProtocolFrameReader()
            reader.append(Data(#"{"kind":"ping.ack","sessionId":"\#(sessionID)","x":""#.utf8) + Data(repeating: 0x61, count: size))
            XCTAssertTrue(reader.overLimit)
            XCTAssertThrowsError(try reader.next())
            let depth = maximumProtocolJSONDepth + 1 + Int(random.next(below: 30_000))
            let deep = #"{"kind":"x.y","sessionId":"\#(sessionID)","x":"# + String(repeating: "[", count: depth) + String(repeating: "]", count: depth) + "}"
            XCTAssertNil(decode(Data(deep.utf8)))
            XCTAssertNil(decode(Data((#"{"a":"# + String(repeating: #"{"a":"#, count: depth)).utf8)))
        }
    }

    func testReaderFindsTheNextFrameAfterABadOne() {
        property("resynchronize") {
            let good = validFrames.randomElement(using: &random)!
            let garbage = mutate(Data(validFrames.randomElement(using: &random)!.utf8)).filter { $0 != 0x0A }
            guard garbage.count <= maximumProtocolFrameBytes else { return }
            var reader = ProtocolFrameReader()
            reader.append(garbage + Data([0x0A]) + Data(good.utf8) + Data([0x0A]))
            _ = try? reader.next()
            XCTAssertNotNil(try? reader.next(), "lost the frame after \(garbage.count) bad bytes")
        }
    }

    func testReplayWindowMatchesAReferenceModel() {
        property("replay window", scale: 10) {
            var window = ReplayWindow()
            var reference: [String] = []
            let pool = 10 + random.next(below: 2_000)
            for _ in 0..<(1 + random.next(below: 3_000)) {
                let id = "id-\(random.next(below: pool))"
                let expected = !reference.contains(id)
                XCTAssertEqual(window.accept(id), expected, "replay of \(id)")
                if expected {
                    reference.append(id)
                    if reference.count > 256 { reference.removeFirst() }
                }
                XCTAssertLessThanOrEqual(window.count, 256)
            }
            for id in reference.suffix(10) { XCTAssertFalse(window.accept(id)) }
        }
    }

    func testPayloadDecodersRejectGarbageWithoutCrashing() {
        property("payload decoders") {
            let payload = randomPayload()
            _ = decodeFeatureState(payload)
            if let battery = decodeBatteryStatus(payload) { XCTAssertTrue((0...100).contains(battery.level)) }
            if let rich = decodeRichClipboard(payload) {
                XCTAssertFalse(rich.text.isEmpty)
                XCTAssertTrue(rich.html.contains(where: { !$0.isWhitespace }))
                XCTAssertLessThanOrEqual(rich.text.utf8.count + rich.html.utf8.count, maximumClipboardContentBytes)
            }
            let features = Data((#"{"version":1,"features":["# + String(repeating: "1,", count: 1_000) + "1]}").utf8)
            XCTAssertEqual(decodeFeatureState(features), .invalid)
        }
    }

    // MARK: helpers

    private func property(_ name: String, scale: Int = 1, file: StaticString = #filePath, line: UInt = #line, _ body: () throws -> Void) {
        let failuresBefore = testRun?.failureCount ?? 0
        for index in 0..<max(1, iterations / scale) {
            do { try body() } catch { XCTFail("fuzz property \"\(name)\" threw \(error)", file: file, line: line) }
            if (testRun?.failureCount ?? 0) > failuresBefore {
                XCTFail("fuzz property \"\(name)\" failed at case \(index) with seed \(seed) (BRIDGEY_FUZZ_SEED=\(seed))", file: file, line: line)
                return
            }
        }
    }

    private func decode(_ frame: Data) -> PairingMessage? {
        var reader = ProtocolFrameReader()
        reader.append(frame + Data([0x0A]))
        return (try? reader.next()) ?? nil
    }

    private func assertWithinLimits(_ message: PairingMessage) {
        XCTAssertTrue(!message.kind.isEmpty && message.kind.utf8.count <= 64)
        XCTAssertLessThanOrEqual(message.sessionId.utf8.count, 128)
        XCTAssertTrue(!message.sessionId.isEmpty || message.kind == "pairing.cancel")
        if let sequence = message.sequence { XCTAssertLessThanOrEqual(abs(sequence), Int64(jsonMaximumSafeInteger)) }
        for value in [message.messageId, message.transferId, message.nonce, message.deviceName].compactMap({ $0 }) { XCTAssertFalse(value.isEmpty) }
    }

    private func mutate(_ data: Data) -> Data {
        var value = data
        for _ in 0..<(1 + random.next(below: 5)) {
            switch random.next(below: 6) {
            case 0: if !value.isEmpty { value[value.startIndex + Int(random.next(below: UInt64(value.count)))] = UInt8(truncatingIfNeeded: random.next()) }
            case 1: if !value.isEmpty { value = value.prefix(Int(random.next(below: UInt64(value.count)))) }
            case 2: value.append(random.bytes(1 + Int(random.next(below: 31))))
            case 3: if value.count > 1 { value.remove(at: value.startIndex + Int(random.next(below: UInt64(value.count - 1)))) }
            case 4: value += value
            default:
                let text = String(decoding: value, as: UTF8.self)
                value = Data(text.replacingOccurrences(of: "\"sessionId\"", with: random.bool() ? "\"sessionid\"" : "\"sessionId\":5,\"x\"").utf8)
            }
            value = Data(value)
        }
        return value
    }

    private func randomKind() -> String {
        switch random.next(below: 5) {
        case 0: return "features.update"
        case 1: return "future.kind\(random.next(below: 100))"
        case 2: return "k." + String(repeating: "x", count: 60 + Int(random.next(below: 10)))
        case 3: return ""
        default: return ["pairing.cancel", "files.chunk", "quick.request", "notifications.post"].randomElement(using: &random)!
        }
    }

    private func randomString() -> String {
        switch random.next(below: 5) {
        case 0: return ""
        case 1: return String(repeating: "x", count: Int(random.next(below: 300)))
        case 2: return String(String.UnicodeScalarView((0..<random.next(below: 40)).compactMap { _ in Unicode.Scalar(UInt32(0x20 + random.next(below: 0x2FDF))) }))
        case 3: return "\u{0}\u{7}\n\"\\"
        default: return String(format: "%016llx%016llx", random.next(), random.next())
        }
    }

    private func randomJSON(depth: Int) -> Any {
        switch random.next(below: depth > 6 ? 6 : 9) {
        case 0: return randomString()
        case 1: return Int(truncatingIfNeeded: random.next())
        case 2: return Double(random.next(below: 1_000_000)) / 7
        case 3: return random.bool()
        case 4: return NSNull()
        case 5: return [Int64.max, Int64.min, 9_007_199_254_740_992, 0, -1].randomElement(using: &random)!
        case 6: return (0..<random.next(below: 5)).map { _ in randomJSON(depth: depth + 1) }
        case 7: return Dictionary((0..<random.next(below: 5)).map { _ in (String(randomString().prefix(12)), randomJSON(depth: depth + 1)) }, uniquingKeysWith: { a, _ in a })
        default: return randomString()
        }
    }

    private func randomPayload() -> Data {
        switch random.next(below: 4) {
        case 0: return random.bytes(Int(random.next(below: 4096)))
        case 1: return mutate(samples.randomElement(using: &random)!)
        case 2:
            var object: [String: Any] = [:]
            for key in ["version", "features", "level", "isCharging", "text", "html"] where random.bool() {
                object[key] = randomJSON(depth: 0)
            }
            return (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        default: return samples.randomElement(using: &random)!
        }
    }
}

/// A small seedable generator, so a failing fuzz run can be replayed with BRIDGEY_FUZZ_SEED.
struct SplitMix64: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    mutating func next(below bound: UInt64) -> UInt64 { bound == 0 ? 0 : next() % bound }
    mutating func bool() -> Bool { next() & 1 == 1 }
    mutating func bytes(_ count: Int) -> Data { Data((0..<count).map { _ in UInt8(truncatingIfNeeded: next()) }) }
}
