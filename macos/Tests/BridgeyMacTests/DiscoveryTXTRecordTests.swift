import Foundation
import XCTest
@testable import BridgeyMac

final class DiscoveryTXTRecordTests: XCTestCase {
    func testParsesBoundedHints() {
        let peer = DiscoveryTXTRecord.parse(serviceName: "Bridgey-Pixel", attributes: [
            "id": Data("550e8400-e29b-41d4-a716-446655440000".utf8),
            "name": Data("Pixel 10 Pro".utf8),
            "platform": Data("android".utf8),
            "version": Data("1".utf8),
        ])
        XCTAssertEqual(peer.deviceNameHint, "Pixel 10 Pro")
        XCTAssertEqual(peer.protocolVersionHint, 1)
    }

    func testRejectsOversizedAndInvalidHints() {
        let peer = DiscoveryTXTRecord.parse(serviceName: "Safe fallback", attributes: [
            "id": Data("not-a-uuid".utf8),
            "name": Data(String(repeating: "x", count: 65).utf8),
            "platform": Data("MAC OS!".utf8),
            "version": Data("0".utf8),
        ])
        XCTAssertEqual(peer.deviceNameHint, "Safe fallback")
        XCTAssertNil(peer.deviceIDHint)
        XCTAssertNil(peer.platformHint)
        XCTAssertNil(peer.protocolVersionHint)
    }
}
