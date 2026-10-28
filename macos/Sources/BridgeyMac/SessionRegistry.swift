import Foundation

/// The feature state assumed for a peer until its first `features.update`.
func defaultRemoteFeatureState() -> [BridgeyFeature: Bool] {
    Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0, featureEnabledByLegacyPeer($0)) })
}

/// What this Mac knows about one connected device. Every device has its own copy,
/// so one device's features, battery or ringing state never leak into another's.
struct PeerLinkState: Equatable {
    var remoteFeatures = defaultRemoteFeatureState()
    var featureStateReceived = false
    var battery: RemoteBatteryStatus?
    /// The device reported that it is ringing (Find device).
    var ringing = false
}

/// An authenticated device as the UI sees it.
struct ConnectedDevice: Identifiable, Equatable {
    let id: String
    let name: String
    let link: PeerLinkState
}

/// Sessions keyed by device: pending sessions (handshake or pairing code) on their own,
/// and at most one authenticated session per device ID. See "Multiple devices" in
/// docs/architecture.md for the duplicate rule, which both platforms share.
final class SessionRegistry<S: AnyObject> {
    enum Admission {
        /// The session is now the device's session; `replaced`, if any, must be closed.
        case accepted(replaced: S?)
        /// Another live session for the device wins; this one must be closed.
        case rejected
    }

    private final class Entry {
        let session: S
        let initiatedLocally: Bool
        var deviceID: String?
        var peerName = ""
        var link = PeerLinkState()

        init(session: S, initiatedLocally: Bool) {
            self.session = session
            self.initiatedLocally = initiatedLocally
        }
    }

    private let localDeviceID: String
    private var pending: [Entry] = []
    /// Authenticated sessions in connection order.
    private var active: [Entry] = []

    init(localDeviceID: String) {
        self.localDeviceID = localDeviceID
    }

    func addPending(_ session: S, initiatedLocally: Bool) {
        guard !contains(session) else { return }
        pending.append(Entry(session: session, initiatedLocally: initiatedLocally))
    }

    var pendingSessions: [S] { pending.map(\.session) }

    func isPending(_ session: S) -> Bool { pending.contains { $0.session === session } }

    func isActive(_ session: S) -> Bool { active.contains { $0.session === session } }

    func contains(_ session: S) -> Bool { isPending(session) || isActive(session) }

    /// Moves a pending session to the authenticated sessions under `deviceID`.
    func authenticate(_ session: S, deviceID: String, peerName: String) -> Admission {
        guard let index = pending.firstIndex(where: { $0.session === session }) else { return .rejected }
        let entry = pending[index]
        entry.deviceID = deviceID
        entry.peerName = peerName
        guard let existingIndex = active.firstIndex(where: { $0.deviceID == deviceID }) else {
            pending.remove(at: index)
            active.append(entry)
            return .accepted(replaced: nil)
        }
        let existing = active[existingIndex]
        guard prefers(entry, over: existing) else {
            entry.deviceID = nil
            return .rejected
        }
        pending.remove(at: index)
        entry.link = existing.link
        active.remove(at: existingIndex)
        active.append(entry)
        return .accepted(replaced: existing.session)
    }

    /// Keeps the session dialled by the side with the lower device ID; if both were
    /// dialled by the same side, the newer one wins.
    private func prefers(_ candidate: Entry, over existing: Entry) -> Bool {
        let candidateLower = dialledByLowerID(candidate)
        let existingLower = dialledByLowerID(existing)
        return candidateLower != existingLower ? candidateLower : true
    }

    private func dialledByLowerID(_ entry: Entry) -> Bool {
        guard let remote = entry.deviceID else { return false }
        return entry.initiatedLocally ? localDeviceID < remote : remote < localDeviceID
    }

    /// Forgets the session. Returns the device ID when it was that device's authenticated session.
    @discardableResult
    func remove(_ session: S) -> String? {
        if let index = pending.firstIndex(where: { $0.session === session }) {
            pending.remove(at: index)
            return nil
        }
        guard let index = active.firstIndex(where: { $0.session === session }) else { return nil }
        return active.remove(at: index).deviceID
    }

    /// Forgets every session and returns them, pending ones first.
    @discardableResult
    func removeAll() -> [S] {
        let all = pending.map(\.session) + active.map(\.session)
        pending.removeAll()
        active.removeAll()
        return all
    }

    func session(for deviceID: String) -> S? { active.first { $0.deviceID == deviceID }?.session }

    func deviceID(of session: S) -> String? { active.first { $0.session === session }?.deviceID }

    /// Connected device IDs in connection order.
    var connectedIDs: [String] { active.compactMap(\.deviceID) }

    var connections: [ConnectedDevice] {
        active.compactMap { entry in
            entry.deviceID.map { ConnectedDevice(id: $0, name: entry.peerName, link: entry.link) }
        }
    }

    func link(for deviceID: String) -> PeerLinkState? { active.first { $0.deviceID == deviceID }?.link }

    @discardableResult
    func update(_ deviceID: String, _ transform: (inout PeerLinkState) -> Void) -> Bool {
        guard let entry = active.first(where: { $0.deviceID == deviceID }) else { return false }
        transform(&entry.link)
        return true
    }

    /// The device user actions go to: `selected` while it is connected, otherwise the
    /// most recently connected device.
    func target(selected: String?) -> String? {
        if let selected, active.contains(where: { $0.deviceID == selected }) { return selected }
        return active.last?.deviceID
    }

    /// Connected devices for a broadcast, in connection order.
    func broadcastTargets(_ include: (String, PeerLinkState) -> Bool) -> [(deviceID: String, session: S)] {
        active.compactMap { entry in
            guard let id = entry.deviceID, include(id, entry.link) else { return nil }
            return (id, entry.session)
        }
    }
}
