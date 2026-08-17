import XCTest
@testable import BridgeyMac

final class CallRequestTests: XCTestCase {
    func testNormalizesCommonPhoneNumberFormatting() {
        XCTAssertEqual(normalizedPhoneNumber(" +7 (999) 123-45-67 "), "+79991234567")
        XCTAssertEqual(normalizedPhoneNumber("12345"), "12345")
    }

    func testRejectsCommandsExtensionsAndInvalidLengths() {
        XCTAssertNil(normalizedPhoneNumber("*100#"))
        XCTAssertNil(normalizedPhoneNumber("+1 555 CALL-NOW"))
        XCTAssertNil(normalizedPhoneNumber("12"))
        XCTAssertNil(normalizedPhoneNumber("1234567890123456"))
        XCTAssertNil(normalizedPhoneNumber("7 +999 123"))
        XCTAssertNil(normalizedPhoneNumber("١٢٣٤٥"))
    }
}
