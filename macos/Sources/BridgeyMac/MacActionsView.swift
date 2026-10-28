import AppKit
import SwiftUI

struct MacActionsSettingsView: View {
    @ObservedObject var actions: MacActions
    let featureEnabled: Bool

    var body: some View {
        Section(L10n.text("settings.macActions", fallback: "Mac actions")) {
            Text(L10n.text("actions.footnote", fallback: "Turn on Mac actions in Features above, then choose what the phone may do. The phone can only pick from this list; it never sends commands, and Bridgey never runs anything else."))
                .font(.caption).foregroundStyle(.secondary)
            ForEach(MacActionGroup.allCases) { group in
                Toggle(group.title, isOn: Binding(
                    get: { actions.config.groups.contains(group) },
                    set: { actions.setGroup(group, enabled: $0) }
                ))
            }
            if actions.config.groups.contains(.lock) && !actions.accessibilityTrusted {
                Text(L10n.text("actions.lock.accessibility", fallback: "Lock screen presses the macOS Lock Screen shortcut, which needs Accessibility access. Turn on Bridgey in Privacy & Security > Accessibility."))
                    .font(.caption).foregroundStyle(.orange)
                Button(L10n.text("actions.accessibility.open", fallback: "Open Accessibility Settings")) {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
            LabeledContent(L10n.text("actions.apps", fallback: "Apps the phone can open")) {
                Button(L10n.text("actions.apps.add", fallback: "Add App…"), action: actions.chooseApps)
                    .disabled(actions.config.apps.count >= MacActionsConfig.maximumApps)
            }
            ForEach(actions.config.apps) { app in
                HStack {
                    Text(app.name)
                    Spacer()
                    Button(L10n.text("actions.remove", fallback: "Remove")) { actions.removeApp(app.id) }
                }
            }
            LabeledContent(L10n.text("actions.shortcuts", fallback: "Shortcuts the phone can run")) {
                Menu(L10n.text("actions.shortcuts.add", fallback: "Add Shortcut")) {
                    let added = Set(actions.config.shortcuts.map(\.name))
                    let choices = (actions.availableShortcuts ?? []).filter { !added.contains($0) }
                    if actions.availableShortcuts == nil {
                        Text(L10n.text("actions.shortcuts.loading", fallback: "Loading Shortcuts…"))
                    } else if choices.isEmpty {
                        Text(L10n.text("actions.shortcuts.none", fallback: "No other Shortcuts"))
                    }
                    ForEach(choices.prefix(200), id: \.self) { name in
                        Button(macActionTitle(name)) { actions.addShortcut(named: name) }
                    }
                }
                .fixedSize()
                .disabled(actions.config.shortcuts.count >= MacActionsConfig.maximumShortcuts)
            }
            ForEach(actions.config.shortcuts) { shortcut in
                HStack {
                    Text(macActionTitle(shortcut.name))
                    Spacer()
                    Button(L10n.text("actions.remove", fallback: "Remove")) { actions.removeShortcut(shortcut.id) }
                }
            }
            Text(L10n.text("actions.shortcuts.detail", fallback: "Shortcuts run with no input from the phone, one at a time, for up to a minute. The phone sees the names of the apps and Shortcuts listed here."))
                .font(.caption).foregroundStyle(.secondary)
            if !actions.recent.isEmpty {
                ForEach(actions.recent) { entry in
                    HStack {
                        Text(entry.title)
                        Spacer()
                        Text(macActionOutcomeText(entry.refusal))
                            .foregroundStyle(entry.refusal == nil ? Color.secondary : Color.orange)
                        Text(entry.at, format: .dateTime.hour().minute())
                            .foregroundStyle(.secondary)
                    }
                    .font(.caption)
                }
                Button(L10n.text("actions.recent.clear", fallback: "Clear recent requests")) { actions.clearRecent() }
            }
        }
        .disabled(!featureEnabled)
        .onAppear { if actions.availableShortcuts == nil { actions.loadShortcuts() } }
    }
}

func macActionOutcomeText(_ refusal: MacActionRefusal?) -> String {
    switch refusal {
    case nil: L10n.text("actions.outcome.done", fallback: "Done")
    case .notAllowed?: L10n.text("actions.outcome.notAllowed", fallback: "Refused: not allowed")
    case .invalid?: L10n.text("actions.outcome.invalid", fallback: "Refused: invalid request")
    case .rateLimited?: L10n.text("actions.outcome.rateLimited", fallback: "Refused: too many at once")
    case .busy?: L10n.text("actions.outcome.busy", fallback: "Refused: a Shortcut was still running")
    case .permissionRequired?: L10n.text("actions.outcome.permission", fallback: "Refused: needs Accessibility")
    case .unavailable?: L10n.text("actions.outcome.unavailable", fallback: "Not available on this Mac")
    case .failed?: L10n.text("actions.outcome.failed", fallback: "Failed")
    }
}
