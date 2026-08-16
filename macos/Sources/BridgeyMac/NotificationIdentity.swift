import CryptoKit
import Foundation

func remoteNotificationRequestIdentifier(deviceID: String, notificationID: String) -> String {
    let digest = SHA256.hash(data: Data("\(deviceID)\u{0}\(notificationID)".utf8))
        .map { String(format: "%02x", $0) }
        .joined()
    return "bridgey.android.\(digest)"
}

func remoteNotificationCategoryIdentifier(deviceID: String, notificationID: String, actionTokens: [String]) -> String {
    let value = ([deviceID, notificationID] + actionTokens).joined(separator: "\u{0}")
    let digest = SHA256.hash(data: Data(value.utf8))
        .map { String(format: "%02x", $0) }
        .joined()
    return "bridgey.android.actions.\(digest)"
}

let maximumRemoteNotificationIconBytes = 20 * 1024

func remoteNotificationIconData(_ encoded: String?) -> Data? {
    guard let encoded,
          encoded.count <= 28 * 1024,
          let data = Data(base64Encoded: encoded),
          !data.isEmpty,
          data.count <= maximumRemoteNotificationIconBytes,
          data.starts(with: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]) else { return nil }
    return data
}

func remoteNotificationIconFileName(packageName: String, data: Data) -> String {
    var input = Data(packageName.utf8)
    input.append(0)
    input.append(data)
    let digest = SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
    return "\(digest).png"
}
