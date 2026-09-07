import Carbon
import SwiftUI

enum ShortcutAction: String, CaseIterable, Identifiable {
    case clipboard, call, link, ping
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
    var defaultKey: String { switch self { case .clipboard: "C"; case .call: "P"; default: "Off" } }
}

@MainActor
final class ShortcutSettings: ObservableObject {
    static let keys: [(String, UInt32)] = [
        ("A", 0), ("B", 11), ("C", 8), ("D", 2), ("E", 14), ("F", 3), ("G", 5), ("H", 4),
        ("I", 34), ("J", 38), ("K", 40), ("L", 37), ("M", 46), ("N", 45), ("O", 31),
        ("P", 35), ("Q", 12), ("R", 15), ("S", 1), ("T", 17), ("U", 32), ("V", 9),
        ("W", 13), ("X", 7), ("Y", 16), ("Z", 6),
    ]
    @Published private(set) var keys: [ShortcutAction: String] = [:]
    @Published private(set) var useShift: Bool
    @Published private(set) var error: String?
    private var registered: [GlobalHotKey] = []
    var perform: (ShortcutAction) -> Void = { _ in }

    init() {
        useShift = UserDefaults.standard.bool(forKey: "shortcuts.shift")
        for action in ShortcutAction.allCases {
            keys[action] = UserDefaults.standard.string(forKey: "shortcuts.\(action.rawValue)") ?? action.defaultKey
        }
    }
    func setKey(_ key: String, for action: ShortcutAction) {
        guard key == "Off" || Self.keys.contains(where: { $0.0 == key }) else { return }
        guard key == "Off" || !keys.contains(where: { $0.key != action && $0.value == key }) else {
            error = "That shortcut is already assigned to another Bridgey action"; return
        }
        keys[action] = key
        UserDefaults.standard.set(key, forKey: "shortcuts.\(action.rawValue)")
        register()
    }
    func setShift(_ value: Bool) {
        useShift = value; UserDefaults.standard.set(value, forKey: "shortcuts.shift"); register()
    }
    func register() {
        registered.removeAll(); error = nil
        for (index, action) in ShortcutAction.allCases.enumerated() {
            guard let key = Self.keys.first(where: { $0.0 == keys[action] }) else { continue }
            let hotkey = GlobalHotKey(keyCode: key.1,
                modifiers: UInt32(controlKey | optionKey | (useShift ? shiftKey : 0)),
                identifier: UInt32(index + 1)) { [weak self] in self?.perform(action) }
            if !hotkey.isRegistered { error = "A shortcut is in use by another app. Choose a different key or add Shift." }
            registered.append(hotkey)
        }
    }
}

struct ShortcutSettingsView: View {
    @ObservedObject var shortcuts: ShortcutSettings
    var body: some View {
        Section("Keyboard shortcuts") {
            Text("Shortcuts use Control + Option and the selected key.")
                .font(.caption).foregroundStyle(.secondary)
            Toggle("Also require Shift", isOn: Binding(get: { shortcuts.useShift }, set: shortcuts.setShift))
            ForEach(ShortcutAction.allCases) { action in
                Picker(action.title, selection: Binding(
                    get: { shortcuts.keys[action] ?? "Off" }, set: { shortcuts.setKey($0, for: action) })) {
                    Text("Off").tag("Off")
                    ForEach(ShortcutSettings.keys, id: \.0) { Text($0.0).tag($0.0) }
                }
            }
            if let error = shortcuts.error { Text(error).font(.caption).foregroundStyle(.red) }
        }
    }
}
