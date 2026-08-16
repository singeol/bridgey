import AppKit
import SwiftUI

@MainActor
final class FileDropWindowController: NSWindowController {
    init(pairing: PairingCoordinator) {
        let root = FileDropView(pairing: pairing)
        let window = NSWindow(contentViewController: NSHostingController(rootView: root))
        window.title = "Bridgey File Drop"
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.setContentSize(NSSize(width: 460, height: 280))
        window.minSize = NSSize(width: 400, height: 240)
        window.isReleasedWhenClosed = false
        window.hidesOnDeactivate = false
        window.level = .floating
        window.collectionBehavior.insert(.canJoinAllSpaces)
        window.setFrameAutosaveName("BridgeyFileDropWindow")
        window.center()
        super.init(window: window)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }
}

private struct FileDropView: View {
    @ObservedObject var pairing: PairingCoordinator
    @State private var isTargeted = false

    private var canSend: Bool {
        guard case .connected = pairing.state else { return false }
        return pairing.isFeatureAvailable(.files)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L10n.text("drop.title", fallback: "Drop a file here to send"))
                .font(.title2.weight(.semibold))
            Text(L10n.text("drop.detail", fallback: "This window stays open while you switch to Finder."))
                .foregroundStyle(.secondary)

            VStack(spacing: 10) {
                Image(systemName: isTargeted ? "arrow.down.doc.fill" : "doc.badge.arrow.up")
                    .font(.system(size: 36))
                    .foregroundStyle(canSend ? Color.accentColor : Color.secondary)
                Text(canSend
                     ? L10n.text("drop.ready", fallback: "Drop one file anywhere in this area")
                     : L10n.text("drop.unavailable", fallback: "Connect an Android device and enable File transfer first."))
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(24)
            .background(
                (isTargeted ? Color.accentColor.opacity(0.16) : Color.accentColor.opacity(0.07)),
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(
                        Color.accentColor.opacity(canSend ? 0.6 : 0.25),
                        style: StrokeStyle(lineWidth: 2, dash: [7])
                    )
            )
            .dropDestination(for: URL.self) { urls, _ in
                guard canSend, let url = urls.first else { return false }
                return pairing.sendDroppedFile(url)
            } isTargeted: { isTargeted = $0 }
            .accessibilityLabel("File drop area")
            .accessibilityHint("Drop one file to send it to the connected Android device")
        }
        .padding(24)
        .frame(minWidth: 400, minHeight: 240)
    }
}
