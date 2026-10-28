import AppKit
import CoreGraphics
import Foundation

/// What a remote input session does to this Mac. The app posts CGEvents; tests use a fake.
@MainActor
protocol RemoteInputInjecting: AnyObject {
    /// Moves the pointer by a delta in points, clamped to the displays. With a button held, the
    /// move is a drag of that button.
    func move(dx: Int, dy: Int, held: Set<PointerButton>)
    func button(_ button: PointerButton, down: Bool)
    func scroll(dx: Int, dy: Int, unit: ScrollUnit)
    /// Types text as text (Unicode key events), never as shortcuts.
    func type(_ text: String)
    func press(_ strokes: [KeyStroke])
}

struct KeyModifiers: OptionSet, Equatable, Hashable {
    let rawValue: Int
    static let shift = KeyModifiers(rawValue: 1 << 0)
    static let command = KeyModifiers(rawValue: 1 << 1)
    static let option = KeyModifiers(rawValue: 1 << 2)
    static let control = KeyModifiers(rawValue: 1 << 3)
}

/// One key press and release with modifiers, as Carbon virtual key codes.
struct KeyStroke: Equatable {
    let keyCode: CGKeyCode
    var modifiers: KeyModifiers = []

    enum Code {
        static let returnKey: CGKeyCode = 36
        static let tab: CGKeyCode = 48
        static let space: CGKeyCode = 49
        static let backspace: CGKeyCode = 51
        static let escape: CGKeyCode = 53
        static let command: CGKeyCode = 55
        static let shift: CGKeyCode = 56
        static let option: CGKeyCode = 58
        static let control: CGKeyCode = 59
        static let f5: CGKeyCode = 96
        static let home: CGKeyCode = 115
        static let pageUp: CGKeyCode = 116
        static let end: CGKeyCode = 119
        static let pageDown: CGKeyCode = 121
        static let left: CGKeyCode = 123
        static let right: CGKeyCode = 124
        static let down: CGKeyCode = 125
        static let up: CGKeyCode = 126
        static let b: CGKeyCode = 11
        static let f: CGKeyCode = 3
        static let p: CGKeyCode = 35
    }
}

extension RemoteKey {
    /// The one stroke for this key. Shift is the only modifier a controller can add;
    /// `previousApp` is Command-Tab, chosen here rather than on the wire.
    func stroke(shift: Bool) -> KeyStroke {
        let modifiers: KeyModifiers = shift ? .shift : []
        switch self {
        case .left: return KeyStroke(keyCode: KeyStroke.Code.left, modifiers: modifiers)
        case .right: return KeyStroke(keyCode: KeyStroke.Code.right, modifiers: modifiers)
        case .up: return KeyStroke(keyCode: KeyStroke.Code.up, modifiers: modifiers)
        case .down: return KeyStroke(keyCode: KeyStroke.Code.down, modifiers: modifiers)
        case .returnKey: return KeyStroke(keyCode: KeyStroke.Code.returnKey, modifiers: modifiers)
        case .escape: return KeyStroke(keyCode: KeyStroke.Code.escape, modifiers: modifiers)
        case .tab: return KeyStroke(keyCode: KeyStroke.Code.tab, modifiers: modifiers)
        case .space: return KeyStroke(keyCode: KeyStroke.Code.space, modifiers: modifiers)
        case .backspace: return KeyStroke(keyCode: KeyStroke.Code.backspace, modifiers: modifiers)
        case .pageUp: return KeyStroke(keyCode: KeyStroke.Code.pageUp, modifiers: modifiers)
        case .pageDown: return KeyStroke(keyCode: KeyStroke.Code.pageDown, modifiers: modifiers)
        case .home: return KeyStroke(keyCode: KeyStroke.Code.home, modifiers: modifiers)
        case .end: return KeyStroke(keyCode: KeyStroke.Code.end, modifiers: modifiers)
        case .f5: return KeyStroke(keyCode: KeyStroke.Code.f5, modifiers: modifiers)
        case .previousApp: return KeyStroke(keyCode: KeyStroke.Code.tab, modifiers: .command)
        }
    }
}

/// Presentation actions as keys for the frontmost app. Next and previous are Page Down and Page Up,
/// like presentation clickers; start plays from the current slide.
func presentationStrokes(_ action: PresentAction, frontmostBundleID: String?) -> [KeyStroke] {
    switch action {
    case .next: return [KeyStroke(keyCode: KeyStroke.Code.pageDown)]
    case .previous: return [KeyStroke(keyCode: KeyStroke.Code.pageUp)]
    case .end: return [KeyStroke(keyCode: KeyStroke.Code.escape)]
    case .black: return [KeyStroke(keyCode: KeyStroke.Code.b)]
    case .start:
        switch PresentationApp(bundleID: frontmostBundleID) {
        case .keynote: return [KeyStroke(keyCode: KeyStroke.Code.p, modifiers: [.option, .command])]
        case .powerPoint, .browser: return [KeyStroke(keyCode: KeyStroke.Code.returnKey, modifiers: .command)]
        case .other: return [KeyStroke(keyCode: KeyStroke.Code.f, modifiers: [.control, .command])]
        }
    case .laserOn, .laserMove, .laserOff: return []
    }
}

