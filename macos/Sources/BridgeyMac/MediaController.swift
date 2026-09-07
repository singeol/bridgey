import AppKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum MediaPlayer: String, CaseIterable, Identifiable {
    case off, music, spotify
    var id: String { rawValue }
    var title: String { switch self { case .off: "Off"; case .music: "Music"; case .spotify: "Spotify" } }
    var bundleID: String { self == .music ? "com.apple.Music" : "com.spotify.client" }
}

struct MediaSnapshot {
    var title = ""
    var artist = ""
    var playing = false
    var position = 0
    var duration = 0
    var volume = 0
    var detail = ""
    var artwork: String? = nil
    func payload(player: MediaPlayer) -> [String: Any] {
        var value: [String: Any] = ["version": 1, "player": player.title, "title": title, "artist": artist,
         "playing": playing, "position": position, "duration": duration, "volume": volume, "detail": detail]
        if let artwork { value["artwork"] = artwork }
        return value
    }
}

func mediaCommand(_ action: String, value: String) -> String? {
    switch action {
    case "toggle": return "playpause"
    case "pause": return "pause"
    case "next": return "next track"
    case "previous": return "previous track"
    case "seek":
        guard let seconds = Int(value), (0...604800).contains(seconds) else { return nil }
        return "set player position to \(seconds)"
    case "volume":
        guard let volume = Int(value), (0...100).contains(volume) else { return nil }
        return "set sound volume to \(volume)"
    default: return nil
    }
}

// Only fixed scripts and validated integer operands reach osascript. No remote source code.
private func runMediaScript(_ source: String, limit: Int = 8192) -> String? {
    let process = Process()
    let pipe = Pipe()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    process.arguments = ["-e", source]
    process.standardOutput = pipe
    process.standardError = FileHandle.nullDevice
    let deadline = DispatchWorkItem { if process.isRunning { process.terminate() } }
    do { try process.run() } catch { return nil }
    DispatchQueue.global().asyncAfter(deadline: .now() + 4, execute: deadline)
    var data = Data()
    while let chunk = try? pipe.fileHandleForReading.read(upToCount: 8192), !chunk.isEmpty {
        guard data.count + chunk.count <= limit else {
            pipe.fileHandleForReading.closeFile()
            process.terminate()
            process.waitUntilExit()
            deadline.cancel()
            return nil
        }
        data.append(chunk)
    }
    process.waitUntilExit()
    deadline.cancel()
    guard process.terminationStatus == 0 else { return nil }
    return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .newlines)
}

// osascript prints raw Apple-event data as «data TYPE<hex>». Never read player files directly.
func mediaArtworkData(_ descriptor: String) -> Data? {
    guard descriptor.utf8.count <= 2_100_000, descriptor.hasPrefix("«data "), descriptor.hasSuffix("»") else { return nil }
    let hex = descriptor.dropFirst(10).dropLast()
    guard !hex.isEmpty, hex.count.isMultiple(of: 2) else { return nil }
    var bytes = Data()
    var index = hex.startIndex
    while index < hex.endIndex {
        let end = hex.index(index, offsetBy: 2)
        guard let byte = UInt8(hex[index..<end], radix: 16) else { return nil }
        bytes.append(byte); index = end
    }
    return bytes
}

private func smallMediaArtwork(_ descriptor: String) -> String? {
    guard let data = mediaArtworkData(descriptor),
          let source = CGImageSourceCreateWithData(data as CFData, nil),
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 128,
            kCGImageSourceCreateThumbnailWithTransform: true,
          ] as CFDictionary) else { return nil }
    let output = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
    CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.7] as CFDictionary)
    guard CGImageDestinationFinalize(destination), output.length <= 16384 else { return nil }
    return (output as Data).base64EncodedString()
}

@MainActor
final class MediaController: ObservableObject {
    @Published var player: MediaPlayer {
        didSet { UserDefaults.standard.set(player.rawValue, forKey: "media.player"); generation += 1; refresh() }
    }
    @Published var pauseForCalls: Bool {
        didSet { UserDefaults.standard.set(pauseForCalls, forKey: "media.pauseForCalls") }
    }
    @Published private(set) var snapshot = MediaSnapshot()
    var onState: ([String: Any]) -> Void = { _ in }
    var allowed: () -> Bool = { false }
    private var busy = false
    private var generation = 0
    private var callActive = false
    private var pendingPause = false
    private let queue = DispatchQueue(label: "dev.bridgey.media")

