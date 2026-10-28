import Foundation
import XCTest
@testable import BridgeyMac

/// protocol/test-vectors/protocol-v1.json: Android (ProtocolGoldenVectorsTest.kt) decodes the same vectors
/// and must reach the same results.
final class ProtocolGoldenVectorsTests: XCTestCase {
    private var vectors: [String: Any] = [:]
    private var sessionID = ""

    override func setUpWithError() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../protocol/test-vectors/protocol-v1.json")
            .standardizedFileURL
        vectors = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        sessionID = try XCTUnwrap(vectors["sessionId"] as? String)
    }

    func testFrames() throws {
        try each("frames") { vector in
            let prefix = #"{"kind":"pairing.offer","sessionId":"\#(sessionID)","deviceName":""#
            let padding = (vector["padTo"] as? Int).map { String(repeating: "a", count: $0 - prefix.utf8.count - 2) } ?? ""
            var bytes: Data
            if let base64 = vector["lineBase64"] as? String {
                bytes = try XCTUnwrap(Data(base64Encoded: base64))
            } else if let depth = vector["nest"] as? Int {
                let inner = String(repeating: "[", count: depth - 1) + String(repeating: "]", count: depth - 1)
                bytes = Data(#"{"kind":"x.y","sessionId":"\#(sessionID)","x":\#(inner)}"#.utf8)
            } else if vector["padTo"] != nil {
                bytes = Data((prefix + padding + "\"}").utf8)
            } else {
                bytes = Data(try XCTUnwrap(vector["line"] as? String).utf8)
            }
            bytes.append(Data(((vector["lineEnding"] as? String) ?? "\n").utf8))
            var reader = ProtocolFrameReader()
            reader.append(bytes)
            let decoded = (try? reader.next()).map(Self.fields)
            if (vector["expect"] as? String) == "reject" { return decoded == nil }
            guard let decoded, var expected = vector["expect"] as? [String: Any] else { return false }
            for (key, value) in expected where value as? String == "@PAD@" { expected[key] = padding }
            return NSDictionary(dictionary: decoded).isEqual(to: expected)
        }
    }

    func testFeaturesUpdate() throws {
        try each("featuresUpdate") { vector in
            let result: Any
            switch decodeFeatureState(plaintext(vector)) {
            case .invalid: result = "invalid"
            case .ignored: result = "ignored"
            case let .state(features):
                result = ["features": Dictionary(uniqueKeysWithValues: features.map { ($0.key.rawValue, $0.value) })]
            }
            return Self.same(result, vector["expect"])
        }
    }

    func testBatteryUpdate() throws {
        try each("batteryUpdate") { vector in
            let result: Any = decodeBatteryStatus(plaintext(vector))
                .map { ["level": $0.level, "isCharging": $0.isCharging] as [String: Any] } ?? "reject"
            return Self.same(result, vector["expect"])
        }
    }

    func testClipboardRich() throws {
        try each("clipboardRich") { vector in
            let result: Any = decodeRichClipboard(plaintext(vector)).map { ["text": $0.text, "html": $0.html] } ?? "reject"
            return Self.same(result, vector["expect"])
        }
    }

    func testRichClipboardIsBoundedTogetherAtThirtyTwoKiB() throws {
        func payload(textBytes: Int) throws -> Data {
            let html = "<b>" + String(repeating: "h", count: 16 * 1024 - 7) + "</b>"
            return try JSONSerialization.data(withJSONObject: ["version": 1, "text": String(repeating: "t", count: textBytes), "html": html])
        }
        let content = try XCTUnwrap(decodeRichClipboard(try payload(textBytes: 16 * 1024)))
        XCTAssertEqual(content.text.utf8.count + content.html.utf8.count, maximumClipboardContentBytes)
        XCTAssertNil(decodeRichClipboard(try payload(textBytes: 16 * 1024 + 1)))
    }

    // MARK: helpers

    private func each(_ group: String, file: StaticString = #filePath, line: UInt = #line, _ check: ([String: Any]) throws -> Bool) throws {
        let list = try XCTUnwrap(vectors[group] as? [[String: Any]])
        XCTAssertGreaterThan(list.count, 5)
        let failed = list.filter { ((try? check($0)) ?? false) == false }.compactMap { $0["name"] as? String }
        XCTAssertEqual(failed, [], "\(group) vectors that decode differently from the contract", file: file, line: line)
    }

    private func plaintext(_ vector: [String: Any]) -> Data {
        if let raw = vector["plaintextString"] as? String { return Data(raw.utf8) }
        return (try? JSONSerialization.data(withJSONObject: vector["plaintext"] as Any, options: [.fragmentsAllowed])) ?? Data()
    }

    private static func same(_ result: Any, _ expected: Any?) -> Bool {
        guard let expected else { return false }
        return NSArray(object: result).isEqual(to: [expected])
    }

    private static func fields(_ message: PairingMessage) -> [String: Any] {
        var fields: [String: Any] = ["kind": message.kind, "sessionId": message.sessionId]
        let optional: [(String, String?)] = [
            ("deviceId", message.deviceId), ("deviceName", message.deviceName), ("publicKey", message.publicKey),
            ("identityKey", message.identityKey), ("proof", message.proof), ("signature", message.signature),
            ("messageId", message.messageId), ("nonce", message.nonce), ("ciphertext", message.ciphertext),
            ("transferId", message.transferId),
        ]
        for (key, value) in optional { if let value { fields[key] = value } }
        if let sequence = message.sequence { fields["sequence"] = NSNumber(value: sequence) }
        return fields
    }
}
