import AppKit
import SwiftUI

/*
 * Settings: remote control status, Accessibility (why and where), how to stop, and the private
 * session log. The switch is in Features and the per-phone grants are under Paired devices.
 */

struct RemoteControlSettingsView: View {
    @ObservedObject var host: RemoteInputHost
    @ObservedObject var audit: RemoteControlAuditLog
    @ObservedObject var settings: BridgeySettings
    @State private var confirmClear = false

    var body: some View {
        Section(L10n.text("remote.section", fallback: "Remote control")) {
            Text(L10n.text("remote.footnote", fallback: "Turn on Remote control in Features, then allow each phone under Paired devices. The phone also needs Remote control turned on in Bridgey. Typing and keys use a short list of keys; shortcuts such as Quit or Lock are never sent."))
                .font(.caption).foregroundStyle(.secondary)
            if let session = host.active {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(format: L10n.text("remote.pill.title", fallback: "%@ is controlling this Mac"), session.deviceName))
                            .fontWeight(.medium)
                        Text(String(format: L10n.text("remote.active.detail", fallback: "%@, since %@"),
                                    remoteModeTitle(session.mode), session.startedAt.formatted(date: .omitted, time: .shortened)))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(L10n.text("remote.stop", fallback: "Stop"), role: .destructive, action: host.stopLocally)
                }
            }
            if settings.globalFeatures[.remoteInput] == true {
                LabeledContent(L10n.text("remote.accessibility", fallback: "Accessibility access")) {
                    if host.accessibilityTrusted {
                        Text(L10n.text("remote.accessibility.allowed", fallback: "Allowed")).foregroundStyle(.secondary)
                    } else {
                        Button(L10n.text("remote.accessibility.open", fallback: "Open System Settings")) {
                            NSWorkspace.shared.open(AccessibilityAccess.settingsURL)
                        }
                    }
                }
                Text(host.accessibilityTrusted
                     ? L10n.text("remote.accessibility.on", fallback: "macOS requires Accessibility for any app that moves the pointer or types. Bridgey uses it only during a session you can see.")
                     : L10n.text("remote.accessibility.off", fallback: "Needed to move the pointer and type. macOS asks when you first allow a phone. If you declined, turn on Bridgey in Privacy & Security, Accessibility."))
                    .font(.caption).foregroundStyle(host.accessibilityTrusted ? Color.secondary : Color.orange)
                LabeledContent(L10n.text("remote.stop.title", fallback: "Stop at any time"),
                               value: L10n.text("remote.stop.shortcut", fallback: "Control-Option-Escape"))
                Text(L10n.text("remote.stop.detail", fallback: "Or press Stop in the pill at the top of the screen. The pill shows for the whole session."))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        Section(L10n.text("remote.log", fallback: "Remote control log")) {
            Text(L10n.text("remote.log.detail", fallback: "Kept only on this Mac, for 30 days. It records which phone controlled this Mac, when, for how long and how many moves, clicks and keys, never what was typed."))
                .font(.caption).foregroundStyle(.secondary)
            if audit.entries.isEmpty {
                Text(L10n.text("remote.log.empty", fallback: "No remote control yet")).foregroundStyle(.secondary)
            } else {
                ForEach(Array(audit.entries.prefix(10))) { entry in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(entry.deviceName).fontWeight(.medium)
                            Spacer()
                            Text(entry.startedAt, format: .dateTime.day().month().hour().minute())
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Text(remoteAuditDetail(entry))
                            .font(.caption)
                            .foregroundStyle(entry.accepted ? Color.secondary : Color.orange)
                    }
                }
                if audit.entries.count > 10 {
                    Text(String(format: L10n.text("remote.log.more", fallback: "%d more in the last 30 days"), audit.entries.count - 10))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Button(L10n.text("remote.log.clear", fallback: "Clear log"), role: .destructive) { confirmClear = true }
            }
        }
        .confirmationDialog(L10n.text("remote.log.clear.title", fallback: "Clear the remote control log?"), isPresented: $confirmClear) {
            Button(L10n.text("remote.log.clear", fallback: "Clear log"), role: .destructive, action: audit.clear)
        } message: {
            Text(L10n.text("remote.log.clear.body", fallback: "This removes the record of past remote control sessions from this Mac."))
        }
        .onAppear {
            host.refreshAccessibility()
            audit.prune()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            host.refreshAccessibility()
        }
    }
}

