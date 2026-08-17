import AppKit
import Carbon
import Foundation

func phoneNumberFromTelURL(_ value: String) -> String? {
    guard value.lowercased().hasPrefix("tel:") else { return nil }
    var number = String(value.dropFirst(4))
    if number.hasPrefix("//") { number.removeFirst(2) }
    guard let decoded = number.removingPercentEncoding else { return nil }
    return normalizedPhoneNumber(decoded)
}

@MainActor
final class PhoneURLHandler: NSObject {
    private let onCall: (String) -> Void

    init(onCall: @escaping (String) -> Void) {
        self.onCall = onCall
        super.init()
        NSAppleEventManager.shared().setEventHandler(
            self,
            andSelector: #selector(handleGetURL(_:withReplyEvent:)),
            forEventClass: AEEventClass(kInternetEventClass),
            andEventID: AEEventID(kAEGetURL)
        )
    }

    deinit {
        NSAppleEventManager.shared().removeEventHandler(
            forEventClass: AEEventClass(kInternetEventClass),
            andEventID: AEEventID(kAEGetURL)
        )
    }

    @objc private func handleGetURL(
        _ event: NSAppleEventDescriptor,
        withReplyEvent _: NSAppleEventDescriptor
    ) {
        guard let value = event.paramDescriptor(forKeyword: AEKeyword(keyDirectObject))?.stringValue,
              let number = phoneNumberFromTelURL(value) else { return }
        onCall(number)
    }
}