    init() {
        player = MediaPlayer(rawValue: UserDefaults.standard.string(forKey: "media.player") ?? "") ?? .off
        pauseForCalls = UserDefaults.standard.bool(forKey: "media.pauseForCalls")
    }

    func refresh() { perform(nil, completion: { _ in }) }
    func command(action: String, value: String, completion: @escaping (Bool) -> Void) {
        guard let command = mediaCommand(action, value: value) else { completion(false); return }
        perform(command, completion: completion)
    }
    func callChanged(active: Bool) {
        let shouldPause = active && !callActive && pauseForCalls
        callActive = active
        if !active { pendingPause = false }
        if shouldPause {
            if busy { pendingPause = true }
            else { command(action: "pause", value: "", completion: { _ in }) }
        }
    }
    func reset() { generation += 1; pendingPause = false; snapshot = MediaSnapshot() }

    private func perform(_ command: String?, completion: @escaping (Bool) -> Void) {
        guard allowed() else { snapshot = MediaSnapshot(); completion(false); return }
        guard player != .off else {
            snapshot = MediaSnapshot(detail: "Select Music or Spotify in Mac Settings → Media")
            onState(snapshot.payload(player: .off)); completion(false); return
        }
        guard !busy else { completion(false); return }
        let selected = player
        guard NSRunningApplication.runningApplications(withBundleIdentifier: selected.bundleID).isEmpty == false else {
            snapshot = MediaSnapshot(detail: "Open \(selected.title) on your Mac")
            onState(snapshot.payload(player: selected)); completion(false); return
        }
        busy = true
        let epoch = generation
        let previous = snapshot
        queue.async {
            let prefix = "with timeout of 3 seconds\n tell application id \"\(selected.bundleID)\"\n"
            let suffix = "\n end tell\nend timeout"
            let success = command.map { runMediaScript(prefix + $0 + suffix) != nil } ?? true
            if command != nil {
                DispatchQueue.main.async {
                    completion(success && epoch == self.generation && self.allowed() && self.player == selected)
                }
            }
            let duration = selected == .spotify ? "(duration of current track) / 1000" : "duration of current track"
            let source = prefix + """
                set sep to ASCII character 31
                set t to ""
                set a to ""
                set d to 0
                set p to 0
                try
                    set t to name of current track
                    set a to artist of current track
                    set d to \(duration)
                    set p to player position
                end try
                if (count t) > 256 then set t to text 1 thru 256 of t
                if (count a) > 256 then set a to text 1 thru 256 of a
                return t & sep & a & sep & (player state as text) & sep & (p as integer) & sep & (d as integer) & sep & sound volume
                """ + suffix
            let output = runMediaScript(source)
            let fields = output?.components(separatedBy: "\u{1f}") ?? []
            var result = MediaSnapshot(detail: "Allow Bridgey in System Settings → Privacy & Security → Automation")
            if fields.count == 6 {
                result = MediaSnapshot(title: String(fields[0].prefix(256)), artist: String(fields[1].prefix(256)),
                    playing: fields[2] == "playing", position: min(max(Int(fields[3]) ?? 0, 0), 604800),
                    duration: min(max(Int(fields[4]) ?? 0, 0), 604800), volume: min(max(Int(fields[5]) ?? 0, 0), 100))
                if selected == .music && !result.title.isEmpty {
                    if previous.title == result.title && previous.artist == result.artist {
                        result.artwork = previous.artwork
                    } else if let art = runMediaScript(prefix + "return raw data of artwork 1 of current track" + suffix, limit: 2_100_000) {
                        result.artwork = smallMediaArtwork(art)
                    }
                }
            }
            let snapshot = result
            DispatchQueue.main.async {
                self.busy = false
                defer {
                    if self.pendingPause && self.callActive && self.pauseForCalls {
                        self.pendingPause = false
                        self.command(action: "pause", value: "", completion: { _ in })
                    }
                }
                guard epoch == self.generation, self.allowed(), self.player == selected else {
                    if command == nil { completion(false) }
                    return
                }
                self.snapshot = snapshot
                self.onState(snapshot.payload(player: selected))
                if command == nil { completion(output != nil) }
            }
        }
    }
}
