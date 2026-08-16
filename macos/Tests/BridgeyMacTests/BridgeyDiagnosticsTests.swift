import XCTest
@testable import BridgeyMac

final class BridgeyDiagnosticsTests: XCTestCase {
    func testEventsAreBoundedAndNormalized() {
        let diagnostics = BridgeyDiagnostics(limit: 2)
        diagnostics.record(category: "Transport", event: "Connected")
        diagnostics.record(category: "Plugin", event: "Clipboard content", outcome: "Rejected")
        diagnostics.record(category: "Transfer", event: "Interrupted", outcome: "Needs retry")

        let events = diagnostics.snapshot()

        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events.last?.category, "transfer")
        XCTAssertEqual(events.last?.outcome, "needs_retry")
    }
}
