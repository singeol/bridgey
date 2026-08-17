import AppKit
import Foundation

func normalizedPhoneNumber(_ value: String) -> String? {
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    let allowed = CharacterSet(charactersIn: "+-(). 0123456789")
    guard !trimmed.isEmpty,
          trimmed.unicodeScalars.allSatisfy(allowed.contains),
          trimmed.filter({ $0 == "+" }).count <= 1,
          !trimmed.contains("+") || trimmed.first == "+" else { return nil }
    let digits = trimmed.filter(\.isNumber)
    guard (3...15).contains(digits.count) else { return nil }
    return (trimmed.first == "+" ? "+" : "") + digits
}

@MainActor
final class CallServiceProvider: NSObject {
    private let onCall: (String) -> Void

    init(onCall: @escaping (String) -> Void) {
        self.onCall = onCall
    }

    @objc func callWithBridgey(
        _ pasteboard: NSPasteboard,
        userData: String,
        error: AutoreleasingUnsafeMutablePointer<NSString?>
    ) {
        guard let value = pasteboard.string(forType: .string), normalizedPhoneNumber(value) != nil else {
            error.pointee = "The selected text is not a supported phone number."
            return
        }
        onCall(value)
    }
}
