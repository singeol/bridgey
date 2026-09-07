import AppKit
import Foundation

struct QuickRequestSequence {
    private var last: [String: Int] = [:]
    mutating func accept(feature: String, sequence: Int) -> Bool {
        guard ["links", "media"].contains(feature), (1...9_007_199_254_740_991).contains(sequence),
              sequence > last[feature, default: 0] else { return false }
        last[feature] = sequence
        return true
    }
}

func validatedWebLink(_ value: String) -> String? {
    let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard text.utf8.count <= 4096,
          !text.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || $0.value < 32 || $0 == "\\" }),
          let parts = URLComponents(string: text),
          ["http", "https"].contains(parts.scheme?.lowercased() ?? ""),
          let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
          parts.port == nil || (1...65535).contains(parts.port!) else { return nil }
    return text
}

@MainActor
final class QuickActions: ObservableObject {
    @Published private(set) var receivedLink: String?
    @Published private(set) var status: String?
    var available: (BridgeyFeature) -> Bool = { _ in false }
    var send: (String, [String: Any]) -> Bool = { _, _ in false }
    private var pending: String?
    private var timeout: Task<Void, Never>?
    private var sequence = 0

    func sendClipboardLink() {
        guard let link = NSPasteboard.general.string(forType: .string).flatMap(validatedWebLink) else {
            status = "Copy a valid http or https link first"; return
        }
        guard available(.links) else { status = "Web links are unavailable on one of your devices"; return }
        guard pending == nil else { return }
        let id = UUID().uuidString.lowercased()
        pending = id
        sequence += 1
        status = "Sending link…"
        if !send("quick.request", ["version": 1, "requestId": id, "feature": "links", "action": "offer", "value": link, "sequence": sequence]) {
            pending = nil; status = "Not connected — link not sent"; return
        }
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled, self?.pending == id else { return }
            self?.pending = nil
            self?.status = "Android did not confirm delivery"
        }
    }

    func receive(_ kind: String, payload: [String: Any]) {
        guard payload["version"] as? Int == 1,
              let id = payload["requestId"] as? String, UUID(uuidString: id) != nil else { return }
        if kind == "quick.result" {
            guard id == pending, payload["feature"] as? String == "links" else { return }
            pending = nil; timeout?.cancel()
            status = payload["accepted"] as? Bool == true
                ? "Link delivered — waiting for the user to open it"
                : "Link declined — check settings or dismiss the previous link"
        } else if kind == "quick.request" {
            let link = (payload["value"] as? String).flatMap(validatedWebLink)
            let accepted = available(.links) && payload["feature"] as? String == "links" &&
                payload["action"] as? String == "offer" && link != nil && receivedLink == nil
            if accepted {
                receivedLink = link
                status = "Link received — open it below"
                NSSound(named: NSSound.Name("Glass"))?.play()
            }
            _ = send("quick.result", ["version": 1, "requestId": id, "feature": "links", "accepted": accepted])
        }
    }

    func openLink() {
        guard available(.links), let link = receivedLink.flatMap(validatedWebLink), let url = URL(string: link) else { return }
        if NSWorkspace.shared.open(url) { receivedLink = nil } else { status = "Could not open your browser" }
    }
    func dismissLink() { receivedLink = nil }
    func reset() { timeout?.cancel(); pending = nil; receivedLink = nil; status = nil }
}