func remoteModeTitle(_ mode: RemoteInputMode) -> String {
    switch mode {
    case .touchpad: L10n.text("remote.mode.touchpad", fallback: "Touchpad")
    case .presentation: L10n.text("remote.mode.presentation", fallback: "Presentation")
    }
}

/// "2 min 5 s · Moves: 2140 · Clicks: 36 · Stopped on the phone", or why a start was refused.
func remoteAuditDetail(_ entry: RemoteControlAuditEntry) -> String {
    let reason = remoteAuditReasonText(entry)
    guard entry.accepted else { return reason }
    var parts = [remoteDurationText(entry.duration)]
    let counts = remoteAuditCountsText(entry.counts)
    if !counts.isEmpty { parts.append(counts) }
    parts.append(reason)
    return parts.joined(separator: " \u{00B7} ")
}

/// How a session ended, or why a start was refused, in plain words.
func remoteAuditReasonText(_ entry: RemoteControlAuditEntry) -> String {
    switch entry.reason {
    case .user: L10n.text("remote.reason.user", fallback: "Stopped on the phone")
    case .leftScreen: L10n.text("remote.reason.leftScreen", fallback: "Closed on the phone")
    case .screenOff: L10n.text("remote.reason.screenOff", fallback: "Phone screen turned off")
    case .localStop: L10n.text("remote.reason.localStop", fallback: "Stopped on this Mac")
    case .timeout: L10n.text("remote.reason.timeout", fallback: "Ended after 6 seconds without input")
    case .disabled: entry.accepted
        ? L10n.text("remote.reason.disabled", fallback: "Ended: permission turned off")
        : L10n.text("remote.reason.refusedDisabled", fallback: "Refused: not allowed for this phone")
    case .permissionRequired: entry.accepted
        ? L10n.text("remote.reason.permissionLost", fallback: "Ended: Accessibility access was removed")
        : L10n.text("remote.reason.refusedPermission", fallback: "Refused: Accessibility access needed")
    case .busy: L10n.text("remote.reason.busy", fallback: "Refused: another phone was in control")
    case .invalid: L10n.text("remote.reason.invalid", fallback: "Refused: the request was not valid")
    case .unsupported: L10n.text("remote.reason.unsupported", fallback: "Refused: not supported")
    case .disconnected: L10n.text("remote.reason.disconnected", fallback: "Connection lost")
    case .replaced: L10n.text("remote.reason.replaced", fallback: "Restarted from the phone")
    case nil: entry.endReason
    }
}

/// "Moves: 2140 · Clicks: 36": only types that happened, in a fixed order.
func remoteAuditCountsText(_ counts: [String: Int]) -> String {
    let labels: [(RemoteInputEventType, String)] = [
        (.pointer, L10n.text("remote.count.pointer", fallback: "Moves: %d")),
        (.button, L10n.text("remote.count.button", fallback: "Clicks: %d")),
        (.scroll, L10n.text("remote.count.scroll", fallback: "Scrolls: %d")),
        (.drag, L10n.text("remote.count.drag", fallback: "Drags: %d")),
        (.key, L10n.text("remote.count.key", fallback: "Keys: %d")),
        (.text, L10n.text("remote.count.text", fallback: "Texts: %d")),
        (.present, L10n.text("remote.count.present", fallback: "Slide actions: %d")),
    ]
    return labels.compactMap { type, format in
        counts[type.rawValue].flatMap { $0 > 0 ? String(format: format, $0) : nil }
    }.joined(separator: " \u{00B7} ")
}

func remoteDurationText(_ seconds: TimeInterval) -> String {
    let formatter = DateComponentsFormatter()
    formatter.unitsStyle = .abbreviated
    formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : seconds >= 60 ? [.minute, .second] : [.second]
    formatter.maximumUnitCount = 2
    return formatter.string(from: max(1, seconds.rounded())) ?? ""
}
