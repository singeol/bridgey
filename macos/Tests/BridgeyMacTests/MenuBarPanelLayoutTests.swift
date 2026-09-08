import AppKit
import SwiftUI
import XCTest
@testable import BridgeyMac

final class MenuBarPanelLayoutTests: XCTestCase {
    @MainActor func testCompactPanelRejectsStaleTallWindowProposal() {
        let host = NSHostingController(rootView: fixture(height: 120, transfers: 10))
        let natural = host.sizeThatFits(in: NSSize(width: 340, height: 400))
        let stale = host.sizeThatFits(in: NSSize(width: 340, height: 1800))
        XCTAssertEqual(natural.width, 340, accuracy: 0.5)
        XCTAssertLessThan(natural.height, 250)
        XCTAssertEqual(stale.height, natural.height, accuracy: 0.5)
    }

    @MainActor func testPanelShrinksAfterRepeatedConnectionStateChanges() {
        let host = NSHostingController(rootView: fixture(height: 120, transfers: 10))
        let proposal = NSSize(width: 340, height: 1800)
        let compact = host.sizeThatFits(in: proposal)
        for _ in 0..<5 {
            host.rootView = fixture(height: 480, transfers: 10)
            let expanded = host.sizeThatFits(in: proposal)
            XCTAssertGreaterThan(expanded.height, compact.height + 300)
            host.rootView = fixture(height: 120, transfers: 10)
            XCTAssertEqual(host.sizeThatFits(in: proposal).height, compact.height, accuracy: 0.5)
        }
    }

    @MainActor func testTransferHistoryRowDoesNotExpandVertically() {
        let host = NSHostingController(rootView: fixture(height: 120, transfers: 0))
        let proposal = NSSize(width: 340, height: 1800)
        let withoutHistory = host.sizeThatFits(in: proposal)
        host.rootView = fixture(height: 120, transfers: 10)
        let withHistory = host.sizeThatFits(in: proposal)
        XCTAssertGreaterThan(withHistory.height, withoutHistory.height)
        XCTAssertLessThan(withHistory.height - withoutHistory.height, 60)
        host.rootView = fixture(height: 120, transfers: 0)
        XCTAssertEqual(host.sizeThatFits(in: proposal).height, withoutHistory.height, accuracy: 0.5)
    }

    @MainActor private func fixture(height: CGFloat, transfers: Int) -> some View {
        MenuBarPanelSurface {
            VStack(alignment: .leading, spacing: 14) {
                Color.clear.frame(height: height)
                if transfers > 0 { FileTransfersSummaryButton(count: transfers, action: {}) }
            }
            .padding(16)
        }
    }
}
