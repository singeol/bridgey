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
