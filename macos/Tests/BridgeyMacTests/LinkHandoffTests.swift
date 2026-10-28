import XCTest
@testable import BridgeyMac

final class LinkHandoffTests: XCTestCase {
    @MainActor private func offer(_ actions: QuickActions, _ link: String = "https://example.com/page") {
        actions.receive("quick.request", payload: ["version": 1, "requestId": UUID().uuidString,
            "feature": "links", "action": "offer", "value": link])
    }

    @MainActor func testReceivedLinksWaitForAnExplicitOpenOrDismiss() {
        let actions = QuickActions()
        var replies: [[String: Any]] = []
        actions.available = { $0 == .links }
        actions.send = { _, payload in replies.append(payload); return true }
        offer(actions)
        XCTAssertEqual(actions.receivedLink, "https://example.com/page")
        XCTAssertEqual(replies.last?["accepted"] as? Bool, true)
        XCTAssertNil(replies.last?["opened"])
        offer(actions, "https://example.com/second")
        XCTAssertEqual(replies.last?["accepted"] as? Bool, false, "one pending link at a time")
        XCTAssertEqual(actions.receivedLink, "https://example.com/page")
        actions.dismissLink()
        offer(actions, "javascript:alert(1)")
        XCTAssertEqual(replies.last?["accepted"] as? Bool, false)
        XCTAssertNil(actions.receivedLink)
    }

    func testBrowserScriptsAreFixedAndPreferTheLastBrowser() {
        XCTAssertEqual(preferredBrowser(last: .chrome, running: [.safari, .chrome]), .chrome)
        XCTAssertEqual(preferredBrowser(last: .chrome, running: [.safari]), .safari)
        XCTAssertEqual(preferredBrowser(last: nil, running: [.chrome]), .chrome)
        XCTAssertNil(preferredBrowser(last: .safari, running: []))
        XCTAssertTrue(SupportedBrowser.safari.script.contains("tell application id \"com.apple.Safari\" to return URL of current tab of front window"))
        XCTAssertTrue(SupportedBrowser.chrome.script.contains("active tab"))
        XCTAssertNotNil(BrowserTabFailure.automation.message.range(of: "Automation"))
    }
}
