import AppKit
import Foundation

struct PhoneFolderRoot: Identifiable, Equatable {
    let id: String
    let name: String
}

struct PhoneFolderEntry: Identifiable, Equatable {
    let name: String
    let isDirectory: Bool
    let size: Int64?
    let modified: Date?
    var id: String { (isDirectory ? "d/" : "f/") + name }
}

private struct BrowseRequestPayload: Encodable {
    var version = 1
    let requestId: String
    var rootId: String?
    var path: String?
    var offset: Int?
}

private struct BrowseRootsResult: Decodable {
    struct Root: Decodable {
        let rootId: String
        let name: String
    }

    let version: Int
    let requestId: String
    let roots: [Root]
    let uploads: Bool
}

private struct BrowseListResult: Decodable {
    struct Entry: Decodable {
        let name: String
        let dir: Bool
        let size: Int64?
        let modified: Int64?
    }

    let version: Int
    let requestId: String
    let rootId: String
    let path: String
    let offset: Int
    let total: Int
    let entries: [Entry]
    let nextOffset: Int?
}

private struct BrowseDownloadResult: Decodable {
    let version: Int
    let requestId: String
    let transferId: String
}

struct BrowseErrorPayload: Decodable {
    let version: Int
    let requestId: String
    let code: String
}

/// Browses the folders an Android user shared with this Mac (docs/protocol.md,
/// "Browsing shared folders"). The coordinator encrypts and sends requests and
/// hands decrypted answers back; this type checks every answer against the
/// request it is waiting for and ignores anything else.
@MainActor
final class PhoneFolderBrowser: ObservableObject {
    static let maxPageEntries = 200
    static let timeout: TimeInterval = 8

    @Published private(set) var roots: [PhoneFolderRoot] = []
    @Published private(set) var uploadsAllowed = false
    @Published private(set) var rootID: String?
    @Published private(set) var path: [String] = []
    @Published private(set) var entries: [PhoneFolderEntry] = []
    @Published private(set) var total = 0
    @Published private(set) var nextOffset: Int?
    @Published private(set) var loading = false
    @Published private(set) var status: String?

    /// Sends an encoded request payload; false when it could not be sent.
    var send: ((String, Data) -> Bool)?
    var available: () -> Bool = { false }
    /// Queues an upload of a local file into a shared folder: file, root ID, path.
    var upload: ((URL, String, String) -> Void)?

    private enum Pending {
        case roots
        case list(rootID: String, path: String, offset: Int)
        case download(name: String)
    }

    private var pending: [String: Pending] = [:]
    private var timeouts: [String: DispatchWorkItem] = [:]
    private var latestListRequest: String?

    var rootName: String? { roots.first { $0.id == rootID }?.name }

    func refreshRoots() {
        guard available() else {
            reset()
            return
        }
        request("browse.roots", BrowseRequestPayload(requestId: newRequestID()), pending: .roots)
    }

    func open(root: PhoneFolderRoot) {
        rootID = root.id
        path = []
        list(offset: 0)
    }

    func open(folder entry: PhoneFolderEntry) {
        guard entry.isDirectory, path.count < FolderRules.maxDepth else { return }
        path.append(entry.name)
        list(offset: 0)
    }

    /// Goes back to the shared folder itself (depth 0) or one of the folders above.
    func goTo(depth: Int) {
        guard depth >= 0, depth <= path.count else { return }
        path = Array(path.prefix(depth))
        list(offset: 0)
    }

    func loadMore() {
        guard let nextOffset else { return }
        list(offset: nextOffset)
    }

    func refresh() {
        if rootID == nil { refreshRoots() } else { list(offset: 0) }
    }

    func download(_ entry: PhoneFolderEntry) {
        guard !entry.isDirectory, let rootID else { return }
        request(
            "browse.download",
            BrowseRequestPayload(requestId: newRequestID(), rootId: rootID, path: (path + [entry.name]).joined(separator: "/")),
            pending: .download(name: entry.name)
        )
    }

    func chooseUpload() {
        guard uploadsAllowed, let rootID else { return }
        let target = path.joined(separator: "/")
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.title = L10n.text("browse.upload.title", fallback: "Add Files to Android Folder")
        panel.prompt = L10n.text("browse.upload.prompt", fallback: "Add")
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        guard panel.runModal() == .OK else { return }
        panel.urls.forEach { upload?($0, rootID, target) }
        status = L10n.text("browse.upload.queued", fallback: "Adding files. Progress appears in File Transfer.")
    }

    func reset() {
        timeouts.values.forEach { $0.cancel() }
        timeouts.removeAll()
        pending.removeAll()
        latestListRequest = nil
        roots = []
        uploadsAllowed = false
        rootID = nil
        path = []
        entries = []
        total = 0
        nextOffset = nil
        loading = false
        status = nil
    }