/// The frontmost app, as far as presentations care. Google Slides runs in a browser.
enum PresentationApp: Equatable {
    case keynote, powerPoint, browser, other

    static let browsers: Set<String> = [
        "com.apple.Safari", "com.apple.SafariTechnologyPreview", "com.google.Chrome", "com.google.Chrome.beta",
        "com.google.Chrome.canary", "company.thebrowser.Browser", "com.microsoft.edgemac", "org.mozilla.firefox",
        "com.brave.Browser", "com.vivaldi.Vivaldi", "com.operasoftware.Opera", "org.chromium.Chromium",
    ]

    init(bundleID: String?) {
        switch bundleID {
        case "com.apple.iWork.Keynote": self = .keynote
        case "com.microsoft.Powerpoint": self = .powerPoint
        case let id? where Self.browsers.contains(id): self = .browser
        default: self = .other
        }
    }
}


/// Keeps a point on a display: unchanged when inside one, otherwise the nearest point of the
/// nearest display. Works for any arrangement, gaps and different sizes included. `displays` are
/// global display bounds (Quartz: origin top left of the main display, y down).
func clampToDisplays(_ point: CGPoint, displays: [CGRect]) -> CGPoint {
    let screens = displays.filter { !$0.isEmpty }
    guard !screens.isEmpty else { return point }
    if screens.contains(where: { point.x >= $0.minX && point.x < $0.maxX && point.y >= $0.minY && point.y < $0.maxY }) {
        return point
    }
    var best = point
    var bestDistance = CGFloat.greatestFiniteMagnitude
    for rect in screens {
        let candidate = CGPoint(x: min(max(point.x, rect.minX), rect.maxX - 1), y: min(max(point.y, rect.minY), rect.maxY - 1))
        let distance = (candidate.x - point.x) * (candidate.x - point.x) + (candidate.y - point.y) * (candidate.y - point.y)
        if distance < bestDistance {
            bestDistance = distance
            best = candidate
        }
    }
    return best
}

/// The display that contains `point`, or the nearest one.
func display(containing point: CGPoint, displays: [CGRect]) -> CGRect? {
    let clamped = clampToDisplays(point, displays: displays)
    return displays.first { clamped.x >= $0.minX && clamped.x < $0.maxX && clamped.y >= $0.minY && clamped.y < $0.maxY }
}

/// A laser position (0...10,000 on each axis, origin top left) on `display`, in Quartz coordinates.
func laserPoint(x: Int, y: Int, on display: CGRect) -> CGPoint {
    let scale = CGFloat(RemoteInputLimits.laserScale)
    return CGPoint(x: display.minX + display.width * CGFloat(x) / scale, y: display.minY + display.height * CGFloat(y) / scale)
}

/// Quartz (y down from the top of the main display) to Cocoa (y up from its bottom).
func cocoaPoint(fromQuartz point: CGPoint, mainDisplayHeight: CGFloat) -> CGPoint {
    CGPoint(x: point.x, y: mainDisplayHeight - point.y)
}

/// Text as Unicode key events: at most `limit` UTF-16 units each (apps read up to 20), never
/// splitting a character.
func remoteTextChunks(_ text: String, limit: Int = 20) -> [[UInt16]] {
    var chunks: [[UInt16]] = []
    var current: [UInt16] = []
    for character in text {
        let units = Array(String(character).utf16)
        if !current.isEmpty && current.count + units.count > limit {
            chunks.append(current)
            current = []
        }
        current += units
    }
    if !current.isEmpty { chunks.append(current) }
    return chunks
}

/// Double and triple clicks: macOS reads the click count from each event, so the host keeps it.
struct ClickCounter {
    var interval: TimeInterval = 0.5
    var slop: CGFloat = 4
    private var last: (button: PointerButton, at: TimeInterval, point: CGPoint, count: Int)?

    init(interval: TimeInterval = 0.5, slop: CGFloat = 4) {
        self.interval = interval
        self.slop = slop
    }

    mutating func down(_ button: PointerButton, at time: TimeInterval, point: CGPoint) -> Int {
        var count = 1
        if let last, last.button == button, time - last.at <= interval,
           abs(point.x - last.point.x) <= slop, abs(point.y - last.point.y) <= slop {
            count = last.count >= 3 ? 1 : last.count + 1
        }
        last = (button, time, point, count)
        return count
    }
}

