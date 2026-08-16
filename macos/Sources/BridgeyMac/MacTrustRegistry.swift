import Foundation
import Security

struct MacTrustedDevice: Codable, Equatable {
    let id: String
    let name: String
    let identityKey: String
}

final class MacTrustRegistry {
    private let service: String
    private let account: String
    private var devicesByID: [String: MacTrustedDevice]

    init(
        service: String = "dev.bridgey.mac.trust",
        account: String = "trusted-devices-v1",
        migrating defaultsStores: [UserDefaults] = [.standard]
    ) {
        self.service = service
        self.account = account
        devicesByID = Self.loadFromKeychain(service: service, account: account)
        defaultsStores.forEach(migrateDefaults)
    }

    var deviceIDs: Set<String> { Set(devicesByID.keys) }

    var devices: [MacTrustedDevice] { Array(devicesByID.values) }

    func identityKey(for deviceID: String) -> String? {
        devicesByID[deviceID]?.identityKey
    }

    @discardableResult
    func remember(deviceID: String, name: String, identityKey: String) -> Bool {
        guard UUID(uuidString: deviceID) != nil,
              !name.isEmpty,
              Data(base64Encoded: identityKey) != nil else { return false }
        let previous = devicesByID[deviceID]
        devicesByID[deviceID] = MacTrustedDevice(id: deviceID, name: name, identityKey: identityKey)
        guard save() else {
            devicesByID[deviceID] = previous
            return false
        }
        return true
    }

    func remove(deviceID: String) {
        let previous = devicesByID.removeValue(forKey: deviceID)
        if !save(), let previous { devicesByID[deviceID] = previous }
    }

    private func migrateDefaults(_ defaults: UserDefaults) {
        let ids = defaults.stringArray(forKey: "trustedDeviceIDs") ?? []
        var importedKeys: [String] = []
        var changed = false
        for id in ids where UUID(uuidString: id) != nil {
            let identityKeyName = "trusted.\(id).identityKey"
            let nameKey = "trusted.\(id).name"
            guard let identityKey = defaults.string(forKey: identityKeyName),
                  Data(base64Encoded: identityKey) != nil else { continue }
            let name = defaults.string(forKey: nameKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
            devicesByID[id] = MacTrustedDevice(
                id: id,
                name: name?.isEmpty == false ? name! : "Unknown device",
                identityKey: identityKey
            )
            importedKeys.append(contentsOf: [identityKeyName, nameKey])
            changed = true
        }
        guard changed, save() else { return }
        importedKeys.forEach(defaults.removeObject(forKey:))
        defaults.removeObject(forKey: "trustedDeviceIDs")
    }

    private static func loadFromKeychain(service: String, account: String) -> [String: MacTrustedDevice] {
        var query = baseQuery(service: service, account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let decoded = try? JSONDecoder().decode([String: MacTrustedDevice].self, from: data) else {
            return [:]
        }
        return decoded.filter { id, device in
            id == device.id && UUID(uuidString: id) != nil && Data(base64Encoded: device.identityKey) != nil
        }
    }

    private func save() -> Bool {
        guard let data = try? JSONEncoder().encode(devicesByID) else { return false }
        let status = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var item = baseQuery
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        item[kSecValueData as String] = data
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    func deleteStorageForTesting() {
        SecItemDelete(baseQuery as CFDictionary)
        devicesByID.removeAll()
    }

    private var baseQuery: [String: Any] {
        Self.baseQuery(service: service, account: account)
    }

    private static func baseQuery(service: String, account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
