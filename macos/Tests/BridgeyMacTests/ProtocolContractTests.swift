import Foundation
import XCTest
@testable import BridgeyMac

/// protocol/messages.json lists every message kind of Bridgey Protocol v1 (docs/protocol.md, Version 1
/// compatibility contract). These tests fail when the macOS sources send or dispatch a kind that is not
/// listed, or when a listed kind is not used here. Android runs the same source scan, plus checks of the
/// file's own consistency, in ProtocolContractTest.kt.
final class ProtocolContractTests: XCTestCase {
    private static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .appendingPathComponent("../../..")
        .standardizedFileURL

    private var contract: [String: Any] = [:]
    private var kinds: [String] = []

    override func setUpWithError() throws {
        let data = try Data(contentsOf: Self.repoRoot.appendingPathComponent("protocol/messages.json"))
        contract = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let messages = try XCTUnwrap(contract["messages"] as? [[String: Any]])
        kinds = try messages.map { try XCTUnwrap($0["kind"] as? String) }
    }

    func testEveryKindTheSourcesUseIsListed() throws {
        let found = try Self.scanKinds(in: Self.repoRoot.appendingPathComponent("macos/Sources"))
        XCTAssertGreaterThanOrEqual(found.count, 30, "the scan found almost nothing; did the sources move?")
        let listed = Set(kinds)
        let problems = found.keys.sorted().filter { !listed.contains($0) }
            .map { "\($0) (in \(found[$0]!)) is not in protocol/messages.json" }
            + kinds.filter { found[$0] == nil }
            .map { "\($0) is listed in protocol/messages.json but the macOS sources never use it" }
        XCTAssertEqual(problems, [], "Describe new messages in docs/protocol.md and protocol/messages.json")
    }

    func testFeatureKeysMatchTheSettingsModel() throws {
        let features = try XCTUnwrap(contract["features"] as? [[String: Any]])
        XCTAssertEqual(features.compactMap { $0["key"] as? String }, BridgeyFeature.allCases.map(\.rawValue))
        for entry in features {
            let key = try XCTUnwrap(entry["key"] as? String)
            let feature = try XCTUnwrap(BridgeyFeature(rawValue: key))
            XCTAssertEqual(entry["requiredInMap"] as? Bool, featureEnabledByLegacyPeer(feature), "requiredInMap for \(feature.rawValue)")
        }
    }

    func testTransportLimitsMatchTheCode() throws {
        let transport = try XCTUnwrap(contract["transport"] as? [String: Any])
        XCTAssertEqual(transport["maxFrameBytes"] as? Int, maximumProtocolFrameBytes)
        XCTAssertEqual(transport["maxJsonDepth"] as? Int, maximumProtocolJSONDepth)
    }

    func testEveryKindIsDescribedInTheProtocolDocument() throws {
        let document = try String(contentsOf: Self.repoRoot.appendingPathComponent("docs/protocol.md"), encoding: .utf8)
        XCTAssertEqual(kinds.filter { !document.contains("`\($0)`") }, [])
    }

    func testScanFindsEveryStyleTheSourcesUse() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("contract-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try """
        current.send(PairingMessage(kind: "b.one", sessionId: current.id))
                    case "b.two", "b.three":
        _ = sendFindCommand(kind: "b.four")
        if message.kind == "b.five" { }
        _ = self?.sendQuickPayload(kind: "b.six", payload: $0)
        defaults.set(true, forKey: "settings.onboarding.completed")
        L10n.text("welcome.body", fallback: "x")
        Image(systemName: "battery.100percent")
        """.write(to: directory.appendingPathComponent("A.swift"), atomically: true, encoding: .utf8)
        XCTAssertEqual(Set(try Self.scanKinds(in: directory).keys), ["b.one", "b.two", "b.three", "b.four", "b.five", "b.six"])
    }

    private static let kindPattern = #"[a-z][a-z0-9]*(?:\.[a-z0-9_]+)+"#

    /// Kinds the Swift sources under `root` send or dispatch, with the first file that uses each.
    static func scanKinds(in root: URL) throws -> [String: String] {
        let k = kindPattern
        let patterns = try [#"kind:\s*"(\#(k))""#, #"kind\s*==\s*"(\#(k))""#, #"\bsend\w*\((?:[^()"]*,\s*)?"(\#(k))""#]
            .map { try NSRegularExpression(pattern: $0) }
        let caseLine = try NSRegularExpression(pattern: #"^\s*case\s+((?:"\#(k)"\s*,\s*)*"\#(k)")\s*:"#)
        let literal = try NSRegularExpression(pattern: #""(\#(k))""#)
        func groups(_ regex: NSRegularExpression, _ text: String) -> [String] {
            regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
                .compactMap { Range($0.range(at: 1), in: text).map { String(text[$0]) } }
        }
        let files = (FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?.allObjects as? [URL] ?? [])
            .filter { $0.pathExtension == "swift" }
            .sorted { $0.path < $1.path }
        var found: [String: String] = [:]
        for file in files {
            for line in try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n") {
                var lineKinds = patterns.flatMap { groups($0, line) }
                for list in groups(caseLine, line) { lineKinds += groups(literal, list) }
                for kind in lineKinds where found[kind] == nil { found[kind] = file.lastPathComponent }
            }
        }
        return found
    }
}
