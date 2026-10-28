import Foundation
import XCTest
@testable import BridgeyMac

final class FinderMetadataTests: XCTestCase {
    func testWhereFromRoundTripsAsAStringArray() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("BridgeyFinderMetadata-\(UUID().uuidString).txt")
        try Data("x".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }

        XCTAssertNil(FinderMetadata.whereFroms(file))
        XCTAssertTrue(FinderMetadata.setWhereFrom(file, FinderMetadata.whereFrom(deviceName: "Pixel 9")))
        XCTAssertEqual(FinderMetadata.whereFroms(file), ["Bridgey: Pixel 9"])
    }

    func testDeviceNameIsBounded() {
        XCTAssertEqual(FinderMetadata.whereFrom(deviceName: String(repeating: "n", count: 200)).count, "Bridgey: ".count + 64)
    }

    func testDownloadsCheckUsesPathBoundaries() {
        let downloads = URL(fileURLWithPath: "/tmp/BridgeyFinderTests/Downloads")
        XCTAssertTrue(FinderMetadata.isInDownloads(downloads.appendingPathComponent("Bridgey/a.jpg"), downloads: downloads))
        XCTAssertTrue(FinderMetadata.isInDownloads(downloads, downloads: downloads))
        XCTAssertFalse(FinderMetadata.isInDownloads(URL(fileURLWithPath: "/tmp/BridgeyFinderTests/DownloadsOther/a.jpg"), downloads: downloads))
        XCTAssertFalse(FinderMetadata.isInDownloads(downloads, downloads: nil))
    }
}
