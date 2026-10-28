import Foundation
import Network

// Connection health for Settings and the diagnostics report. Private by design: the route is a
// class, never an address; there are no device names or identifiers; disconnect reasons are fixed
// tokens. The round trip uses the existing heartbeat ping and pong, so nothing new goes on the wire.

enum RouteClass: String {
    /// Private LAN, link-local or loopback addresses.
    case local = "local_network"
    /// 100.64.0.0/10, used by carrier-grade NAT and some VPNs.
    case shared = "shared_address_space"
    case other
    case unknown

    var title: String {
        switch self {
        case .local: L10n.text("health.route.local", fallback: "Local network")
        case .shared: L10n.text("health.route.shared", fallback: "Private network (VPN or carrier NAT)")
        case .other: L10n.text("health.route.other", fallback: "Another network")
        case .unknown: L10n.text("health.route.unknown", fallback: "Unknown route")
        }
    }
}

/// Classifies a numeric host without resolving names. Hostnames and anything malformed are unknown.
func classifyRoute(_ rawHost: String?) -> RouteClass {
    guard var host = rawHost?.trimmingCharacters(in: .whitespaces).lowercased(), !host.isEmpty else { return .unknown }
    host = String(host.split(separator: "%", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    host = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
    if let bytes = ipv4Bytes(host) { return classifyIPv4(bytes) }
    guard host.contains(":"), host.allSatisfy({ $0.isHexDigit || $0 == ":" || $0 == "." }) else { return .unknown }
    if host.hasPrefix("::ffff:"), let bytes = ipv4Bytes(String(host.dropFirst(7))) { return classifyIPv4(bytes) }
    if host == "::1" { return .local }
    let firstGroup = host.split(separator: ":", omittingEmptySubsequences: false).first.map(String.init) ?? ""
    guard let first = Int(firstGroup.isEmpty ? "0" : firstGroup, radix: 16) else { return .unknown }
    return first & 0xffc0 == 0xfe80 || first & 0xfe00 == 0xfc00 ? .local : .other
}

private func ipv4Bytes(_ host: String) -> [Int]? {
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4 else { return nil }
    let bytes = parts.compactMap { part -> Int? in
        guard (1...3).contains(part.count), part.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int(part).flatMap { $0 <= 255 ? $0 : nil }
    }
    return bytes.count == 4 ? bytes : nil
}

private func classifyIPv4(_ b: [Int]) -> RouteClass {
    if b[0] == 10 || b[0] == 127 || (b[0] == 172 && (16...31).contains(b[1])) || (b[0] == 192 && b[1] == 168)
        || (b[0] == 169 && b[1] == 254) { return .local }
    if b[0] == 100 && (64...127).contains(b[1]) { return .shared }
    return .other
}

/// The numeric host of a connection's remote endpoint, used only for the route class.
func endpointHost(_ endpoint: NWEndpoint) -> String? {
    guard case let .hostPort(host, _) = endpoint else { return nil }
    switch host {
    case let .ipv4(address): return "\(address)"
    case let .ipv6(address): return "\(address)"
    default: return nil
    }
}

enum DisconnectReason: String {
    case heartbeat = "heartbeat_timeout"
    case connectionLost = "connection_lost"
    case user
    case protocolError = "protocol_error"

    var title: String {
        switch self {
        case .heartbeat: L10n.text("health.drop.heartbeat", fallback: "Stopped responding")
        case .connectionLost: L10n.text("health.drop.connection", fallback: "Connection lost")
        case .user: L10n.text("health.drop.user", fallback: "Disconnected on this Mac")
        case .protocolError: L10n.text("health.drop.protocol", fallback: "Unexpected message")
        }
    }
}

struct ConnectionHealthSnapshot: Equatable {
    var connected = false
    var route = RouteClass.unknown
    /// True when this Mac opened the connection; nil while disconnected.
    var openedHere: Bool?
    var connectedSince: Date?
    var lastRoundTrip: TimeInterval?
    var medianRoundTrip: TimeInterval?
    var roundTripSamples = 0
    var lastDisconnect: DisconnectReason?
    var lastDisconnectAt: Date?
    var attemptsToday = 0

    func diagnostics(formatter: ISO8601DateFormatter) -> [String: Any] {
        var value: [String: Any] = [
            "connected": connected, "route": route.rawValue,
            "roundTripSamples": roundTripSamples, "connectionAttemptsToday": attemptsToday,
        ]
        if let openedHere { value["openedBy"] = openedHere ? "this_device" : "peer" }
        if let connectedSince { value["connectedSince"] = formatter.string(from: connectedSince) }
        if let lastRoundTrip { value["lastRoundTripMs"] = Int((lastRoundTrip * 1000).rounded()) }
        if let medianRoundTrip { value["medianRoundTripMs"] = Int((medianRoundTrip * 1000).rounded()) }
        if let lastDisconnect { value["lastDisconnectReason"] = lastDisconnect.rawValue }
        if let lastDisconnectAt { value["lastDisconnectAt"] = formatter.string(from: lastDisconnectAt) }
        return value
    }
}

/// The lower median, or nil.
func lowerMedian(_ samples: [TimeInterval]) -> TimeInterval? {
    let sorted = samples.sorted()
    return sorted.isEmpty ? nil : sorted[(sorted.count - 1) / 2]
}

/// Health facts for the current or last connection, in memory only.
struct ConnectionHealth {
    static let roundTripSamples = 10
    static let maximumPendingPings = 8

    private(set) var snapshot = ConnectionHealthSnapshot()
    private var pings: [(id: String, at: Date)] = []
    private var roundTrips: [TimeInterval] = []
    private var attemptsDay: DateComponents?
    private var calendar: Calendar

    init(calendar: Calendar = .current) { self.calendar = calendar }

    /// A connection attempt started, by either device.
    mutating func attempt(at now: Date) {
        let day = calendar.dateComponents([.year, .month, .day], from: now)
        if attemptsDay != day { attemptsDay = day; snapshot.attemptsToday = 0 }
        snapshot.attemptsToday += 1
    }

    /// The session is authenticated. `host` only selects the route class and is not kept.
    mutating func connected(host: String?, openedHere: Bool, at now: Date) {
        pings = []
        roundTrips = []
        snapshot.connected = true
        snapshot.route = classifyRoute(host)
        snapshot.openedHere = openedHere
        snapshot.connectedSince = now
        snapshot.lastRoundTrip = nil
        snapshot.medianRoundTrip = nil
        snapshot.roundTripSamples = 0
    }

    mutating func disconnected(_ reason: DisconnectReason, at now: Date) {
        guard snapshot.connected else { return }
        pings = []
        snapshot.connected = false
        snapshot.route = .unknown
        snapshot.openedHere = nil
        snapshot.connectedSince = nil
        snapshot.lastDisconnect = reason
        snapshot.lastDisconnectAt = now
    }

    mutating func heartbeatSent(_ id: String, at now: Date) {
        pings.append((id, now))
        if pings.count > Self.maximumPendingPings { pings.removeFirst(pings.count - Self.maximumPendingPings) }
    }

    /// A pong echoing `id`; one this Mac did not send is ignored. Returns whether it counted.
    @discardableResult
    mutating func heartbeatAnswered(_ id: String?, at now: Date) -> Bool {
        guard let id, let index = pings.firstIndex(where: { $0.id == id }) else { return false }
        roundTrips.append(max(0, now.timeIntervalSince(pings.remove(at: index).at)))
        if roundTrips.count > Self.roundTripSamples { roundTrips.removeFirst(roundTrips.count - Self.roundTripSamples) }
        snapshot.lastRoundTrip = roundTrips.last
        snapshot.medianRoundTrip = lowerMedian(roundTrips)
        snapshot.roundTripSamples = roundTrips.count
        return true
    }

    /// The snapshot with today's attempt count, which resets at local midnight.
    func current(at now: Date) -> ConnectionHealthSnapshot {
        var value = snapshot
        if attemptsDay != calendar.dateComponents([.year, .month, .day], from: now) { value.attemptsToday = 0 }
        return value
    }
}
