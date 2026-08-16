import Foundation
import Network

let maximumProtocolFrameBytes = 65_536
let maximumTransferHistory = 20

func reconnectDelay(attempt: Int) -> TimeInterval {
    let boundedAttempt = min(max(attempt, 0), 30)
    return min(pow(2.0, Double(boundedAttempt)), 30.0)
}

func heartbeatExpired(
    supported: Bool,
    lastReceivedAt: Date,
    now: Date = Date(),
    timeout: TimeInterval = 30
) -> Bool {
    supported && now.timeIntervalSince(lastReceivedAt) >= timeout
}

func localNetworkPermissionDenied(_ error: NWError) -> Bool {
    switch error {
    case .posix(.EACCES), .dns(-65570): true
    default: false
    }
}

func decodeProtocolMessage(_ data: Data) throws -> PairingMessage {
    guard !data.isEmpty, data.count <= maximumProtocolFrameBytes else {
        throw PairingError.invalidMessage
    }
    let message = try JSONDecoder().decode(PairingMessage.self, from: data)
    guard !message.kind.isEmpty, message.kind.utf8.count <= 64,
          !message.sessionId.isEmpty, message.sessionId.utf8.count <= 128 else {
        throw PairingError.invalidMessage
    }
    return message
}

func recoverInterruptedTransfers(_ transfers: [String: FileTransferRow]) -> [String: FileTransferRow] {
    Dictionary(uniqueKeysWithValues: transfers.values
        .map { transfer in
            guard transfer.active else { return transfer }
            return FileTransferRow(
                id: transfer.id,
                name: transfer.name,
                status: "Transfer interrupted — reconnect to retry",
                active: false,
                startedAt: transfer.startedAt,
                retryable: transfer.retryable
            )
        }
        .sorted { $0.startedAt > $1.startedAt }
        .prefix(maximumTransferHistory)
        .map { ($0.id, $0) })
}
