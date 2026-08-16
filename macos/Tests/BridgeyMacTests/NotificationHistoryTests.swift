import Foundation
import XCTest
@testable import BridgeyMac

final class NotificationHistoryTests: XCTestCase {
    func testHistoryIsBoundedNewestFirstAndPersistent() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let file = directory.appendingPathComponent("history.json")
        let store = NotificationHistoryStore(fileURL: file)
        let now = Date(timeIntervalSince1970: 1_000_000)
        var items: [NotificationHistoryItem] = []

        for index in 0..<(NotificationHistoryStore.maximumItems + 5) {
            let item = NotificationHistoryItem(
                id: "notification-\(index)",
                packageName: "example.app",
                applicationName: "Example",
                title: "Title \(index)",
                text: "Text",
                receivedAt: now.addingTimeInterval(TimeInterval(index))
            )
            items = store.record(item, in: items, now: now.addingTimeInterval(TimeInterval(index)))
        }

        XCTAssertEqual(items.count, NotificationHistoryStore.maximumItems)
        XCTAssertEqual(items.first?.id, "notification-204")
        XCTAssertEqual(store.load(now: now.addingTimeInterval(204)).count, NotificationHistoryStore.maximumItems)
        try? FileManager.default.removeItem(at: directory)
    }

    func testHistoryExpiresAndUpdatesExistingNotification() {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("history.json")
        let store = NotificationHistoryStore(fileURL: file)
        let now = Date()
        let expired = NotificationHistoryItem(
            id: "expired",
            packageName: "old.app",
            applicationName: "Old",
            title: "Old",
            text: "Old",
            receivedAt: now.addingTimeInterval(-NotificationHistoryStore.maximumAge - 1)
        )
        let original = NotificationHistoryItem(
            id: "same",
            packageName: "chat.app",
            applicationName: "Chat",
            title: "Original",
            text: "First",
            receivedAt: now.addingTimeInterval(-10)
        )
        let updated = NotificationHistoryItem(
            id: "same",
            packageName: "chat.app",
            applicationName: "Chat",
            title: "Updated",
            text: "Second",
            receivedAt: now
        )

        let items = store.record(updated, in: [expired, original], now: now)

        XCTAssertEqual(items, [updated])
        store.clear()
        XCTAssertTrue(store.load(now: now).isEmpty)
    }
}
