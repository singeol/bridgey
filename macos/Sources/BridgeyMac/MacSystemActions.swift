import AppKit
import ApplicationServices
import AudioToolbox
import CoreAudio
import Foundation
import IOKit.pwr_mgt

// The real operations behind Mac actions, public interfaces only:
// - Lock: macOS has no public "lock now" call, so this posts the system Lock Screen shortcut,
//   Control-Command-Q, with CGEvent. That needs Accessibility access.
// - Display sleep: /usr/bin/pmset displaysleepnow, fixed arguments, no shell.
// - Sleep: IOKit IOPMSleepSystem, allowed for the logged-in user without admin rights.
// - Screen saver: opens the system ScreenSaverEngine.app.
// - Volume: CoreAudio on the default output device.
// - Apps: NSWorkspace, by the bundle identifier stored in the local list.
// - Shortcuts: /usr/bin/shortcuts run <name>, the name from the local list as one process argument,
//   no input, at most 60 seconds.
// Tests never call this type; they use a fake MacSystemActing.
@MainActor
final class LiveMacSystemActions: MacSystemActing {
    private let queue = DispatchQueue(label: "dev.bridgey.mac-actions")

    var accessibilityTrusted: Bool { AXIsProcessTrusted() }

    func requestAccessibility() {
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
    }

    func lockScreen() -> Bool {
        guard AXIsProcessTrusted(), let source = CGEventSource(stateID: .hidSystemState),
              let down = CGEvent(keyboardEventSource: source, virtualKey: 12, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: 12, keyDown: false) else { return false }
        down.flags = [.maskControl, .maskCommand]
        up.flags = [.maskControl, .maskCommand]
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
        return true
    }

    func displaySleep() -> Bool {
        MacActionProcess.run(path: "/usr/bin/pmset", arguments: ["displaysleepnow"], timeout: 5)?.status == 0
    }

    func sleepSystem() -> Bool {
        let port = IOPMFindPowerManagement(mach_port_t(MACH_PORT_NULL))
        guard port != 0 else { return false }
        defer { IOServiceClose(port) }
        return IOPMSleepSystem(port) == kIOReturnSuccess
    }

