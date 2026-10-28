import Foundation

/// Settings, Reset Bridgey on this Mac. The UserDefaults keys below hold settings, keyboard shortcuts,
/// media settings and the trust records of versions before 0.4.1 (docs/upgrade-policy.md). `deviceID` and
/// `legacyPreferencesMigrated` are kept, as are the Keychain identity, received files and launch at login.
enum LocalDataReset {
    static let removedKeyPrefixes = ["settings.", "shortcuts.", "media.", "trusted."]
    static let removedKeys: Set<String> = ["trustedDeviceIDs"]

    static func storedKeys(in defaults: UserDefaults) -> [String] {
        defaults.dictionaryRepresentation().keys
            .filter { key in removedKeys.contains(key) || removedKeyPrefixes.contains { key.hasPrefix($0) } }
            .sorted()
    }

    static func removeStoredKeys(from defaults: UserDefaults) {
        storedKeys(in: defaults).forEach(defaults.removeObject(forKey:))
    }
}
