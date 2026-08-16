import AppKit
import SwiftUI

@MainActor
final class FileTransferWindowController: NSWindowController {
    init(pairing: PairingCoordinator) {
        let root = FileTransferView(pairing: pairing)
        let window = NSWindow(contentViewController: NSHostingController(rootView: root))
        window.title = "Bridgey File Transfer"
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.setContentSize(NSSize(width: 620, height: 360))
        window.minSize = NSSize(width: 520, height: 260)
        window.isReleasedWhenClosed = false
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

private struct FileTransferView: View {
    @ObservedObject var pairing: PairingCoordinator

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("File Transfer").font(.title2).fontWeight(.semibold)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    if pairing.fileTransfers.isEmpty {
                        Text("No recent file transfers").foregroundStyle(.secondary)
                    } else {
                        ForEach(pairing.fileTransfers.values.sorted(by: { $0.startedAt > $1.startedAt })) { transfer in
                            HStack(alignment: .center, spacing: 12) {
                                if transfer.active { ProgressView().controlSize(.small) }
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(transfer.name).fontWeight(.medium)
                                    Text(transfer.status).font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(2)
                                }
                                Spacer()
                                if transfer.active {
                                    Button("Cancel", role: .destructive) {
                                        pairing.cancelFileTransfer(id: transfer.id)
                                    }
                                } else if transfer.retryable {
                                    Button("Retry") { pairing.retryFileTransfer(id: transfer.id) }
                                }
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Divider()
            HStack {
                if pairing.fileTransferActive {
                    Button("Cancel All", role: .destructive) { pairing.cancelFileTransfer() }
                }
                if pairing.fileTransfers.values.contains(where: { !$0.active }) {
                    Button("Clear History") { pairing.clearTransferHistory() }
                }
                Spacer()
                Button("Close") { NSApp.keyWindow?.close() }
            }
        }
        .padding(24)
        .frame(minWidth: 500, minHeight: 240)
    }
}
