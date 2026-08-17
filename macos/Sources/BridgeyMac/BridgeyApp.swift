import AppKit
import SwiftUI

@main
struct BridgeyApp: App {
    @StateObject private var discovery: BonjourDiscovery
    @StateObject private var pairing: PairingCoordinator
    @StateObject private var settings: BridgeySettings
    private let settingsWindow: SettingsWindowController
    private let callServiceProvider: CallServiceProvider

    init() {
        LegacyPreferences.migrateIfNeeded()
        let settings = BridgeySettings()
        _settings = StateObject(wrappedValue: settings)
        let discovery = BonjourDiscovery(deviceName: settings.deviceName)
        _discovery = StateObject(wrappedValue: discovery)
        let pairing = PairingCoordinator(deviceID: discovery.localDeviceID, deviceName: discovery.localDeviceName, settings: settings)
        pairing.observe(discovery)
        _pairing = StateObject(wrappedValue: pairing)
        let callServiceProvider = CallServiceProvider { [weak pairing] number in pairing?.sendCall(number) }
        self.callServiceProvider = callServiceProvider
        NSApplication.shared.servicesProvider = callServiceProvider
        settingsWindow = SettingsWindowController(discovery: discovery, pairing: pairing, settings: settings)
    }

    var body: some Scene {
        MenuBarExtra("Bridgey", systemImage: menuBarSymbol) {
            BridgeyPanel(
                discovery: discovery,
                pairing: pairing,
                settings: settings,
                onOpenSettings: settingsWindow.show
            )
        }
        .menuBarExtraStyle(.window)
    }

    private var menuBarSymbol: String {
        if case .connected = pairing.state { return "link.circle.fill" }
        return "link.circle"
    }
}

private struct BridgeyPanel: View {
    @ObservedObject var discovery: BonjourDiscovery
    @ObservedObject var pairing: PairingCoordinator
    @ObservedObject var settings: BridgeySettings
    let onOpenSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .frame(width: 34, height: 34)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text("Bridgey").font(.headline)
                    Text(headerSubtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Circle()
                    .fill(isConnected ? Color.green : Color.secondary.opacity(0.45))
                    .frame(width: 8, height: 8)
            }

            if !settings.hasCompletedOnboarding {
                welcomeCard
            } else {
                switch pairing.state {
                case let .connected(_, name): connectedCard(name: name)
                case let .connecting(name): statusCard(title: "Connecting to \(name)", detail: "Establishing a secure session…", progress: true)
                case let .verification(name, code): verificationCard(name: name, code: code)
                case let .failed(message): failureCard(message)
                case .idle: nearbyDevices
                }
            }

