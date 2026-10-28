import Foundation

/// Wire rules for folder transfers (docs/protocol.md, "Folders").
enum FolderRules {
    static let maxFiles = 10_000
    static let maxFolders = 2_000
    static let maxDepth = 32
    static let maxNameBytes = 255
    static let maxPathBytes = 1_024
    static let maxFileSize: Int64 = 10 * 1024 * 1024 * 1024
    static let maxTotalSize: Int64 = 1 << 40
    static let maxPages = 64
    static let maxEntriesPerPage = 256
    static let maxPayloadBytes = 32 * 1024

    /// One path component: never empty, `.`, `..`, a separator or a control character.
    static func isValidName(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".." && name.utf8.count <= maxNameBytes &&
            !name.unicodeScalars.contains { $0 == "/" || $0.value < 0x20 || $0.value == 0x7f }
    }

    /// A relative path inside the folder: valid components joined by `/`, bounded in depth and length.
    static func isValidPath(_ path: String) -> Bool {
        guard !path.isEmpty, path.utf8.count <= maxPathBytes else { return false }
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        return components.count <= maxDepth && components.allSatisfy { isValidName(String($0)) }
    }
}

struct FolderEntry: Equatable {
    let path: String
    let isDirectory: Bool
    let size: Int64
}

struct FolderEntryPayload: Codable, Equatable {
    let type: String
    let path: String
    let size: Int64?
}

/// A scanned folder ready to offer. Entries are in depth-first pre-order with
/// siblings sorted by name, so every entry's parent is listed before it.
struct OutgoingFolderManifest {
    let name: String
    let entries: [FolderEntry]
    let fileURLs: [Int: URL]
    let skippedItems: Int

    var fileCount: Int { entries.filter { !$0.isDirectory }.count }
    var folderCount: Int { entries.filter(\.isDirectory).count }
    var totalSize: Int64 { entries.reduce(0) { $0 + $1.size } }

    /// Splits the entries into manifest pages within the entry and payload limits.
    /// The margin leaves room for the page header around the entries.
    func pages(maxEntryBytes: Int = FolderRules.maxPayloadBytes - 512) -> [[FolderEntryPayload]] {
        let encoder = JSONEncoder()
        var pages: [[FolderEntryPayload]] = []
        var current: [FolderEntryPayload] = []
        var bytes = 0
        for entry in entries {
            let payload = FolderEntryPayload(
                type: entry.isDirectory ? "dir" : "file",
                path: entry.path,
                size: entry.isDirectory ? nil : entry.size
            )
            let size = ((try? encoder.encode(payload))?.count ?? maxEntryBytes) + 1
            if !current.isEmpty && (current.count >= FolderRules.maxEntriesPerPage || bytes + size > maxEntryBytes) {
                pages.append(current)
                current = []
                bytes = 0
            }
            current.append(payload)
            bytes += size
        }
        if !current.isEmpty { pages.append(current) }
        return pages
    }
}

enum FolderScanError: Error {
    case unreadable
    case empty
    case tooLarge
    case cancelled
}

/// Lists a folder for sending. Hidden items, symbolic links, special files,
/// partial Bridgey downloads and anything outside the wire limits are skipped
/// and counted; the scan fails only when the folder is unreadable, has no files
/// or exceeds the file, folder or size limits.
enum FolderScanner {
    private static let keys: [URLResourceKey] = [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]

    static func scan(_ root: URL, isCancelled: () -> Bool = { false }) throws -> OutgoingFolderManifest {
        let rootName = FolderRules.isValidName(root.lastPathComponent) ? root.lastPathComponent : "Folder"
        var entries: [FolderEntry] = []
        var fileURLs: [Int: URL] = [:]
        var skipped = 0
        var files = 0
        var folders = 0
        var total: Int64 = 0

        func visit(_ directory: URL, prefix: String) throws {
            guard !isCancelled() else { throw FolderScanError.cancelled }
            let children: [URL]
            do {
                children = try FileManager.default.contentsOfDirectory(
                    at: directory,
                    includingPropertiesForKeys: keys,
                    options: [.skipsHiddenFiles]
                )
            } catch {
                if prefix.isEmpty { throw FolderScanError.unreadable }
                skipped += 1
                return
            }
            for child in children.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let name = child.lastPathComponent
                let path = prefix.isEmpty ? name : "\(prefix)/\(name)"
                guard FolderRules.isValidPath(path), !name.hasSuffix(".bridgey-part"),
                      let values = try? child.resourceValues(forKeys: Set(keys)),
                      values.isSymbolicLink != true else {
                    skipped += 1
                    continue
                }
                if values.isDirectory == true {
                    folders += 1
                    guard folders <= FolderRules.maxFolders else { throw FolderScanError.tooLarge }
                    entries.append(FolderEntry(path: path, isDirectory: true, size: 0))
                    try visit(child, prefix: path)
                } else if values.isRegularFile == true, let size = values.fileSize.map(Int64.init),
                          size <= FolderRules.maxFileSize {
                    files += 1
                    total += size
                    guard files <= FolderRules.maxFiles, total <= FolderRules.maxTotalSize else {
                        throw FolderScanError.tooLarge
                    }
                    fileURLs[entries.count] = child
                    entries.append(FolderEntry(path: path, isDirectory: false, size: size))
                } else {
                    skipped += 1
                }
            }
        }

        try visit(root, prefix: "")
        guard files > 0 else { throw FolderScanError.empty }
        let manifest = OutgoingFolderManifest(name: rootName, entries: entries, fileURLs: fileURLs, skippedItems: skipped)
        guard manifest.pages().count <= FolderRules.maxPages else { throw FolderScanError.tooLarge }
        return manifest
    }
}
