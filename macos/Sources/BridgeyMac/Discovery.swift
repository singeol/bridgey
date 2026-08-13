import Foundation

enum LegacyPreferences {
    static func migrateIfNeeded() {
        let current = UserDefaults.standard
        guard !current.bool(forKey: "legacyPreferencesMigrated"),
              let legacy = UserDefaults(suiteName: "BridgeyMac") else { return }
        if let deviceID = legacy.string(forKey: "deviceID"), UUID(uuidString: deviceID) != nil {
            current.set(deviceID, forKey: "deviceID")
        }
        let trustedIDs = legacy.stringArray(forKey: "trustedDeviceIDs") ?? []
        if !trustedIDs.isEmpty {
            current.set(trustedIDs, forKey: "trustedDeviceIDs")
            for id in trustedIDs {
                for suffix in ["identityKey", "name"] {
                    let key = "trusted.\(id).\(suffix)"
                    if let value = legacy.string(forKey: key) { current.set(value, forKey: key) }
                }
            }
        }
        current.set(true, forKey: "legacyPreferencesMigrated")
    }
}

struct DiscoveredPeer: Identifiable, Equatable {
    let serviceName: String
    let deviceIDHint: String?
    let deviceNameHint: String
    let platformHint: String?
    let protocolVersionHint: Int?
    var host: String?
    var port: Int?

    var id: String { serviceName }
}

enum DiscoveryTXTRecord {
    static func parse(serviceName: String, attributes: [String: Data]) -> DiscoveredPeer {
        let id = decode(attributes["id"], limit: 36).flatMap {
            UUID(uuidString: $0) == nil ? nil : $0.lowercased()
        }
        let decodedName = decode(attributes["name"], limit: 64)
        let name = decodedName?.isEmpty == false ? decodedName! : String(serviceName.prefix(64))
        let platform = decode(attributes["platform"], limit: 16).flatMap {
            $0.range(of: "^[a-z][a-z0-9-]{0,15}$", options: .regularExpression) == nil ? nil : $0
        }
        let version = decode(attributes["version"], limit: 8)
            .flatMap(Int.init)
            .flatMap { $0 > 0 ? $0 : nil }
        return DiscoveredPeer(
            serviceName: serviceName,
            deviceIDHint: id,
            deviceNameHint: name,
            platformHint: platform,
            protocolVersionHint: version,
            host: nil,
            port: nil
        )
    }

    private static func decode(_ data: Data?, limit: Int) -> String? {
        guard let data, !data.isEmpty, data.count <= limit else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

@MainActor
final class BonjourDiscovery: NSObject, ObservableObject {
    static let serviceType = "_bridgey._tcp."
    static let defaultPort: Int32 = 42_458

    @Published private(set) var peers: [DiscoveredPeer] = []

    private let browser = NetServiceBrowser()
    private var publishedService: NetService?
    private var services: [String: NetService] = [:]
    private var isRunning = false
    private let deviceID: String
    private let deviceName: String
    var localDeviceID: String { deviceID }
    var localDeviceName: String { deviceName }
    private lazy var ownServiceName = "Bridgey-\(deviceID.prefix(8))"

    override init() {
        let defaults = UserDefaults.standard
        if let stored = defaults.string(forKey: "deviceID"), UUID(uuidString: stored) != nil {
            deviceID = stored
        } else {
            deviceID = UUID().uuidString.lowercased()
            defaults.set(deviceID, forKey: "deviceID")
        }
        deviceName = Host.current().localizedName ?? "Mac"
        super.init()
        browser.delegate = self
        start()
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        publish()
        browser.searchForServices(ofType: Self.serviceType, inDomain: "local.")
        BridgeyLog.discovery("browsing started")
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        browser.stop()
        publishedService?.stop()
        publishedService = nil
        services.removeAll()
        peers.removeAll()
        BridgeyLog.discovery("browsing stopped")
    }

    private func publish() {
        let service = NetService(
            domain: "local.",
            type: Self.serviceType,
            name: ownServiceName,
            port: Self.defaultPort
        )
        service.delegate = self
        service.setTXTRecord(NetService.data(fromTXTRecord: [
            "id": Data(deviceID.utf8),
            "name": Data(String(deviceName.prefix(64)).utf8),
            "version": Data("1".utf8),
            "platform": Data("macos".utf8),
        ]))
        service.publish()
        publishedService = service
    }

    private func accept(_ service: NetService) {
        guard service.name != ownServiceName else { return }
        let attributes = service.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        var peer = DiscoveryTXTRecord.parse(serviceName: service.name, attributes: attributes)
        peer.host = service.hostName
        peer.port = service.port > 0 ? service.port : nil
        let otherPeers = peers.filter { $0.id != peer.id }
        peers = (otherPeers + [peer]).sorted { $0.deviceNameHint.localizedCaseInsensitiveCompare($1.deviceNameHint) == .orderedAscending }
        BridgeyLog.discovery("peer discovered service=\(service.name)")
    }
}

extension BonjourDiscovery: NetServiceBrowserDelegate {
    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        Task { @MainActor in
            guard service.name != ownServiceName else { return }
            services[service.name] = service
            service.delegate = self
            service.resolve(withTimeout: 5)
        }
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        Task { @MainActor in
            services.removeValue(forKey: service.name)
            peers.removeAll { $0.serviceName == service.name }
            BridgeyLog.discovery("peer lost service=\(service.name)")
        }
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        BridgeyLog.discovery("browse failed")
    }
}

extension BonjourDiscovery: NetServiceDelegate {
    nonisolated func netServiceDidResolveAddress(_ sender: NetService) {
        Task { @MainActor in accept(sender) }
    }

    nonisolated func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        BridgeyLog.discovery("resolve failed service=\(sender.name)")
    }

    nonisolated func netServiceDidPublish(_ sender: NetService) {
        BridgeyLog.discovery("service published name=\(sender.name)")
    }
}

enum BridgeyLog {
    static func discovery(_ message: String) {
        // Discovery service names are untrusted hints; no payload or secret is logged.
        NSLog("DISCOVERY %@", message)
    }
}
