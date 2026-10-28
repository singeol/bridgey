import AppKit
import Carbon
import SwiftUI

/*
 * While a phone controls this Mac: a small pill at the top of the screen ("Pixel is controlling
 * this Mac", Stop), always on top, non-activating, above full-screen slideshows, plus the
 * Control-Option-Escape stop shortcut. The remote cannot send Control or Option, so it can never
 * press the shortcut itself.
 */

@MainActor
final class RemoteControlIndicator: RemoteControlIndicating {
    private var pill: NSPanel?
    private var stopHotKey: GlobalHotKey?

    func show(deviceName: String, mode: RemoteInputMode, onStop: @escaping () -> Void) {
        hide()
        let host = FirstClickHostingView(rootView: RemoteControlPill(deviceName: deviceName, mode: mode, onStop: onStop))
        let size = host.fittingSize
        let panel = Self.overlayPanel(size: size)
        panel.contentView = host
        let screen = NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) } ?? NSScreen.main
        if let visible = screen?.visibleFrame {
            panel.setFrameOrigin(NSPoint(x: visible.midX - size.width / 2, y: visible.maxY - size.height - 10))
        }
        panel.orderFrontRegardless()
        pill = panel
        stopHotKey = GlobalHotKey(keyCode: UInt32(kVK_Escape), modifiers: UInt32(controlKey | optionKey), identifier: 90, action: onStop)
    }

    func hide() {
        pill?.orderOut(nil)
        pill = nil
        stopHotKey = nil
    }

    static func overlayPanel(size: CGSize) -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(origin: .zero, size: size),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.isMovable = false
        // Above a captured full-screen slideshow, so the indicator stays visible.
        panel.level = NSWindow.Level(rawValue: Int(CGShieldingWindowLevel()))
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.animationBehavior = .none
        return panel
    }
}

/// Stop works on the first click, without activating Bridgey or taking focus from the front app.
private final class FirstClickHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

/// "Pixel is controlling this Mac", the stop shortcut, and Stop.
struct RemoteControlPill: View {
    let deviceName: String
    let mode: RemoteInputMode
    let onStop: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(Color.green)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Image(systemName: mode == .presentation ? "play.rectangle" : "cursorarrow.rays")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(String(format: L10n.text("remote.pill.title", fallback: "%@ is controlling this Mac"), deviceName))
                .font(.callout.weight(.semibold))
                .lineLimit(1)
            Text("\u{2303}\u{2325}\u{238B}")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                .help(L10n.text("remote.pill.shortcut", fallback: "Control-Option-Escape also stops remote control"))
                .accessibilityLabel(L10n.text("remote.pill.shortcut", fallback: "Control-Option-Escape also stops remote control"))
            Button(L10n.text("remote.stop", fallback: "Stop"), action: onStop)
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .controlSize(.small)
        }
        .padding(.leading, 14)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .overlay { Capsule().strokeBorder(.white.opacity(0.18)) }
        .padding(6)
        .accessibilityElement(children: .contain)
    }
}
