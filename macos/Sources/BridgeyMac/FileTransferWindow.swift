import AppKit
import QuickLookUI
import SwiftUI

@MainActor
final class FileTransferWindowController: NSWindowController {
    private let quickLookSource = ReceivedFileQuickLookSource()

    init(pairing: PairingCoordinator) {
        var showQuickLook: (URL) -> Void = { _ in }
        let root = FileTransferView(pairing: pairing, quickLook: { showQuickLook($0) })
        let window = NSWindow(contentViewController: NSHostingController(rootView: root))
        window.title = "Bridgey File Transfer"
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.setContentSize(NSSize(width: 620, height: 360))
        window.minSize = NSSize(width: 520, height: 260)
        window.isReleasedWhenClosed = false
        window.center()
        super.init(window: window)
        showQuickLook = { [weak self] in self?.showQuickLook($0) }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }

    private func showQuickLook(_ url: URL) {
        quickLookSource.items = [url]
        guard let panel = QLPreviewPanel.shared() else { return }
        if QLPreviewPanel.sharedPreviewPanelExists(), panel.isVisible, panel.dataSource === quickLookSource {
            panel.reloadData()
        } else {
            window?.makeKeyAndOrderFront(nil)
            panel.makeKeyAndOrderFront(nil)
        }
    }

    // The window controller is in the responder chain, so it can take over the shared Quick Look panel.
    override func acceptsPreviewPanelControl(_ panel: QLPreviewPanel!) -> Bool { !quickLookSource.items.isEmpty }

    override func beginPreviewPanelControl(_ panel: QLPreviewPanel!) {
        panel.dataSource = quickLookSource
    }

    override func endPreviewPanelControl(_ panel: QLPreviewPanel!) {
        if panel.dataSource === quickLookSource { panel.dataSource = nil }
    }
}

/// Quick Look's data source. The panel does not retain it, so the window controller keeps it, and the panel
/// may ask from another thread, so the list sits behind a lock.
private final class ReceivedFileQuickLookSource: NSObject, QLPreviewPanelDataSource, @unchecked Sendable {
    private let lock = NSLock()
    private var urls: [URL] = []

    var items: [URL] {
        get { lock.withLock { urls } }
        set { lock.withLock { urls = newValue } }
    }

    func numberOfPreviewItems(in panel: QLPreviewPanel!) -> Int { items.count }

    func previewPanel(_ panel: QLPreviewPanel!, previewItemAt index: Int) -> QLPreviewItem! {
        let list = items
        return list.indices.contains(index) ? list[index] as NSURL : nil
    }
}

private struct FileTransferView: View {
    @ObservedObject var pairing: PairingCoordinator
    let quickLook: (URL) -> Void

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
                                if transfer.active && !transfer.queued { ProgressView().controlSize(.small) }
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(transfer.name).fontWeight(.medium)
                                    Text(transfer.status).font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(2)
                                }
                                Spacer()
                                if transfer.active {
                                    Button("Cancel", role: .destructive) {
                                        pairing.cancelFileTransfer(id: transfer.id)
                                    }
                                } else if let url = transfer.localURL, FileManager.default.fileExists(atPath: url.path) {
                                    Button {
                                        quickLook(url)
                                    } label: {
                                        Image(systemName: "eye")
                                    }
                                    .help(L10n.text("transfers.quickLook", fallback: "Quick Look"))
                                    .accessibilityLabel(L10n.text("transfers.quickLook", fallback: "Quick Look"))
                                    Button(L10n.text("transfers.showInFinder", fallback: "Show in Finder")) {
                                        NSWorkspace.shared.activateFileViewerSelecting([url])
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