/// Posts CGEvents at the HID level. Needs Accessibility access; without it macOS drops them.
@MainActor
final class CGEventInjector: RemoteInputInjecting {
    private let source = CGEventSource(stateID: .hidSystemState)
    private var clicks = ClickCounter(interval: NSEvent.doubleClickInterval)
    private var clickState: [PointerButton: Int] = [:]

    func move(dx: Int, dy: Int, held: Set<PointerButton>) {
        let start = currentLocation()
        let target = clampToDisplays(CGPoint(x: start.x + CGFloat(dx), y: start.y + CGFloat(dy)), displays: Self.displayBounds())
        let type: CGEventType
        let button: CGMouseButton
        if held.contains(.left) {
            (type, button) = (.leftMouseDragged, .left)
        } else if held.contains(.right) {
            (type, button) = (.rightMouseDragged, .right)
        } else if held.contains(.middle) {
            (type, button) = (.otherMouseDragged, .center)
        } else {
            (type, button) = (.mouseMoved, .left)
        }
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: target, mouseButton: button) else { return }
        event.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx))
        event.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy))
        event.post(tap: .cghidEventTap)
    }

    func button(_ button: PointerButton, down: Bool) {
        let location = currentLocation()
        let (type, mouseButton): (CGEventType, CGMouseButton) = switch button {
        case .left: (down ? .leftMouseDown : .leftMouseUp, .left)
        case .right: (down ? .rightMouseDown : .rightMouseUp, .right)
        case .middle: (down ? .otherMouseDown : .otherMouseUp, .center)
        }
        let state: Int
        if down {
            state = clicks.down(button, at: ProcessInfo.processInfo.systemUptime, point: location)
            clickState[button] = state
        } else {
            state = clickState.removeValue(forKey: button) ?? 1
        }
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: location, mouseButton: mouseButton) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: Int64(state))
        event.post(tap: .cghidEventTap)
    }

    func scroll(dx: Int, dy: Int, unit: ScrollUnit) {
        guard let event = CGEvent(
            scrollWheelEvent2Source: source,
            units: unit == .pixel ? .pixel : .line,
            wheelCount: 2,
            wheel1: Int32(dy),
            wheel2: Int32(dx),
            wheel3: 0
        ) else { return }
        if unit == .pixel { event.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1) }
        event.post(tap: .cghidEventTap)
    }

    func type(_ text: String) {
        for chunk in remoteTextChunks(text) {
            for down in [true, false] {
                guard let event = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down) else { continue }
                event.flags = []
                chunk.withUnsafeBufferPointer { event.keyboardSetUnicodeString(stringLength: $0.count, unicodeString: $0.baseAddress) }
                event.post(tap: .cghidEventTap)
            }
        }
    }

    func press(_ strokes: [KeyStroke]) {
        for stroke in strokes {
            let modifiers: [(KeyModifiers, CGKeyCode, CGEventFlags)] = [
                (.control, KeyStroke.Code.control, .maskControl),
                (.option, KeyStroke.Code.option, .maskAlternate),
                (.shift, KeyStroke.Code.shift, .maskShift),
                (.command, KeyStroke.Code.command, .maskCommand),
            ].filter { stroke.modifiers.contains($0.0) }
            var flags: CGEventFlags = []
            // Modifiers go down first and up last, so system shortcuts such as Command-Tab see them held.
            for (_, code, flag) in modifiers {
                flags.insert(flag)
                post(key: code, down: true, flags: flags)
            }
            let keyFlags = flags.union(Self.functionFlags(stroke.keyCode))
            post(key: stroke.keyCode, down: true, flags: keyFlags)
            post(key: stroke.keyCode, down: false, flags: keyFlags)
            for (_, code, flag) in modifiers.reversed() {
                flags.remove(flag)
                post(key: code, down: false, flags: flags)
            }
        }
    }

    private func post(key: CGKeyCode, down: Bool, flags: CGEventFlags) {
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: down) else { return }
        event.flags = flags
        event.post(tap: .cghidEventTap)
    }

    /// Arrows carry the numeric pad and Fn flags, and the other navigation and function keys the
    /// Fn flag, as a real keyboard sends them.
    private static func functionFlags(_ code: CGKeyCode) -> CGEventFlags {
        switch code {
        case KeyStroke.Code.left, KeyStroke.Code.right, KeyStroke.Code.up, KeyStroke.Code.down:
            [.maskNumericPad, .maskSecondaryFn]
        case KeyStroke.Code.home, KeyStroke.Code.end, KeyStroke.Code.pageUp, KeyStroke.Code.pageDown, KeyStroke.Code.f5:
            .maskSecondaryFn
        default:
            []
        }
    }

    private func currentLocation() -> CGPoint {
        CGEvent(source: nil)?.location ?? .zero
    }

    static func displayBounds() -> [CGRect] {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else { return [] }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else { return [] }
        return ids.prefix(Int(count)).map { CGDisplayBounds($0) }
    }
}
