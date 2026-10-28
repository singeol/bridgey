import Foundation
import XCTest
@testable import BridgeyMac

@MainActor
final class PhoneFolderBrowserTests: XCTestCase {
    private var browser: PhoneFolderBrowser!
    private var sent: [(kind: String, payload: [String: Any])] = []

    override func setUp() async throws {
        browser = PhoneFolderBrowser()
        sent = []
        browser.available = { true }
        browser.send = { [weak self] kind, data in
            let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            self?.sent.append((kind, payload))
            return true
        }
    }

    private func answer(_ kind: String, _ payload: [String: Any]) -> Bool {
        browser.receive(kind: kind, payload: try! JSONSerialization.data(withJSONObject: payload))
    }

    private func lastRequestID() -> String { sent.last?.payload["requestId"] as? String ?? "" }

    private func openRoot() {
        browser.refreshRoots()
        XCTAssertTrue(answer("browse.roots.result", [
            "version": 1, "requestId": lastRequestID(), "uploads": true,
            "roots": [["rootId": "root-1", "name": "Camera"]],
        ]))
        browser.open(root: browser.roots[0])
    }

    func testRootsAndListingFollowTheRequests() {
        openRoot()
        XCTAssertEqual(browser.roots, [PhoneFolderRoot(id: "root-1", name: "Camera")])
        XCTAssertTrue(browser.uploadsAllowed)
        XCTAssertEqual(sent.last?.kind, "browse.list")
        XCTAssertEqual(sent.last?.payload["path"] as? String, "")
        XCTAssertEqual(sent.last?.payload["offset"] as? Int, 0)

        XCTAssertTrue(answer("browse.list.result", [
            "version": 1, "requestId": lastRequestID(), "rootId": "root-1", "path": "", "offset": 0, "total": 3,
            "entries": [["name": "2026", "dir": true], ["name": "a.jpg", "dir": false, "size": 10, "modified": 1_000]],
            "nextOffset": 2,
        ]))
        XCTAssertEqual(browser.entries.map(\.name), ["2026", "a.jpg"])
        XCTAssertEqual(browser.entries[1].size, 10)
        XCTAssertEqual(browser.nextOffset, 2)

        browser.loadMore()
        XCTAssertEqual(sent.last?.payload["offset"] as? Int, 2)
        XCTAssertTrue(answer("browse.list.result", [
            "version": 1, "requestId": lastRequestID(), "rootId": "root-1", "path": "", "offset": 2, "total": 3,
            "entries": [["name": "b.jpg", "dir": false]],
        ]))
        XCTAssertEqual(browser.entries.map(\.name), ["2026", "a.jpg", "b.jpg"])
        XCTAssertNil(browser.nextOffset)

        browser.open(folder: browser.entries[0])
        XCTAssertEqual(sent.last?.payload["path"] as? String, "2026")
        browser.download(PhoneFolderEntry(name: "c.jpg", isDirectory: false, size: 1, modified: nil))
        XCTAssertEqual(sent.last?.kind, "browse.download")
        XCTAssertEqual(sent.last?.payload["path"] as? String, "2026/c.jpg")
    }

    func testAnswersThatWereNotRequestedOrBreakTheRulesAreIgnored() {
        openRoot()
        let listID = lastRequestID()
        XCTAssertFalse(answer("browse.list.result", [
            "version": 1, "requestId": UUID().uuidString.lowercased(), "rootId": "root-1", "path": "", "offset": 0,
            "total": 0, "entries": [],
        ]))
        XCTAssertFalse(answer("browse.list.result", [
            "version": 1, "requestId": listID, "rootId": "root-1", "path": "", "offset": 0, "total": 1,
            "entries": [["name": "../escape", "dir": false]],
        ]))
        XCTAssertTrue(browser.entries.isEmpty)
        XCTAssertFalse(browser.loading)

        browser.goTo(depth: 0)
        let stale = lastRequestID()
        browser.refresh()
        XCTAssertFalse(answer("browse.list.result", [
            "version": 1, "requestId": stale, "rootId": "root-1", "path": "", "offset": 0, "total": 1,
            "entries": [["name": "old.jpg", "dir": false]],
        ]))
        XCTAssertTrue(browser.entries.isEmpty)
    }

    func testErrorsAreShownForTheirRequestOnly() {
        openRoot()
        XCTAssertFalse(browser.receive(error: BrowseErrorPayload(version: 1, requestId: "other", code: "busy")))
        XCTAssertTrue(browser.receive(error: BrowseErrorPayload(version: 1, requestId: lastRequestID(), code: "unavailable")))
        XCTAssertEqual(browser.status, PhoneFolderBrowser.text(forError: "unavailable"))
        XCTAssertFalse(browser.loading)
    }

    func testUnavailableBrowserSendsNothing() {
        browser.available = { false }
        browser.refreshRoots()
        XCTAssertTrue(sent.isEmpty)
        XCTAssertTrue(browser.roots.isEmpty)
    }
}