            if !pairing.fileTransfers.isEmpty {
                Divider()
                Button { pairing.showFileTransferWindow() } label: {
                    Label("File transfers", systemImage: "arrow.left.arrow.right.circle")
                    Spacer()
                    Text("\(pairing.fileTransfers.count)").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            Divider()
            HStack {
                Button(action: onOpenSettings) {
                    Label("Settings", systemImage: "gearshape")
                }
                .buttonStyle(.plain)
                .help("Settings")
                Spacer()
                Button("Quit Bridgey") { NSApplication.shared.terminate(nil) }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
            .font(.caption)
        }
        .padding(16)
        .frame(width: 340)
        .onAppear { pairing.refreshNotificationAuthorization() }
    }

    private var isConnected: Bool {
        if case .connected = pairing.state { return true }
        return false
    }

    private var headerSubtitle: String {
        if case .connected = pairing.state { return "Connected securely" }
        return "Ready on your local network"
    }

    @ViewBuilder
    private func connectedCard(name: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Color.accentColor.gradient)
                    Image(systemName: "smartphone")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(.white)
                }
                .frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(.headline).lineLimit(1)
                    if pairing.isFeatureAvailable(.battery) {
                        if let battery = pairing.remoteBattery {
                            Label(
                                "\(battery.level)%\(battery.isCharging ? " · Charging" : "")",
                                systemImage: battery.isCharging ? "battery.100percent.bolt" : batterySymbol(battery.level)
                            )
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        } else {
                            Text("Waiting for battery status…").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer()
                Button { pairing.dismiss() } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .disabled(pairing.fileTransferActive)
                    .help("Disconnect")
            }

            if let call = pairing.remoteCall {
                VStack(alignment: .leading, spacing: 8) {
                    Label(remoteCallStatusTitle(call.type), systemImage: "phone.arrow.down.left.fill")
                        .font(.headline)
                        .foregroundStyle(Color.accentColor)
                    Text(call.caller.isEmpty ? call.applicationName : call.caller)
                        .font(.title3).fontWeight(.semibold).lineLimit(2)
                    if !call.detail.isEmpty && call.detail != call.caller {
                        Text(call.detail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                    if !call.actions.isEmpty {
                        HStack(spacing: 6) {
                            ForEach(Array(call.actions.prefix(3))) { action in
                                Button(action.title) { pairing.performRemoteCallAction(action) }
                                    .buttonStyle(.bordered)
                                    .lineLimit(1)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                    } else {
                        Text("Use the phone to control this call.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(12)
                .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            LazyVGrid(columns: quickActionColumns, spacing: 8) {
                if pairing.isFeatureAvailable(.clipboard) {
                    actionButton("Clipboard", icon: "doc.on.clipboard") { pairing.sendClipboard() }
                }
                if pairing.isFeatureAvailable(.files) {
                    actionButton("File", icon: "paperplane") { pairing.chooseAndSendFile() }
                }
                if pairing.isFeatureAvailable(.findDevice) {
                    actionButton(
                        pairing.macRinging || pairing.androidRinging ? "Stop" : "Ring",
                        icon: pairing.macRinging || pairing.androidRinging ? "stop.circle" : "bell"
                    ) {
                        if pairing.macRinging || pairing.androidRinging { pairing.stopFinding() }
                        else { pairing.findAndroid() }
                    }
                }
                if pairing.isFeatureAvailable(.calls) {
                    actionButton("Call", icon: "phone.arrow.up.right") { pairing.sendCallFromClipboard() }
                        .help("Call the phone number currently in the clipboard (⌃⌥P)")
                }
            }
            if pairing.isFeatureAvailable(.files) {
                Button { pairing.showFileDropWindow() } label: {
                    Label(
                        L10n.text("drop.open", fallback: "Open file drop window"),
                        systemImage: "rectangle.on.rectangle.angled"
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(
                        Color.accentColor.opacity(0.08),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .strokeBorder(Color.accentColor.opacity(0.3), style: StrokeStyle(lineWidth: 1, dash: [4]))
                    )
                    .accessibilityHint("Opens a window that stays visible while you drag a file from Finder")
            }
            if !pairing.isFeatureAvailable(.clipboard) &&
                !pairing.isFeatureAvailable(.files) &&
                !pairing.isFeatureAvailable(.findDevice) &&
                !pairing.isFeatureAvailable(.calls) {
                Text("Quick actions are turned off in Settings on one of your devices.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let status = pairing.clipboardStatus {
                Label(status, systemImage: status == "Delivered" ? "checkmark.circle.fill" : "clock")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let status = pairing.callStatus {
                Label(status, systemImage: status == "Call started on Android" ? "phone.fill" : "phone.badge.clock")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if pairing.isFeatureAvailable(.notifications) && !pairing.notificationsAuthorized {
                Button { pairing.enableNotifications() } label: {
                    Label(
                        pairing.notificationPermissionDetermined ? "Open notification settings" : "Enable Mac notifications",
                        systemImage: "bell.badge"
                    )
                }
                .buttonStyle(.plain)
                .font(.caption)
                .foregroundStyle(Color.accentColor)
            }
        }
        .padding(14)
        .background(.quaternary.opacity(0.55), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var welcomeCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(L10n.text("welcome.title", fallback: "Welcome to Bridgey"), systemImage: "link.circle.fill")
                .font(.headline)
            Text(L10n.text(
                "welcome.body",
                fallback: "Keep your Mac and phone on the same local network. Choose a device, compare the pairing code, and confirm it on both devices. Optional permissions are requested only when you use the related feature."
            ))
            .font(.caption)
            .foregroundStyle(.secondary)
            Button(L10n.text("welcome.continue", fallback: "Get started")) {
                settings.completeOnboarding()
            }
            .buttonStyle(.borderedProminent)
            .accessibilityHint("Closes the welcome guide and starts device discovery")
        }
        .padding(14)
        .background(.quaternary.opacity(0.55), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func actionButton(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 16, weight: .medium))
                Text(title).font(.caption2)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(.background.opacity(0.8), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityLabel(title)
        .accessibilityHint("Runs the \(title.lowercased()) action for the connected device")
    }

    @ViewBuilder
    private var nearbyDevices: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("Nearby devices").font(.subheadline.weight(.semibold))
            if discovery.peers.isEmpty {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Looking for Android devices")
                        Text("Keep both devices on the same Wi‑Fi network.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 8)
            } else {
                ForEach(discovery.peers) { peer in
                    Button {
                        if let host = peer.host, let port = peer.port {
                            pairing.pair(host: host, port: port, peerName: peer.deviceNameHint)
                        }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "smartphone").frame(width: 24)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(peer.deviceNameHint).lineLimit(1)
                                Text(peer.deviceIDHint.map(pairing.trustedDeviceIDs.contains) == true ? "Paired · click to reconnect" : "Click to pair")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 4)
                }
            }
        }
    }

    private func statusCard(title: String, detail: String, progress: Bool) -> some View {
        HStack(spacing: 12) {
            if progress { ProgressView().controlSize(.small) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).fontWeight(.medium)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.55), in: RoundedRectangle(cornerRadius: 14))
    }

    private func verificationCard(name: String, code: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Verify \(name)").font(.headline)
            Text(code).font(.system(size: 32, weight: .semibold, design: .monospaced))
            Text("Confirm only if the same code appears on both devices.").font(.caption).foregroundStyle(.secondary)
            HStack { Button("Codes Match") { pairing.confirm() }.buttonStyle(.borderedProminent); Button("Cancel") { pairing.cancel() } }
        }
        .padding(14).background(.quaternary.opacity(0.55), in: RoundedRectangle(cornerRadius: 14))
    }

    private func failureCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Couldn’t connect", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red)
            Text(message).font(.caption).foregroundStyle(.secondary)
            HStack {
                if message.contains("Local Network") {
                    Button("Open Local Network Settings") {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork")!)
                    }
                }
                Button("Dismiss") { pairing.cancel() }
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.55), in: RoundedRectangle(cornerRadius: 14))
    }

    private func batterySymbol(_ level: Int) -> String {
        switch level {
        case 76...100: "battery.100percent"
        case 51...75: "battery.75percent"
        case 26...50: "battery.50percent"
        case 1...25: "battery.25percent"
        default: "battery.0percent"
        }
    }

    private var quickActionColumns: [GridItem] {
        [GridItem(.flexible(), spacing: 8), GridItem(.flexible())]
    }
}

@MainActor
private final class SettingsWindowController {
    private let discovery: BonjourDiscovery
    private let pairing: PairingCoordinator
    private let settings: BridgeySettings
    private var window: NSWindow?

    init(discovery: BonjourDiscovery, pairing: PairingCoordinator, settings: BridgeySettings) {
        self.discovery = discovery
        self.pairing = pairing
        self.settings = settings
    }

    func show() {
        if window == nil {
            let content = SettingsView(discovery: discovery, pairing: pairing, settings: settings)
            let created = NSWindow(contentViewController: NSHostingController(rootView: content))
            created.title = "Bridgey Settings"
            created.styleMask = [.titled, .closable, .miniaturizable]
            created.setContentSize(NSSize(width: 600, height: 680))
            created.isReleasedWhenClosed = false
            created.setFrameAutosaveName("BridgeySettingsWindow")
            created.center()
            window = created
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

private struct SettingsView: View {
    @ObservedObject var discovery: BonjourDiscovery
    @ObservedObject var pairing: PairingCoordinator
    @ObservedObject var settings: BridgeySettings
    @State private var editedName = ""

    var body: some View {
        Form {
            Section("Bridgey") {
                HStack {
                    TextField("Device name", text: $editedName)
                    Button("Save") {
                        settings.setDeviceName(editedName)
                        pairing.updateDeviceName(settings.deviceName)
                        discovery.updateDeviceName(settings.deviceName)
                    }
                    .disabled(editedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || editedName == settings.deviceName)
                }
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Development")
                Toggle("Launch at login", isOn: Binding(
                    get: { settings.launchAtLogin },
                    set: { settings.setLaunchAtLogin($0) }
                ))
                if let message = settings.loginItemMessage { Text(message).font(.caption).foregroundStyle(.red) }
                HStack {
                    LabeledContent("Received files", value: settings.receiveFolderPath)
                    Button("Choose…") { settings.chooseReceiveFolder() }
                }
            }
            Section("Features") {
                Text("These switches control what this Mac shares with every paired device. Changes appear on a connected device immediately.")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(BridgeyFeature.allCases) { feature in
                    Toggle(feature.title, isOn: Binding(
                        get: { settings.globalFeatures[feature] != false },
                        set: {
                            settings.setGlobal(feature, enabled: $0)
                            if feature == .findDevice && !$0 { pairing.stopFinding() }
                        }
                    ))
                }
                if settings.globalFeatures[.notifications] != false && !pairing.notificationsAuthorized {
                    Button(pairing.notificationPermissionDetermined ? "Open notification settings" : "Enable Mac notifications") {
                        pairing.enableNotifications()
                    }
                }
            }
            Section("Notification history") {
                Toggle("Keep a private local history", isOn: Binding(
                    get: { settings.notificationHistoryEnabled },
                    set: { settings.setNotificationHistoryEnabled($0) }
                ))
                Text("Off by default. When enabled, Bridgey keeps at most 200 notifications for 7 days on this Mac. Turning it off deletes the history.")
                    .font(.caption).foregroundStyle(.secondary)
                if settings.notificationHistoryEnabled {
                    if pairing.notificationHistory.isEmpty {
                        Text("No forwarded notifications yet.").foregroundStyle(.secondary)
                    } else {
                        ForEach(Array(pairing.notificationHistory.prefix(20))) { item in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(item.applicationName).font(.headline)
                                    Spacer()
                                    Text(item.receivedAt, format: .dateTime.day().month().hour().minute())
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                if !item.title.isEmpty { Text(item.title).fontWeight(.medium) }
                                if !item.text.isEmpty {
                                    Text(item.text).lineLimit(3).foregroundStyle(.secondary)
                                }
                            }
                            .textSelection(.enabled)
                        }
                        Button("Clear history", role: .destructive) { pairing.clearNotificationHistory() }
                    }
                }
            }
            Section("Paired devices") {
                if pairing.trustedDevices.isEmpty { Text("No paired devices").foregroundStyle(.secondary) }
                ForEach(pairing.trustedDevices) { device in
                    DisclosureGroup(device.name) {
                        Text("Choose what this Mac may use with this device.")
                            .font(.caption).foregroundStyle(.secondary)
                        ForEach(BridgeyFeature.allCases) { feature in
                            Toggle(isOn: Binding(
                                get: {
                                    settings.globalFeatures[feature] != false &&
                                        settings.deviceFeatures[device.id]?[feature] != false
                                },
                                set: {
                                    settings.setForDevice(device.id, feature: feature, enabled: $0)
                                    if feature == .findDevice && !$0,
                                       case let .connected(connectedID, _) = pairing.state,
                                       connectedID == device.id { pairing.stopFinding() }
                                }
                            )) {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(feature.title)
                                    if connectedDeviceID == device.id && pairing.remoteFeatures[feature] == false {
                                        Text("Off on \(device.name)").font(.caption2).foregroundStyle(.orange)
                                    }
                                }
                            }
                            .disabled(settings.globalFeatures[feature] == false)
                        }
                        Button("Forget device", role: .destructive) { pairing.forget(deviceID: device.id) }
                    }
                }
            }
            Section("Diagnostics") {
                Text("Exports a bounded event log without clipboard text, notification content, file names, addresses, or device identifiers.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Export Diagnostics…") { pairing.exportDiagnostics() }
            }
            Text("Bridgey communicates directly over your local network. Clipboard, files, and notification content are encrypted in transit.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .padding(10)
        .frame(width: 560, height: 620)
        .onAppear {
            editedName = settings.deviceName
            pairing.refreshNotificationAuthorization()
            settings.refreshLoginItemStatus()
        }
    }

    private var connectedDeviceID: String? {
        if case let .connected(deviceID, _) = pairing.state { return deviceID }
        return nil
    }
}
