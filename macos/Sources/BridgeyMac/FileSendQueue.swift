import Foundation

/// Orders outgoing files so that only one is offered at a time. The next file
/// starts after the current one completes, fails, or is cancelled. Each file
/// still uses its own `files.offer`, so the wire protocol is unchanged.
struct FileSendQueue<Item> {
    private(set) var currentID: String?
    private var pending: [(id: String, item: Item)] = []

    var pendingIDs: [String] { pending.map(\.id) }
    var isEmpty: Bool { currentID == nil && pending.isEmpty }

    mutating func enqueue(id: String, item: Item) {
        guard id != currentID, !pending.contains(where: { $0.id == id }) else { return }
        pending.append((id, item))
    }

    /// Returns the next file to start, or nil while a file is in progress or nothing is waiting.
    mutating func startNext() -> (id: String, item: Item)? {
        guard currentID == nil, !pending.isEmpty else { return nil }
        let next = pending.removeFirst()
        currentID = next.id
        return next
    }

    /// Marks the current file as done. Returns false when the ID is not the current file.
    @discardableResult
    mutating func finish(id: String) -> Bool {
        guard currentID == id else { return false }
        currentID = nil
        return true
    }

    /// Removes a file that has not started yet.
    @discardableResult
    mutating func removePending(id: String) -> Bool {
        guard let index = pending.firstIndex(where: { $0.id == id }) else { return false }
        pending.remove(at: index)
        return true
    }

    /// Removes the files that have not started yet and match, and returns their IDs in queue order.
    @discardableResult
    mutating func removePending(where matches: (String) -> Bool) -> [String] {
        let removed = pending.filter { matches($0.id) }.map(\.id)
        pending.removeAll { matches($0.id) }
        return removed
    }

    /// Removes every file that has not started yet and returns their IDs in queue order.
    mutating func removeAllPending() -> [String] {
        let ids = pendingIDs
        pending.removeAll()
        return ids
    }

    /// Forgets the current and waiting files and returns their IDs, current file first.
    mutating func removeAll() -> [String] {
        let ids = (currentID.map { [$0] } ?? []) + pendingIDs
        currentID = nil
        pending.removeAll()
        return ids
    }
}

/// Which device each transfer belongs to. Messages about a transfer are accepted only from
/// its own device, and a device that disconnects ends only its own transfers.
struct TransferOwners {
    private var devices: [String: String] = [:]

    subscript(transferID: String) -> String? { devices[transferID] }

    mutating func assign(_ transferID: String, to deviceID: String) { devices[transferID] = deviceID }

    mutating func remove(_ transferID: String) { devices.removeValue(forKey: transferID) }

    func owns(_ deviceID: String, _ transferID: String) -> Bool { devices[transferID] == deviceID }

    func transfers(of deviceID: String) -> Set<String> {
        Set(devices.compactMap { $0.value == deviceID ? $0.key : nil })
    }

    /// Forgets transfers that are no longer queued, running or shown in the history.
    mutating func retain(_ live: Set<String>) { devices = devices.filter { live.contains($0.key) } }
}
