import XCTest
@testable import BridgeyMac

final class NotificationCallTests: XCTestCase {
    func testAcceptsOnlyKnownCallTypes() {
        XCTAssertEqual(normalizedRemoteCallType("incoming"), "incoming")
        XCTAssertEqual(normalizedRemoteCallType("ongoing"), "ongoing")
        XCTAssertEqual(normalizedRemoteCallType("screening"), "screening")
        XCTAssertEqual(normalizedRemoteCallType("unknown"), "unknown")
        XCTAssertNil(normalizedRemoteCallType("decline"))
        XCTAssertNil(normalizedRemoteCallType(nil))
    }

    func testProvidesSafeCallTitles() {
        XCTAssertEqual(remoteCallStatusTitle("incoming"), "Incoming call")
        XCTAssertEqual(remoteCallStatusTitle("ongoing"), "Call in progress")
        XCTAssertEqual(remoteCallStatusTitle("screening"), "Call screening")
        XCTAssertEqual(remoteCallStatusTitle("unknown"), "Phone call")
    }

    func testRemovesStaleGenericAndroidCallDescriptionAfterStateTransition() {
        XCTAssertEqual(remoteCallDetail("Incoming call", type: "ongoing"), "")
        XCTAssertEqual(remoteCallDetail("Call in progress", type: "ongoing"), "")
        XCTAssertEqual(remoteCallDetail("  Verified caller  ", type: "incoming"), "Verified caller")
    }

    func testCallOverlayReplacesOnlySystemCallNotifications() {
        XCTAssertTrue(shouldUseSystemNotification(callType: nil))
        XCTAssertFalse(shouldUseSystemNotification(callType: "incoming"))
        XCTAssertFalse(shouldUseSystemNotification(callType: "ongoing"))
    }

    func testCallOverlayPlacesAnswerBeforeDecline() {
        let decline = RemoteCallAction(id: "decline", title: "Decline")
        let answer = RemoteCallAction(id: "answer", title: "Answer")
        XCTAssertEqual(orderedCallActions([decline, answer]), [answer, decline])
    }
}
