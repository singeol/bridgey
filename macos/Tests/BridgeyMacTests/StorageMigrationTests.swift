import Foundation
import XCTest
@testable import BridgeyMac

/// Settings, trust records and notification history written by released versions load with the current
/// code (docs/upgrade-policy.md). Each directory in testdata/migrations/macos holds one release's
/// UserDefaults domain (defaults.plist), the JSON stored in its trust Keychain item (keychain-trust.json),
/// and its notification-history.json. The Keychain itself is not touched here.
final class StorageMigrationTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../testdata/migrations/macos")
        .standardizedFileURL
    private static let phone1 = "3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b9a"
    private static let phone2 = "7a6b5c4d-3e2f-4a1b-9c8d-7e6f5a4b3c2d"
    private static let keyA = "BGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfU="
    private static let keyB = "BHzyexiNA09+ilI4AwS1GsPAiWnid/IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E="
    func testFixturesCoverEveryStoredFormat() throws {
        let versions = try FileManager.default.contentsOfDirectory(atPath: Self.root.path).filter { !$0.hasPrefix(".") }.sorted()
        XCTAssertEqual(versions, ["v0.2.0", "v0.4.0", "v0.5.0", "v0.6.0-rc.2"])
        for version in versions {
            XCTAssertTrue(FileManager.default.fileExists(atPath: Self.root.appendingPathComponent("\(version)/defaults.plist").path), version)
        }
    }

    @MainActor func testSettingsFrom020KeepTheirValues() throws {
        let settings = BridgeySettings(defaults: try defaults("v0.2.0"))
        XCTAssertEqual(settings.deviceName, "Test Mac")
        XCTAssertEqual(settings.globalFeatures[.notifications], false)
        XCTAssertEqual(settings.globalFeatures[.clipboard], true)
        // Features added later have no stored value and use the current defaults.
        XCTAssertEqual(settings.globalFeatures[.calls], true)
        XCTAssertEqual(settings.globalFeatures[.ping], true)
        XCTAssertEqual(settings.globalFeatures[.media], false)
        XCTAssertEqual(settings.deviceFeatures[Self.phone1], [.files: false])
        XCTAssertEqual(settings.receiveFolderPath, "/Users/test/Downloads/Bridgey")
        XCTAssertFalse(settings.hasCompletedOnboarding)
        XCTAssertFalse(settings.notificationHistoryEnabled)
    }

    @MainActor func testSettingsFrom040And050KeepTheirValues() throws {
        let v040 = BridgeySettings(defaults: try defaults("v0.4.0"))
        XCTAssertEqual(v040.globalFeatures[.files], false)
        XCTAssertEqual(v040.deviceFeatures[Self.phone1], [.battery: false])
        XCTAssertEqual(v040.deviceFeatures[Self.phone2], [.clipboard: true])
        XCTAssertTrue(v040.hasCompletedOnboarding)

        let v050 = BridgeySettings(defaults: try defaults("v0.5.0"))
        XCTAssertEqual(v050.globalFeatures[.battery], false)
        XCTAssertEqual(v050.globalFeatures[.calls], true)
        XCTAssertFalse(v050.isEnabled(.calls, for: Self.phone1))
        XCTAssertTrue(v050.notificationHistoryEnabled)
    }

    @MainActor func testSettingsFrom060rc2LoadEveryFeature() throws {
        let settings = BridgeySettings(defaults: try defaults("v0.6.0-rc.2"))
        XCTAssertEqual(settings.globalFeatures, [
            .clipboard: true, .files: true, .notifications: true, .battery: true, .findDevice: true,
            .ping: true, .links: true, .media: true, .calls: false,
        ])
        XCTAssertEqual(settings.deviceFeatures[Self.phone1], [.media: false])
        XCTAssertEqual(settings.deviceFeatures[Self.phone2], [.links: false])
        XCTAssertFalse(settings.notificationHistoryEnabled)
    }

    @MainActor func testABookmarkFromAnotherMachineFallsBackToTheStoredFolder() throws {
        let settings = BridgeySettings(defaults: try defaults("v0.4.0"))
        XCTAssertEqual(settings.receiveDirectoryAccess().url.path, "/Users/test/Downloads/Bridgey")
    }

    @MainActor func testShortcutsAndMediaSettingsFrom060rc2() throws {
        let stored = try defaults("v0.6.0-rc.2")
        let shortcuts = ShortcutSettings(defaults: stored)
        XCTAssertEqual(shortcuts.keys, [.clipboard: "V", .call: "P", .link: "L", .ping: "Off"])
        XCTAssertTrue(shortcuts.useShift)
        let media = MediaController(defaults: stored)
        XCTAssertEqual(media.player, .spotify)
        XCTAssertTrue(media.pauseForCalls)

        let older = try defaults("v0.5.0")
        let olderShortcuts = ShortcutSettings(defaults: older)
        XCTAssertEqual(olderShortcuts.keys, [.clipboard: "C", .call: "P", .link: "Off", .ping: "Off"])
        XCTAssertFalse(olderShortcuts.useShift)
        XCTAssertEqual(MediaController(defaults: older).player, .off)
    }

    @MainActor func testAnUnknownMediaPlayerMeansOff() throws {
        let stored = try defaults("v0.6.0-rc.2")
        stored.set("future-player", forKey: "media.player")
        XCTAssertEqual(MediaController(defaults: stored).player, .off)
    }

    @MainActor func testUnknownStoredKeysAreIgnored() throws {
        let stored = try defaults("v0.6.0-rc.2")
        stored.set(false, forKey: "settings.global.future_feature")
        stored.set(true, forKey: "settings.device.\(Self.phone1).future_feature")
        stored.set(7, forKey: "settings.future")
        let settings = BridgeySettings(defaults: stored)
        XCTAssertEqual(Set(settings.globalFeatures.keys), Set(BridgeyFeature.allCases))
        XCTAssertEqual(settings.deviceFeatures[Self.phone1], [.media: false])
    }

    func testTrustInUserDefaultsBefore041IsFoundForMigration() throws {
        let v020 = MacTrustRegistry.legacyDevices(in: try defaults("v0.2.0"))
        XCTAssertEqual(v020.devices, [Self.phone1: MacTrustedDevice(id: Self.phone1, name: "Test Phone", identityKey: Self.keyA)])
        XCTAssertEqual(Set(v020.keys), ["trusted.\(Self.phone1).identityKey", "trusted.\(Self.phone1).name"])

        // A blank name becomes "Unknown device"; an entry whose ID is not a UUID is skipped.
        let v040 = MacTrustRegistry.legacyDevices(in: try defaults("v0.4.0"))
        XCTAssertEqual(v040.devices, [
            Self.phone1: MacTrustedDevice(id: Self.phone1, name: "Test Phone", identityKey: Self.keyA),
            Self.phone2: MacTrustedDevice(id: Self.phone2, name: "Unknown device", identityKey: Self.keyB),
        ])
        XCTAssertFalse(v040.keys.contains { $0.contains("not-a-uuid") })

        XCTAssertTrue(MacTrustRegistry.legacyDevices(in: try defaults("v0.5.0")).devices.isEmpty)
    }

    func testTrustKeychainItemFrom050And060rc2Decodes() throws {
        let v050 = MacTrustRegistry.decodeStoredDevices(try data("v0.5.0/keychain-trust.json"))
        XCTAssertEqual(v050, [Self.phone1: MacTrustedDevice(id: Self.phone1, name: "Test Phone", identityKey: Self.keyA)])
        let v060 = MacTrustRegistry.decodeStoredDevices(try data("v0.6.0-rc.2/keychain-trust.json"))
        XCTAssertEqual(Set(v060.keys), [Self.phone1, Self.phone2])
        XCTAssertEqual(v060[Self.phone2]?.name, "Work Phone")
        // What the current code writes reads back the same.
        XCTAssertEqual(MacTrustRegistry.decodeStoredDevices(try JSONEncoder().encode(v060)), v060)
    }

    func testTrustKeychainItemDropsInconsistentEntries() {
        let json = """
        {"\(Self.phone1)":{"id":"\(Self.phone2)","name":"Swapped","identityKey":"\(Self.keyA)"},
         "not-a-uuid":{"id":"not-a-uuid","name":"Broken","identityKey":"\(Self.keyA)"},
         "\(Self.phone2)":{"id":"\(Self.phone2)","name":"Work Phone","identityKey":"\(Self.keyB)"}}
        """
        XCTAssertEqual(Array(MacTrustRegistry.decodeStoredDevices(Data(json.utf8)).keys), [Self.phone2])
        XCTAssertTrue(MacTrustRegistry.decodeStoredDevices(Data("not json".utf8)).isEmpty)
    }

    func testNotificationHistoryFrom050LoadsAndPrunesOldItems() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("history-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for version in ["v0.5.0", "v0.6.0-rc.2"] {
            let file = directory.appendingPathComponent("\(version).json")
            try data("\(version)/notification-history.json").write(to: file)
            // 2026-10-21T00:00:00Z: the third 0.5.0 item is older than seven days.
            let items = NotificationHistoryStore(fileURL: file).load(now: Date(timeIntervalSinceReferenceDate: 814_233_600))
            XCTAssertEqual(items.map(\.title), ["Alex", "Invoice"], version)
            XCTAssertEqual(items.first?.applicationName, "Chat")
            XCTAssertEqual(items.first?.receivedAt, Date(timeIntervalSinceReferenceDate: 814_181_400))
        }
    }

    // MARK: helpers

    /// A fresh UserDefaults suite holding the fixture domain of `version`.
    private func defaults(_ version: String) throws -> UserDefaults {
        let name = "dev.bridgey.mac.migration-tests.\(UUID().uuidString)"
        addTeardownBlock { UserDefaults.standard.removePersistentDomain(forName: name) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        let plist = try data("\(version)/defaults.plist")
        let values = try XCTUnwrap(PropertyListSerialization.propertyList(from: plist, format: nil) as? [String: Any])
        for (key, value) in values { defaults.set(value, forKey: key) }
        return defaults
    }

    private func data(_ path: String) throws -> Data {
        try Data(contentsOf: Self.root.appendingPathComponent(path))
    }
}
