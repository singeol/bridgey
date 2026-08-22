import XCTest
@testable import BridgeyMac

final class PhoneURLHandlerTests: XCTestCase {
    func testExtractsValidatedNumberFromTelURL() {
        XCTAssertEqual(phoneNumberFromTelURL("tel:+79991234567"), "+79991234567")
        XCTAssertEqual(phoneNumberFromTelURL("TEL:%2B7%20(999)%20123-45-67"), "+79991234567")
        XCTAssertEqual(phoneNumberFromTelURL("tel://12345"), "12345")
    }

    func testRejectsNonPhoneAndDialCommands() {
        XCTAssertNil(phoneNumberFromTelURL("https://bridgey.dev"))
        XCTAssertNil(phoneNumberFromTelURL("tel:*100%23"))
        XCTAssertNil(phoneNumberFromTelURL("tel:+1-555-CALL-NOW"))
    }

    func testQueuesAColdLaunchURLUntilCallHandlingIsConfigured() {
        let router = PendingPhoneCallRouter()
        var received: [String] = []

        router.receive("tel:%2B79991234567")
        XCTAssertTrue(received.isEmpty)

        router.configure { received.append($0) }
        XCTAssertEqual(received, ["+79991234567"])

        router.receive("tel:12345")
        XCTAssertEqual(received, ["+79991234567", "12345"])
    }
}
