import Foundation

struct BridgeyDiagnosticEvent: Codable, Equatable {
    let timestamp: String
    let category: String
    let event: String
    let outcome: String
}

final class BridgeyDiagnostics {
    private let limit: Int
    private var events: [BridgeyDiagnosticEvent] = []
    private let formatter = ISO8601DateFormatter()

    init(limit: Int = 200) { self.limit = limit }

    func record(category: String, event: String, outcome: String = "ok") {
        events.append(BridgeyDiagnosticEvent(
            timestamp: formatter.string(from: Date()),
            category: token(category),
            event: token(event),
            outcome: token(outcome)
        ))
        if events.count > limit { events.removeFirst(events.count - limit) }
    }

    func snapshot() -> [BridgeyDiagnosticEvent] { events }

    func report(
        connectionState: String,
        transfers: [FileTransferRow],
        localFeatures: [BridgeyFeature: Bool],
        remoteFeatures: [BridgeyFeature: Bool]
    ) throws -> Data {
        let featureObject: ([BridgeyFeature: Bool]) -> [String: Bool] = { features in
            Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0.rawValue, features[$0] != false) })
        }
        let payload: [String: Any] = [
            "schemaVersion": 1,
            "generatedAt": formatter.string(from: Date()),
            "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "development",
            "platform": "macos",
            "osVersion": ProcessInfo.processInfo.operatingSystemVersionString,
            "connectionState": token(connectionState),
            "activeTransferCount": transfers.filter { $0.active }.count,
            "historyCount": transfers.filter { !$0.active }.count,
            "localFeatures": featureObject(localFeatures),
            "remoteFeatures": featureObject(remoteFeatures),
            "events": try events.map { event in
                let data = try JSONEncoder().encode(event)
                return try JSONSerialization.jsonObject(with: data)
            },
        ]
        return try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
    }

    private func token(_ value: String) -> String {
        String(value.lowercased().map { character in
            character.isASCII && (character.isLetter || character.isNumber || "_.-".contains(character)) ? character : "_"
        }.prefix(64))
    }
}
