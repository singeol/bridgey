import Darwin
import Foundation

/// What Finder shows about a received file: "Where from" names the sending
/// device, and the Dock's Downloads stack bounces when the file lands inside
/// ~/Downloads, as for a finished browser download. Best effort: a volume
/// without extended attributes simply goes without.
enum FinderMetadata {
    static let whereFromsAttribute = "com.apple.metadata:kMDItemWhereFroms"

    /// "Bridgey: Pixel 9".
    static func whereFrom(deviceName: String) -> String { "Bridgey: \(String(deviceName.prefix(64)))" }

    /// Writes kMDItemWhereFroms the way browsers do: a binary property list holding an array of strings.
    @discardableResult
    static func setWhereFrom(_ url: URL, _ value: String) -> Bool {
        guard let data = try? PropertyListSerialization.data(fromPropertyList: [value], format: .binary, options: 0) else {
            return false
        }
        return data.withUnsafeBytes { bytes in
            setxattr(url.path, whereFromsAttribute, bytes.baseAddress, bytes.count, 0, XATTR_NOFOLLOW) == 0
        }
    }

    static func whereFroms(_ url: URL) -> [String]? {
        let size = getxattr(url.path, whereFromsAttribute, nil, 0, 0, XATTR_NOFOLLOW)
        guard size > 0 else { return nil }
        var data = Data(count: size)
        let read = data.withUnsafeMutableBytes { getxattr(url.path, whereFromsAttribute, $0.baseAddress, size, 0, XATTR_NOFOLLOW) }
        guard read == size else { return nil }
        return (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String]
    }

    static func isInDownloads(
        _ url: URL,
        downloads: URL? = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
    ) -> Bool {
        guard let downloads else { return false }
        let base = downloads.resolvingSymlinksInPath().standardizedFileURL.path
        let path = url.resolvingSymlinksInPath().standardizedFileURL.path
        return path == base || path.hasPrefix(base + "/")
    }

    /// Marks a verified received file and lets the Downloads stack bounce.
    static func finishReceivedFile(_ url: URL, deviceName: String) {
        setWhereFrom(url, whereFrom(deviceName: deviceName))
        guard isInDownloads(url) else { return }
        DistributedNotificationCenter.default().post(
            name: Notification.Name("com.apple.DownloadFileFinished"),
            object: url.resolvingSymlinksInPath().path
        )
    }
}
