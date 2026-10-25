import Foundation

private let supportedRemoteCallTypes: Set<String> = ["incoming", "ongoing", "screening", "unknown"]

func normalizedRemoteCallType(_ value: String?) -> String? {
    guard let value, supportedRemoteCallTypes.contains(value) else { return nil }
    return value
}

func remoteCallStatusTitle(_ type: String?) -> String {
    switch type {
    case "incoming": return "Incoming call"
    case "ongoing": return "Call in progress"
    case "screening": return "Call screening"
    default: return "Phone call"
    }
}

func remoteCallDetail(_ detail: String, type: String?) -> String {
    let trimmed = detail.trimmingCharacters(in: .whitespacesAndNewlines)
    let genericCallDescriptions: Set<String> = [
        "incoming call", "ongoing call", "call in progress", "call screening", "screening call", "phone call",
    ]
    return genericCallDescriptions.contains(trimmed.lowercased()) ? "" : trimmed
}

func shouldUseSystemNotification(callType: String?) -> Bool {
    callType == nil
}

func remoteCallPausesMedia(_ type: String) -> Bool {
    type == "incoming" || type == "ongoing"
}

struct ActiveRemoteCalls {
    private static let maximumCount = 8
    private var calls: [RemoteCallStatus] = []

    var isEmpty: Bool { calls.isEmpty }
    var pausesMedia: Bool { calls.contains { remoteCallPausesMedia($0.type) } }

    var mostRelevant: RemoteCallStatus? {
        calls.last { $0.type == "incoming" } ?? calls.last
    }

    mutating func update(_ call: RemoteCallStatus) {
        if let index = calls.firstIndex(where: { $0.deviceID == call.deviceID && $0.notificationID == call.notificationID }) {
            calls[index] = call
        } else {
            calls.append(call)
            if calls.count > Self.maximumCount { calls.removeFirst() }
        }
    }

    @discardableResult
    mutating func remove(notificationID: String, deviceID: String) -> RemoteCallStatus? {
        guard let index = calls.firstIndex(where: { $0.deviceID == deviceID && $0.notificationID == notificationID }) else {
            return nil
        }
        return calls.remove(at: index)
    }

    mutating func removeAll() -> [RemoteCallStatus] {
        defer { calls = [] }
        return calls
    }
}
