import AppKit
import SwiftUI

@main
struct BridgeyApp: App {
    @StateObject private var discovery: BonjourDiscovery
    @StateObject private var pairing: PairingCoordinator

    init() {
        LegacyPreferences.migrateIfNeeded()
        let discovery = BonjourDiscovery()
        _discovery = StateObject(wrappedValue: discovery)
        let pairing = PairingCoordinator(deviceID: discovery.localDeviceID, deviceName: discovery.localDeviceName)
        pairing.observe(discovery)
        _pairing = StateObject(wrappedValue: pairing)
    }

    var body: some Scene {
        MenuBarExtra("Bridgey", systemImage: menuBarSymbol) {
            BridgeyPanel(discovery: discovery, pairing: pairing)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(pairing: pairing)
        }
    }

    private var menuBarSymbol: String {
        if case .connected = pairing.state { return "link.circle.fill" }
        return "link.circle"
    }
}

private struct BridgeyPanel: View {
    @ObservedObject var discovery: BonjourDiscovery
    @ObservedObject var pairing: PairingCoordinator

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

            switch pairing.state {
            case let .connected(_, name): connectedCard(name: name)
            case let .connecting(name): statusCard(title: "Connecting to \(name)", detail: "Establishing a secure session…", progress: true)
            case let .verification(name, code): verificationCard(name: name, code: code)
            case let .failed(message): failureCard(message)
            case .idle: nearbyDevices
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
                Button { NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil) } label: {
                    Image(systemName: "gearshape")
                }
                .buttonStyle(.borderless)
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
                    if let battery = pairing.remoteBattery {
                        Label(
                            "\(battery.level)%\(battery.isCharging ? " · Charging" : "")",
                            systemImage: battery.isCharging ? "battery.100percent.bolt" : batterySymbol(battery.level)
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    } else {
                        Text("Android device").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button { pairing.dismiss() } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .disabled(pairing.fileTransferActive)
                    .help("Disconnect")
            }

            HStack(spacing: 8) {
                actionButton("Clipboard", icon: "doc.on.clipboard") { pairing.sendClipboard() }
                actionButton("File", icon: "paperplane") { pairing.chooseAndSendFile() }
                actionButton(
                    pairing.macRinging || pairing.androidRinging ? "Stop" : "Ring",
                    icon: pairing.macRinging || pairing.androidRinging ? "stop.circle" : "bell"
                ) {
                    if pairing.macRinging || pairing.androidRinging { pairing.stopFinding() }
                    else { pairing.findAndroid() }
                }
            }

            if let status = pairing.clipboardStatus {
                Label(status, systemImage: status == "Delivered" ? "checkmark.circle.fill" : "clock")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if !pairing.notificationsAuthorized {
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
            Button("Dismiss") { pairing.cancel() }
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
}

private struct SettingsView: View {
    @ObservedObject var pairing: PairingCoordinator

    var body: some View {
        Form {
            Section("Bridgey") {
                LabeledContent("Device", value: Host.current().localizedName ?? "Mac")
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Development")
            }
            Section("Features") {
                Label("Clipboard sharing", systemImage: "doc.on.clipboard")
                Label("File transfer", systemImage: "arrow.left.arrow.right")
                HStack {
                    Label("Android notifications", systemImage: "bell")
                    Spacer()
                    if pairing.notificationsAuthorized {
                        Text("Enabled").foregroundStyle(.secondary)
                    } else {
                        Button(pairing.notificationPermissionDetermined ? "Settings" : "Enable") {
                            pairing.enableNotifications()
                        }
                    }
                }
                Label("Find device", systemImage: "location.magnifyingglass")
            }
            Text("Bridgey communicates directly over your local network. Clipboard, files, and notification content are encrypted in transit.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .padding(10)
        .frame(width: 480, height: 360)
        .onAppear { pairing.refreshNotificationAuthorization() }
    }
}
