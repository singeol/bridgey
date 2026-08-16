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
