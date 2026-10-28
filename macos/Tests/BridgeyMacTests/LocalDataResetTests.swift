import Foundation
import XCTest
@testable import BridgeyMac

/// Settings, Reset Bridgey on this Mac, applied to the UserDefaults domains of every released version
/// (testdata/migrations/macos). The Keychain is not touched here.
final class LocalDataResetTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../testdata/migrations/macos")
        .standardizedFileURL

    func testResetKeepsOnlyTheDeviceIdentifier() throws {
        for version in ["v0.2.0", "v0.4.0", "v0.5.0", "v0.6.0-rc.2"] {
            let (defaults, name) = try fixture(version)
            XCTAssertFalse(LocalDataReset.storedKeys(in: defaults).isEmpty, version)
            LocalDataReset.removeStoredKeys(from: defaults)
            XCTAssertEqual(Set((defaults.persistentDomain(forName: name) ?? [:]).keys), ["deviceID", "legacyPreferencesMigrated"], version)
        }
    }

    @MainActor func testSettingsResetRestoresDefaults() throws {
        let (defaults, name) = try fixture("v0.6.0-rc.2")
        let settings = BridgeySettings(defaults: defaults)
        settings.resetToDefaults()
        XCTAssertFalse((defaults.persistentDomain(forName: name) ?? [:]).keys.contains { $0.hasPrefix("settings.") })
        XCTAssertEqual(settings.globalFeatures, Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0, $0 != .media) }))
        XCTAssertTrue(settings.deviceFeatures.isEmpty)
        XCTAssertFalse(settings.hasCompletedOnboarding)
        XCTAssertFalse(settings.notificationHistoryEnabled)
        XCTAssertEqual(settings.deviceName, Host.current().localizedName ?? "Mac")
        XCTAssertNotEqual(settings.receiveFolderPath, "/Users/test/Downloads/Bridgey")
        // A new BridgeySettings reads the same defaults back.
        XCTAssertEqual(BridgeySettings(defaults: defaults).globalFeatures, settings.globalFeatures)
    }

    @MainActor func testShortcutResetRestoresDefaults() throws {
        let (defaults, name) = try fixture("v0.6.0-rc.2")
        let shortcuts = ShortcutSettings(defaults: defaults)
        shortcuts.reset()
        XCTAssertEqual(shortcuts.keys, [.clipboard: "C", .call: "P", .link: "Off", .ping: "Off"])
        XCTAssertFalse(shortcuts.useShift)
        XCTAssertFalse((defaults.persistentDomain(forName: name) ?? [:]).keys.contains { $0.hasPrefix("shortcuts.") })
    }

    /// A fresh UserDefaults suite holding the fixture domain of `version`, and its name.
    private func fixture(_ version: String) throws -> (UserDefaults, String) {
        let name = "dev.bridgey.mac.reset-tests.\(UUID().uuidString)"
        addTeardownBlock { UserDefaults.standard.removePersistentDomain(forName: name) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        let plist = try Data(contentsOf: Self.root.appendingPathComponent("\(version)/defaults.plist"))
        let values = try XCTUnwrap(PropertyListSerialization.propertyList(from: plist, format: nil) as? [String: Any])
        for (key, value) in values { defaults.set(value, forKey: key) }
        return (defaults, name)
    }
}