    /// Takes a decrypted answer. Returns false for answers this browser did not ask for.
    @discardableResult
    func receive(kind: String, payload: Data) -> Bool {
        let decoder = JSONDecoder()
        switch kind {
        case "browse.roots.result":
            guard let result = try? decoder.decode(BrowseRootsResult.self, from: payload), result.version == 1,
                  case .roots? = take(result.requestId) else { return false }
            roots = result.roots.prefix(16)
                .filter { !$0.rootId.isEmpty && $0.rootId.count <= 64 && !$0.name.isEmpty }
                .map { PhoneFolderRoot(id: $0.rootId, name: String($0.name.prefix(128))) }
            uploadsAllowed = result.uploads
            loading = false
            status = roots.isEmpty
                ? L10n.text("browse.noRoots", fallback: "No folders are shared. Add folders under Shared folders in Bridgey on Android.")
                : nil
            if let rootID, !roots.contains(where: { $0.id == rootID }) {
                self.rootID = nil
                path = []
                entries = []
            }
        case "browse.list.result":
            guard let result = try? decoder.decode(BrowseListResult.self, from: payload), result.version == 1,
                  case let .list(rootID, path, offset)? = take(result.requestId),
                  result.requestId == latestListRequest else { return false }
            guard result.rootId == rootID, result.path == path, result.offset == offset,
                  result.entries.count <= Self.maxPageEntries,
                  result.total >= 0, result.nextOffset.map({ $0 > offset }) ?? true,
                  result.entries.allSatisfy({ FolderRules.isValidName($0.name) && !$0.name.hasPrefix(".") }) else {
                loading = false
                status = Self.text(forError: "invalid_request")
                return false
            }
            let page = result.entries.map {
                PhoneFolderEntry(
                    name: $0.name,
                    isDirectory: $0.dir,
                    size: $0.dir ? nil : $0.size.flatMap { $0 >= 0 ? $0 : nil },
                    modified: $0.modified.flatMap { $0 > 0 ? Date(timeIntervalSince1970: Double($0) / 1000) : nil }
                )
            }
            entries = offset == 0 ? page : entries + page
            total = result.total
            nextOffset = result.nextOffset
            loading = false
            status = entries.isEmpty ? L10n.text("browse.empty", fallback: "This folder is empty.") : nil
        case "browse.download.result":
            guard let result = try? decoder.decode(BrowseDownloadResult.self, from: payload), result.version == 1,
                  case let .download(name)? = take(result.requestId) else { return false }
            status = String(format: L10n.text("browse.downloading", fallback: "Downloading %@ to the received files folder…"), name)
        default:
            return false
        }
        return true
    }

    /// Handles `browse.error` for one of this browser's requests.
    @discardableResult
    func receive(error: BrowseErrorPayload) -> Bool {
        guard error.version == 1, let request = take(error.requestId) else { return false }
        if case .list = request, error.requestId != latestListRequest { return true }
        loading = false
        status = Self.text(forError: error.code)
        if error.code == "unavailable" || error.code == "not_found", case .list = request, !path.isEmpty {
            entries = []
            nextOffset = nil
        }
        return true
    }

    static func text(forError code: String) -> String {
        switch code {
        case "policy": L10n.text("browse.error.policy", fallback: "Browsing is turned off on Android")
        case "busy": L10n.text("browse.error.busy", fallback: "Android is busy. Try again in a moment.")
        case "not_found": L10n.text("browse.error.notFound", fallback: "This item is no longer on Android")
        case "unavailable": L10n.text("browse.error.unavailable", fallback: "Android can no longer open this shared folder")
        case "read_only": L10n.text("browse.error.readOnly", fallback: "Android does not allow adding files here")
        default: L10n.text("browse.error.other", fallback: "Android could not complete the request")
        }
    }

    private func list(offset: Int) {
        guard let rootID else { return }
        let requestID = newRequestID()
        let path = path.joined(separator: "/")
        latestListRequest = requestID
        if offset == 0 {
            entries = []
            nextOffset = nil
            total = 0
        }
        request(
            "browse.list",
            BrowseRequestPayload(requestId: requestID, rootId: rootID, path: path, offset: offset),
            pending: .list(rootID: rootID, path: path, offset: offset)
        )
    }

    private func request(_ kind: String, _ payload: BrowseRequestPayload, pending request: Pending) {
        guard available(), let data = try? JSONEncoder().encode(payload), send?(kind, data) == true else {
            status = L10n.text("browse.unavailable", fallback: "Connect an Android device with Browse shared folders turned on.")
            loading = false
            return
        }
        let requestID = payload.requestId
        pending[requestID] = request
        loading = true
        status = nil
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, self.take(requestID) != nil else { return }
            self.loading = false
            self.status = L10n.text("browse.timeout", fallback: "Android did not answer")
        }
        timeouts[requestID] = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.timeout, execute: timeout)
    }

    private func take(_ requestID: String) -> Pending? {
        timeouts.removeValue(forKey: requestID)?.cancel()
        return pending.removeValue(forKey: requestID)
    }

    private func newRequestID() -> String { UUID().uuidString.lowercased() }
}
