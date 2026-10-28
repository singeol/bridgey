import AppKit
import SwiftUI

@MainActor
final class PhoneFolderBrowserWindowController: NSWindowController {
    private let browser: PhoneFolderBrowser

    init(browser: PhoneFolderBrowser) {
        self.browser = browser
        let window = NSWindow(contentViewController: NSHostingController(rootView: PhoneFolderBrowserView(browser: browser)))
        window.title = L10n.text("browse.window.title", fallback: "Android Shared Folders")
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(NSSize(width: 680, height: 460))
        window.minSize = NSSize(width: 520, height: 320)
        window.isReleasedWhenClosed = false
        window.center()
        super.init(window: window)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
        browser.refreshRoots()
    }
}

private struct PhoneFolderBrowserView: View {
    @ObservedObject var browser: PhoneFolderBrowser

    var body: some View {
        HStack(spacing: 0) {
            List(browser.roots) { root in
                Button { browser.open(root: root) } label: {
                    Label(root.name, systemImage: "folder")
                        .fontWeight(browser.rootID == root.id ? .semibold : .regular)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
            }
            .frame(width: 190)
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                breadcrumb
                if browser.rootID == nil {
                    Text(L10n.text("browse.pick", fallback: "Choose a shared folder."))
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(browser.entries) { entry in
                        row(entry)
                    }
                }
                footer
            }
            .padding(14)
        }
        .frame(minWidth: 520, minHeight: 320)
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            if let rootName = browser.rootName {
                Button(rootName) { browser.goTo(depth: 0) }
                    .buttonStyle(.link)
                ForEach(Array(browser.path.enumerated()), id: \.offset) { index, name in
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                    Button(name) { browser.goTo(depth: index + 1) }
                        .buttonStyle(.link)
                        .lineLimit(1)
                }
            }
            Spacer()
            if browser.loading { ProgressView().controlSize(.small) }
        }
    }

    @ViewBuilder
    private func row(_ entry: PhoneFolderEntry) -> some View {
        HStack(spacing: 10) {
            Image(systemName: entry.isDirectory ? "folder.fill" : "doc")
                .foregroundStyle(entry.isDirectory ? Color.accentColor : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).lineLimit(1)
                if !entry.isDirectory {
                    Text(detail(entry)).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if entry.isDirectory {
                Button(L10n.text("browse.open", fallback: "Open")) { browser.open(folder: entry) }
            } else {
                Button(L10n.text("browse.download", fallback: "Download")) { browser.download(entry) }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { if entry.isDirectory { browser.open(folder: entry) } }
    }

    private var footer: some View {
        HStack {
            if let status = browser.status {
                Text(status).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            } else if browser.rootID != nil {
                Text(String(format: L10n.text("browse.count", fallback: "%d of %d items"), browser.entries.count, browser.total))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if browser.nextOffset != nil {
                Button(L10n.text("browse.more", fallback: "Load More")) { browser.loadMore() }
            }
            if browser.uploadsAllowed && browser.rootID != nil {
                Button(L10n.text("browse.upload", fallback: "Add Files…")) { browser.chooseUpload() }
            }
            Button(L10n.text("browse.refresh", fallback: "Refresh")) { browser.refresh() }
        }
    }

    private func detail(_ entry: PhoneFolderEntry) -> String {
        var parts: [String] = []
        if let size = entry.size { parts.append(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)) }
        if let modified = entry.modified { parts.append(modified.formatted(date: .abbreviated, time: .shortened)) }
        return parts.joined(separator: " · ")
    }
}
