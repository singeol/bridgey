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
