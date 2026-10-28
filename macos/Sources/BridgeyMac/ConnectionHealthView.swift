import SwiftUI

/// Connection health rows for Settings > Diagnostics. No addresses, names or identifiers.
struct ConnectionHealthView: View {
    let health: ConnectionHealthSnapshot

    var body: some View {
        LabeledContent(L10n.text("health.title", fallback: "Connection health"), value: health.connected
            ? String(format: L10n.text("health.connected", fallback: "Connected over %@"), health.route.title)
            : L10n.text("health.offline", fallback: "Not connected"))
        if health.connected {
            if let openedHere = health.openedHere {
                Text(openedHere ? L10n.text("health.opened.mac", fallback: "This Mac opened the connection")
                    : L10n.text("health.opened.phone", fallback: "The phone opened the connection"))
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let since = health.connectedSince {
                Text(String(format: L10n.text("health.since", fallback: "Connected since %@"),
                            since.formatted(date: .omitted, time: .shortened)))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Text(roundTripText).font(.caption).foregroundStyle(.secondary)
        }
        if let reason = health.lastDisconnect, let at = health.lastDisconnectAt {
            Text(String(format: L10n.text("health.lastDrop", fallback: "Last disconnect: %@ at %@"),
                        reason.title, at.formatted(date: .omitted, time: .shortened)))
                .font(.caption).foregroundStyle(.secondary)
        }
        Text(String(format: L10n.text("health.attempts", fallback: "Connection attempts today: %d"), health.attemptsToday))
            .font(.caption).foregroundStyle(.secondary)
    }

    private var roundTripText: String {
        guard let last = health.lastRoundTrip, let median = health.medianRoundTrip else {
            return L10n.text("health.rtt.waiting", fallback: "Measuring the round trip…")
        }
        return String(format: L10n.text("health.rtt", fallback: "Round trip %d ms (typical %d ms)"),
                      Int((last * 1000).rounded()), Int((median * 1000).rounded()))
    }
}