    func startScreenSaver() -> Bool {
        let url = URL(fileURLWithPath: "/System/Library/CoreServices/ScreenSaverEngine.app", isDirectory: true)
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration()) { _, _ in }
        return true
    }

    func volume() -> MacVolumeState? {
        guard let device = CoreAudioOutput.defaultDevice(), let level = CoreAudioOutput.volume(device) else { return nil }
        return MacVolumeState(level: Int((level * 100).rounded()), muted: CoreAudioOutput.muted(device) ?? false)
    }

    func stepVolume(up: Bool) -> MacVolumeState? {
        guard let device = CoreAudioOutput.defaultDevice(), let level = CoreAudioOutput.volume(device),
              CoreAudioOutput.setVolume(device, steppedVolume(level, up: up)) else { return nil }
        // Like the volume keys: turning it up unmutes.
        if up, CoreAudioOutput.muted(device) == true { _ = CoreAudioOutput.setMuted(device, false) }
        return volume()
    }

    func toggleMute() -> MacVolumeState? {
        guard let device = CoreAudioOutput.defaultDevice(), let muted = CoreAudioOutput.muted(device),
              CoreAudioOutput.setMuted(device, !muted) else { return nil }
        return volume()
    }

    func setVolume(_ level: Int) -> MacVolumeState? {
        guard let device = CoreAudioOutput.defaultDevice(),
              CoreAudioOutput.setVolume(device, Float32(min(max(level, 0), 100)) / 100) else { return nil }
        if level > 0, CoreAudioOutput.muted(device) == true { _ = CoreAudioOutput.setMuted(device, false) }
        return volume()
    }

    func openApp(bundleID: String, completion: @escaping (Bool?) -> Void) {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleID) else { completion(nil); return }
        NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration()) { app, error in
            DispatchQueue.main.async { completion(app != nil && error == nil) }
        }
    }

    func runShortcut(named name: String, update: @escaping (ShortcutRunOutcome) -> Void) {
        queue.async {
            let result = MacActionProcess.run(path: "/usr/bin/shortcuts", arguments: ["run", name], timeout: 60)
            let outcome: ShortcutRunOutcome = result?.status == 0 && result?.timedOut == false ? .done : .failed
            DispatchQueue.main.async { update(outcome) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { update(.running) }
    }

    func listShortcuts(completion: @escaping ([String]?) -> Void) {
        queue.async {
            let result = MacActionProcess.run(path: "/usr/bin/shortcuts", arguments: ["list"], timeout: 10, captureLimit: 256 * 1024)
            let names = result.flatMap { $0.status == 0 ? $0.output : nil }.map {
                Array(Set($0.split(separator: "\n").map(String.init).filter { !$0.isEmpty })).sorted()
            }
            DispatchQueue.main.async { completion(names) }
        }
    }
}

/// One volume key step: 1/16 of the range, snapped to the step grid.
func steppedVolume(_ level: Float32, up: Bool) -> Float32 {
    let steps = (min(max(level, 0), 1) * 16).rounded()
    return min(max(steps + (up ? 1 : -1), 0), 16) / 16
}

/// Runs a fixed executable with separate arguments: never through a shell.
enum MacActionProcess {
    struct Result {
        let status: Int32
        let output: String
        let timedOut: Bool
    }

    static func run(path: String, arguments: [String], timeout: TimeInterval, captureLimit: Int = 0) -> Result? {
        guard FileManager.default.isExecutableFile(atPath: path) else { return nil }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        process.standardInput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        let pipe = Pipe()
        process.standardOutput = captureLimit > 0 ? pipe : FileHandle.nullDevice
        let timedOut = MacActionFlag()
        let deadline = DispatchWorkItem { if process.isRunning { timedOut.set(); process.terminate() } }
        do { try process.run() } catch { return nil }
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: deadline)
        var data = Data()
        if captureLimit > 0 {
            while let chunk = try? pipe.fileHandleForReading.read(upToCount: 65536), !chunk.isEmpty {
                guard data.count + chunk.count <= captureLimit else { process.terminate(); break }
                data.append(chunk)
            }
        }
        process.waitUntilExit()
        deadline.cancel()
        return Result(status: process.terminationStatus, output: String(decoding: data, as: UTF8.self), timedOut: timedOut.value)
    }
}

private final class MacActionFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    var value: Bool { lock.lock(); defer { lock.unlock() }; return flag }
    func set() { lock.lock(); flag = true; lock.unlock() }
}

/// The default output device's volume and mute through CoreAudio.
enum CoreAudioOutput {
    static func defaultDevice() -> AudioDeviceID? {
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr,
              device != kAudioObjectUnknown else { return nil }
        return device
    }

    private static func address(_ selector: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
    }

    static func volume(_ device: AudioDeviceID) -> Float32? {
        var address = address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume)
        guard AudioObjectHasProperty(device, &address) else { return nil }
        var value = Float32(0)
        var size = UInt32(MemoryLayout<Float32>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr else { return nil }
        return min(max(value, 0), 1)
    }

    static func setVolume(_ device: AudioDeviceID, _ level: Float32) -> Bool {
        var address = address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume)
        var settable: DarwinBoolean = false
        guard AudioObjectHasProperty(device, &address),
              AudioObjectIsPropertySettable(device, &address, &settable) == noErr, settable.boolValue else { return false }
        var value = min(max(level, 0), 1)
        return AudioObjectSetPropertyData(device, &address, 0, nil, UInt32(MemoryLayout<Float32>.size), &value) == noErr
    }

    static func muted(_ device: AudioDeviceID) -> Bool? {
        var address = address(kAudioDevicePropertyMute)
        guard AudioObjectHasProperty(device, &address) else { return nil }
        var value = UInt32(0)
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr else { return nil }
        return value != 0
    }

    static func setMuted(_ device: AudioDeviceID, _ muted: Bool) -> Bool {
        var address = address(kAudioDevicePropertyMute)
        var settable: DarwinBoolean = false
        guard AudioObjectHasProperty(device, &address),
              AudioObjectIsPropertySettable(device, &address, &settable) == noErr, settable.boolValue else { return false }
        var value: UInt32 = muted ? 1 : 0
        return AudioObjectSetPropertyData(device, &address, 0, nil, UInt32(MemoryLayout<UInt32>.size), &value) == noErr
    }
}
