import AppKit
import Foundation

func phoneNumberFromTelURL(_ value: String) -> String? {
    guard value.lowercased().hasPrefix("tel:") else { return nil }
    var number = String(value.dropFirst(4))
    if number.hasPrefix("//") { number.removeFirst(2) }
    guard let decoded = number.removingPercentEncoding else { return nil }
    return normalizedPhoneNumber(decoded)
}

final class PendingPhoneCallRouter {
    private var onCall: ((String) -> Void)?
    private var pendingNumbers: [String] = []

    func configure(onCall: @escaping (String) -> Void) {
        self.onCall = onCall
        let queued = pendingNumbers
        pendingNumbers.removeAll()
        queued.forEach(onCall)
    }

    func receive(_ value: String) {
        guard let number = phoneNumberFromTelURL(value) else { return }
        if let onCall {
            onCall(number)
        } else if pendingNumbers.count < 8 {
            pendingNumbers.append(number)
        }
    }
}

@MainActor
final class PhoneURLHandler: NSObject, NSApplicationDelegate {
    private let router = PendingPhoneCallRouter()

    func configure(onCall: @escaping (String) -> Void) {
        router.configure(onCall: onCall)
    }

    func application(_: NSApplication, open urls: [URL]) {
        urls.forEach { router.receive($0.absoluteString) }
    }
}
