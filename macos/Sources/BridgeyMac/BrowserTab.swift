import AppKit
import Foundation

// "Send the current browser tab": asks Safari or Chrome for the address of the front window's tab
// with a fixed AppleScript. macOS asks for Automation access to that browser the first time it is
// used, never before. Nothing from the network or the phone becomes part of a script.

enum SupportedBrowser: String, CaseIterable, Equatable {
    case safari = "com.apple.Safari"
    case chrome = "com.google.Chrome"

    var name: String { self == .safari ? "Safari" : "Chrome" }

    var script: String {
        let tab = self == .safari ? "current tab" : "active tab"
        return "with timeout of 3 seconds\ntell application id \"\(rawValue)\" to return URL of \(tab) of front window\nend timeout"
    }
}

enum BrowserTabFailure: Error, Equatable {
    case noBrowser
    /// No window, or the tab has no web address (a start page).
    case noTab
    /// Automation was refused or the browser did not answer.
    case automation

    var message: String {
        switch self {
        case .noBrowser: L10n.text("browser.none", fallback: "Open Safari or Chrome first")
        case .noTab: L10n.text("browser.noTab", fallback: "The front browser tab has no web address")
        case .automation: L10n.text("browser.automation", fallback: "Allow Bridgey to control the browser in System Settings > Privacy & Security > Automation")
        }
    }
}

/// The browser to ask: the last one used that is still running, else any running one.
func preferredBrowser(last: SupportedBrowser?, running: [SupportedBrowser]) -> SupportedBrowser? {
    if let last, running.contains(last) { return last }
    return SupportedBrowser.allCases.first { running.contains($0) }
}

/// Remembers the browser used last, because opening the menu bar panel does not change it.
@MainActor
final class BrowserTabReader {
    private var lastBrowser: SupportedBrowser?
    private var observer: NSObjectProtocol?
    private let queue = DispatchQueue(label: "dev.bridgey.browser-tab")

    init() {
        lastBrowser = NSWorkspace.shared.frontmostApplication?.bundleIdentifier.flatMap(SupportedBrowser.init(rawValue:))
        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main
        ) { [weak self] note in
            let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            guard let browser = app?.bundleIdentifier.flatMap(SupportedBrowser.init(rawValue:)) else { return }
            Task { @MainActor in self?.lastBrowser = browser }
        }
    }

    func currentTab(completion: @escaping (Result<String, BrowserTabFailure>) -> Void) {
        let running = SupportedBrowser.allCases.filter {
            !NSRunningApplication.runningApplications(withBundleIdentifier: $0.rawValue).isEmpty
        }
        guard let browser = preferredBrowser(last: lastBrowser, running: running) else { completion(.failure(.noBrowser)); return }
        let script = browser.script
        queue.async {
            let output = runFixedAppleScript(script)
            DispatchQueue.main.async {
                guard let output else { completion(.failure(.automation)); return }
                guard let link = validatedWebLink(output) else { completion(.failure(.noTab)); return }
                completion(.success(link))
            }
        }
    }
}

/// Runs a fixed script with osascript (no shell); nil on failure, after 5 seconds or over 8 KiB.
private func runFixedAppleScript(_ source: String) -> String? {
    let process = Process()
    let pipe = Pipe()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    process.arguments = ["-e", source]
    process.standardOutput = pipe
    process.standardError = FileHandle.nullDevice
    let deadline = DispatchWorkItem { if process.isRunning { process.terminate() } }
    do { try process.run() } catch { return nil }
    DispatchQueue.global().asyncAfter(deadline: .now() + 5, execute: deadline)
    var data = Data()
    while let chunk = try? pipe.fileHandleForReading.read(upToCount: 8192), !chunk.isEmpty {
        guard data.count + chunk.count <= 8192 else { process.terminate(); break }
        data.append(chunk)
    }
    process.waitUntilExit()
    deadline.cancel()
    guard process.terminationStatus == 0 else { return nil }
    return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
}
