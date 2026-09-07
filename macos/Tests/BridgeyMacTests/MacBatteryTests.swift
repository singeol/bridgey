import XCTest
@testable import BridgeyMac

final class MacBatteryTests: XCTestCase {
    func testNormalizesBatteryCapacity() {
        XCTAssertEqual(
            normalizedBatteryStatus(current: 79, maximum: 100, isCharging: true),
            LocalBatteryStatus(level: 79, isCharging: true)
        )
        XCTAssertEqual(
            normalizedBatteryStatus(current: 151, maximum: 200, isCharging: false),
            LocalBatteryStatus(level: 76, isCharging: false)
        )
    }

    func testRejectsInvalidCapacityAndClampsUnexpectedValues() {
        XCTAssertNil(normalizedBatteryStatus(current: -1, maximum: 100, isCharging: false))
        XCTAssertNil(normalizedBatteryStatus(current: 50, maximum: 0, isCharging: false))
        XCTAssertEqual(
            normalizedBatteryStatus(current: 120, maximum: 100, isCharging: false)?.level,
            100
        )
    }
}
