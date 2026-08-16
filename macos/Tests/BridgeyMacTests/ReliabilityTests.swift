import Foundation
import Network
import XCTest
@testable import BridgeyMac

final class ReliabilityTests: XCTestCase {
    func testMalformedAndOversizedFramesAreRejected() throws {
        XCTAssertThrowsError(try decodeProtocolMessage(Data("{}".utf8)))
        XCTAssertThrowsError(try decodeProtocolMessage(Data(repeating: 0x78, count: maximumProtocolFrameBytes + 1)))
        let longSession = Data((#"{"kind":"features.update","sessionId":""# + String(repeating: "x", count: 129) + #""}"#).utf8)
        XCTAssertThrowsError(try decodeProtocolMessage(longSession))
        let valid = Data(#"{"kind":"features.update","sessionId":"session"}"#.utf8)
        XCTAssertEqual(try decodeProtocolMessage(valid).kind, "features.update")
    }

    func testReconnectBackoffIsBounded() {
        XCTAssertEqual((0...6).map(reconnectDelay), [1, 2, 4, 8, 16, 30, 30])
    }

    func testHeartbeatTimeoutRequiresNegotiatedSupport() {
        let now = Date(timeIntervalSince1970: 100)
        XCTAssertFalse(heartbeatExpired(supported: false, lastReceivedAt: .distantPast, now: now))
        XCTAssertFalse(heartbeatExpired(supported: true, lastReceivedAt: Date(timeIntervalSince1970: 80), now: now))
        XCTAssertTrue(heartbeatExpired(supported: true, lastReceivedAt: Date(timeIntervalSince1970: 70), now: now))
    }

    func testLocalNetworkPermissionErrorsAreRecognized() {
        XCTAssertTrue(localNetworkPermissionDenied(.posix(.EACCES)))
        XCTAssertTrue(localNetworkPermissionDenied(.dns(-65570)))
        XCTAssertFalse(localNetworkPermissionDenied(.posix(.ECONNREFUSED)))
    }

    func testInterruptedTransferBecomesHistoryEntry() {
        let transfer = FileTransferRow(
            id: "transfer",
            name: "file",
            status: "Receiving",
            active: true,
            startedAt: Date(timeIntervalSince1970: 1),
            retryable: false
        )

        let recovered = recoverInterruptedTransfers([transfer.id: transfer])

        XCTAssertFalse(recovered[transfer.id]!.active)
        XCTAssertEqual(recovered[transfer.id]!.status, "Transfer interrupted — reconnect to retry")
    }
}
