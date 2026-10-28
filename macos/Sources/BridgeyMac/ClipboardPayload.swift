import Foundation

let maximumClipboardContentBytes = 32 * 1024

struct RichClipboardContent: Codable, Equatable {
    let version: Int
    let text: String
    let html: String

    init?(text: String, html: String?) {
        // As on Android, HTML that is only whitespace is not rich content.
        guard let html, !text.isEmpty, html.contains(where: { !$0.isWhitespace }),
              text.utf8.count + html.utf8.count <= maximumClipboardContentBytes else { return nil }
        version = 1
        self.text = text
        self.html = html
    }
}

func clipboardTextFits(_ text: String) -> Bool {
    !text.isEmpty && text.utf8.count <= maximumClipboardContentBytes
}
