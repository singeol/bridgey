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
}
