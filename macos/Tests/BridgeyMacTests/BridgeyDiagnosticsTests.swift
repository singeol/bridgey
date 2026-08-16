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

    func testReportContainsCountsButNotFileNames() throws {
        let diagnostics = BridgeyDiagnostics()
        let privateName = "private-tax-document.pdf"
        let transfer = FileTransferRow(id: "secret-id", name: privateName, status: "Failed", active: false)

        let data = try diagnostics.report(
            connectionState: "idle",
            transfers: [transfer],
            localFeatures: [:],
            remoteFeatures: [:]
        )
        let report = String(decoding: data, as: UTF8.self)

        XCTAssertTrue(report.contains("\"historyCount\" : 1"))
        XCTAssertFalse(report.contains(privateName))
        XCTAssertFalse(report.contains("secret-id"))
    }
}
