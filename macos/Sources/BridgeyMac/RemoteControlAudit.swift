import Foundation

/// One remote control session, or one refused start. Counts only: never typed text, key names,
/// pointer positions or what was on screen.
struct RemoteControlAuditEntry: Codable, Identifiable, Equatable {
    let id: String
    let deviceID: String
    let deviceName: String
    /// `touchpad` or `presentation`; nil when the start was invalid.
    let mode: String?
    /// False for a start the Mac refused (off, not allowed, busy, no Accessibility).
    let accepted: Bool
    let startedAt: Date
    let endedAt: Date
    /// A `RemoteInputStopReason` raw value.
    let endReason: String
    /// Messages per `RemoteInputEventType`.
    let counts: [String: Int]
    /// Over the rate limit, dropped.
    let dropped: Int
    /// Malformed, replayed or out of sequence, dropped.
    let rejected: Int

    var duration: TimeInterval { max(0, endedAt.timeIntervalSince(startedAt)) }
    var reason: RemoteInputStopReason? { RemoteInputStopReason(rawValue: endReason) }
}

/// The audit file: `remote-control-audit.json` in Application Support, owner-only, 30 days.
final class RemoteControlAuditStore {
    static let fileName = "remote-control-audit.json"
    static let maximumAge: TimeInterval = 30 * 24 * 60 * 60
    static let maximumEntries = 500

    let fileURL: URL

    init(fileURL: URL = RemoteControlAuditStore.defaultFileURL()) {
        self.fileURL = fileURL
    }

    func load(now: Date) -> [RemoteControlAuditEntry] {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? Self.decoder.decode([RemoteControlAuditEntry].self, from: data) else { return [] }
        let kept = Self.pruned(decoded, now: now)
        if kept != decoded { save(kept) }
        return kept
    }

    func save(_ entries: [RemoteControlAuditEntry]) {
        guard let data = try? Self.encoder.encode(entries) else { return }
        let manager = FileManager.default
        let directory = fileURL.deletingLastPathComponent()
        try? manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let temporary = directory.appendingPathComponent(".\(fileURL.lastPathComponent).\(UUID().uuidString).tmp")
        guard manager.createFile(atPath: temporary.path, contents: data, attributes: [.posixPermissions: 0o600]) else { return }
        if rename(temporary.path, fileURL.path) != 0 {
            try? manager.removeItem(at: temporary)
            return
        }
        try? manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
    }

    func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }

    /// Newest first, nothing older than 30 days, at most 500 entries.
    static func pruned(_ entries: [RemoteControlAuditEntry], now: Date) -> [RemoteControlAuditEntry] {
        let oldest = now.addingTimeInterval(-maximumAge)
        return Array(entries.filter { $0.endedAt >= oldest }.sorted { $0.endedAt > $1.endedAt }.prefix(maximumEntries))
    }

    static func defaultFileURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support", isDirectory: true)
        return base.appendingPathComponent("Bridgey/\(fileName)")
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return decoder
    }()
}

/// The audit log in memory, shown in Settings under Remote control and cleared there.
@MainActor
final class RemoteControlAuditLog: ObservableObject {
    @Published private(set) var entries: [RemoteControlAuditEntry]

    private let store: RemoteControlAuditStore?
    private let clock: () -> Date

    init(store: RemoteControlAuditStore? = RemoteControlAuditStore(), clock: @escaping () -> Date = Date.init) {
        self.store = store
        self.clock = clock
        entries = store?.load(now: clock()) ?? []
    }

    func record(_ entry: RemoteControlAuditEntry) {
        NSLog("PLUGIN remote input audit accepted=%@ reason=%@", entry.accepted ? "true" : "false", entry.endReason)
        entries = RemoteControlAuditStore.pruned([entry] + entries.filter { $0.id != entry.id }, now: clock())
        store?.save(entries)
    }

    func clear() {
        entries = []
        store?.clear()
    }

    /// Drops entries past 30 days; called when Settings opens.
    func prune() {
        let kept = RemoteControlAuditStore.pruned(entries, now: clock())
        guard kept != entries else { return }
        entries = kept
        store?.save(kept)
    }
}
