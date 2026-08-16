import XCTest
@testable import BridgeyMac

final class ClipboardPayloadTests: XCTestCase {
    func testRichClipboardRequiresPlainFallbackAndHTML() {
        XCTAssertNil(RichClipboardContent(text: "text", html: nil))
        XCTAssertNil(RichClipboardContent(text: "", html: "<b>text</b>"))
        XCTAssertEqual(RichClipboardContent(text: "text", html: "<b>text</b>")?.html, "<b>text</b>")
    }

    func testClipboardContentIsBounded() {
        XCTAssertTrue(clipboardTextFits(String(repeating: "x", count: maximumClipboardContentBytes)))
        XCTAssertNil(RichClipboardContent(
            text: String(repeating: "x", count: maximumClipboardContentBytes),
            html: "<b>x</b>"
        ))
    }
}
