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

    func testCallOverlayKeepsAndroidActionOrder() {
        let decline = RemoteCallAction(id: "decline", title: "Decline")
        let answer = RemoteCallAction(id: "answer", title: "Answer")
        XCTAssertEqual(visibleCallActions([decline, answer], limit: 2), [decline, answer])
        XCTAssertEqual(visibleCallActions([answer, decline], limit: 2), [answer, decline])
    }

    func testCallOverlayNeverDropsHangUp() {
        let speaker = RemoteCallAction(id: "speaker", title: "Speaker")
        let mute = RemoteCallAction(id: "mute", title: "Mute")
        let hangUp = RemoteCallAction(id: "hang-up", title: "Hang Up")
        XCTAssertEqual(visibleCallActions([speaker, mute, hangUp], limit: 2), [speaker, hangUp])
        XCTAssertEqual(visibleCallActions([hangUp, speaker, mute], limit: 2), [hangUp, speaker])
        XCTAssertEqual(visibleCallActions([speaker, mute], limit: 2), [speaker, mute])
        XCTAssertEqual(visibleCallActions([speaker, mute, hangUp], limit: 3), [speaker, mute, hangUp])
    }

    func testCallActionRolesMatchEnglishAndRussianTitles() {
        XCTAssertEqual(callActionRole("Answer"), .answer)
        XCTAssertEqual(callActionRole("Ответить"), .answer)
        XCTAssertEqual(callActionRole("Decline"), .end)
        XCTAssertEqual(callActionRole("Hang Up"), .end)
        XCTAssertEqual(callActionRole("ОТКЛОНИТЬ"), .end)
        XCTAssertEqual(callActionRole("Завершить"), .end)
        XCTAssertEqual(callActionRole("Speaker"), .other)
    }

    func testCallWaitingShowsIncomingCallAndFallsBackWhenItEnds() {
        var calls = ActiveRemoteCalls()
        calls.update(remoteCall("first", type: "ongoing"))
        calls.update(remoteCall("second", type: "incoming"))
        XCTAssertEqual(calls.mostRelevant?.notificationID, "second")

        calls.update(remoteCall("first", type: "ongoing", caller: "Updated"))
        XCTAssertEqual(calls.mostRelevant?.notificationID, "second")

        XCTAssertEqual(calls.remove(notificationID: "second", deviceID: "phone")?.notificationID, "second")
        XCTAssertEqual(calls.mostRelevant?.notificationID, "first")
        XCTAssertEqual(calls.mostRelevant?.caller, "Updated")
        XCTAssertTrue(calls.pausesMedia)
    }

    func testNewestCallWinsWhenNoneIsIncoming() {
        var calls = ActiveRemoteCalls()
        calls.update(remoteCall("held", type: "ongoing"))
        calls.update(remoteCall("answered", type: "ongoing"))
        XCTAssertEqual(calls.mostRelevant?.notificationID, "answered")
        calls.update(remoteCall("held", type: "ongoing"))
        XCTAssertEqual(calls.mostRelevant?.notificationID, "answered")
    }

    func testCallsAreKeyedByDeviceAndNotification() {
        var calls = ActiveRemoteCalls()
        calls.update(remoteCall("call", type: "ongoing", deviceID: "phone-a"))
        calls.update(remoteCall("call", type: "ongoing", deviceID: "phone-b"))
        XCTAssertNil(calls.remove(notificationID: "call", deviceID: "phone-c"))
        calls.remove(notificationID: "call", deviceID: "phone-b")
        XCTAssertEqual(calls.mostRelevant?.deviceID, "phone-a")
    }

    func testMediaStaysPausedUntilTheLastCallEnds() {
        var calls = ActiveRemoteCalls()
        XCTAssertFalse(calls.pausesMedia)
        calls.update(remoteCall("first", type: "ongoing"))
        calls.update(remoteCall("second", type: "incoming"))
        calls.remove(notificationID: "second", deviceID: "phone")
        XCTAssertTrue(calls.pausesMedia)
        calls.remove(notificationID: "first", deviceID: "phone")
        XCTAssertFalse(calls.pausesMedia)
        XCTAssertNil(calls.mostRelevant)
    }

    func testScreeningAloneDoesNotPauseMedia() {
        var calls = ActiveRemoteCalls()
        calls.update(remoteCall("screened", type: "screening"))
        XCTAssertFalse(calls.pausesMedia)
    }

    func testRemoveAllReturnsEveryCall() {
        var calls = ActiveRemoteCalls()
        calls.update(remoteCall("first", type: "ongoing"))
        calls.update(remoteCall("second", type: "incoming"))
        XCTAssertEqual(calls.removeAll().map(\.notificationID), ["first", "second"])
        XCTAssertTrue(calls.isEmpty)
        XCTAssertNil(calls.mostRelevant)
    }

    private func remoteCall(
        _ notificationID: String,
        type: String,
        deviceID: String = "phone",
        caller: String = "Caller"
    ) -> RemoteCallStatus {
        RemoteCallStatus(
            notificationID: notificationID,
            deviceID: deviceID,
            applicationName: "Phone",
            caller: caller,
            detail: "",
            type: type,
            actions: []
        )
    }
}
