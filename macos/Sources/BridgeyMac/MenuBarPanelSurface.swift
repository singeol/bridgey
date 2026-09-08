import SwiftUI

/// A menu-bar window must report its content height, not accept the previous
/// (often much taller) connection state's window-height proposal.
struct MenuBarPanelSurface<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .frame(width: 340, alignment: .topLeading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct FileTransfersSummaryButton: View {
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            // Keep Spacer horizontal: a multi-view button label has no explicit
            // layout axis and must not participate in the panel's vertical sizing.
            HStack {
                Label("File transfers", systemImage: "arrow.left.arrow.right.circle")
                Spacer()
                Text("\(count)").foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
