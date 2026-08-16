import Foundation
import XCTest
@testable import BridgeyMac

final class MacTrustRegistryTests: XCTestCase {
    func testMigratesTrustRecordsFromUserDefaultsToKeychain() throws {
        let namespace = UUID().uuidString
        let suiteName = "dev.bridgey.tests.\(namespace)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let deviceID = UUID().uuidString.lowercased()
        let identityKey = Data("test-public-key".utf8).base64EncodedString()
        defaults.set([deviceID], forKey: "trustedDeviceIDs")
        defaults.set("Test phone", forKey: "trusted.\(deviceID).name")
        defaults.set(identityKey, forKey: "trusted.\(deviceID).identityKey")

        let service = "dev.bridgey.tests.trust.\(namespace)"
        let registry = MacTrustRegistry(service: service, migrating: [defaults])
        defer { registry.deleteStorageForTesting() }

        XCTAssertEqual(registry.deviceIDs, [deviceID])
        XCTAssertEqual(registry.identityKey(for: deviceID), identityKey)
        XCTAssertEqual(registry.devices.first?.name, "Test phone")
        XCTAssertNil(defaults.array(forKey: "trustedDeviceIDs"))
        XCTAssertNil(defaults.string(forKey: "trusted.\(deviceID).identityKey"))

        let reloaded = MacTrustRegistry(service: service, migrating: [])
        XCTAssertEqual(reloaded.deviceIDs, [deviceID])
        XCTAssertEqual(reloaded.identityKey(for: deviceID), identityKey)
    }
}
