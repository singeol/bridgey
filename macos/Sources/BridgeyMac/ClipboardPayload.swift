import Foundation

let maximumClipboardContentBytes = 32 * 1024

struct RichClipboardContent: Codable, Equatable {
    let version: Int
    let text: String
    let html: String

    init?(text: String, html: String?) {
        guard let html, !text.isEmpty, !html.isEmpty,
              text.utf8.count + html.utf8.count <= maximumClipboardContentBytes else { return nil }
        version = 1
        self.text = text
        self.html = html
    }

    func validated() -> RichClipboardContent? {
        guard version == 1 else { return nil }
        return RichClipboardContent(text: text, html: html)
    }
}

func clipboardTextFits(_ text: String) -> Bool {
    !text.isEmpty && text.utf8.count <= maximumClipboardContentBytes
}
