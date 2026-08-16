import XCTest
@testable import BridgeyMac

final class NotificationIdentityTests: XCTestCase {
    func testIdentifierIsDeterministicAndNamespacedByDevice() {
        let first = remoteNotificationRequestIdentifier(deviceID: "phone-a", notificationID: "notification-1")
        XCTAssertEqual(first, remoteNotificationRequestIdentifier(deviceID: "phone-a", notificationID: "notification-1"))
        XCTAssertNotEqual(first, remoteNotificationRequestIdentifier(deviceID: "phone-b", notificationID: "notification-1"))
        XCTAssertTrue(first.hasPrefix("bridgey.android."))
    }

    func testActionCategoryDependsOnScopedTokens() {
        let first = remoteNotificationCategoryIdentifier(
            deviceID: "phone-a",
            notificationID: "notification-1",
            actionTokens: ["action-a"]
        )
        XCTAssertEqual(first, remoteNotificationCategoryIdentifier(
            deviceID: "phone-a",
            notificationID: "notification-1",
            actionTokens: ["action-a"]
        ))
        XCTAssertNotEqual(first, remoteNotificationCategoryIdentifier(
            deviceID: "phone-a",
            notificationID: "notification-1",
            actionTokens: ["action-b"]
        ))
    }
}
