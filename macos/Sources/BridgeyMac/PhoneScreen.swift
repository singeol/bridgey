import CoreVideo
import Foundation

/*
 * Screen sharing, phone to Mac (`screen`, docs/protocol.md). The phone starts a stream after the person
 * confirms Android's capture dialog; this Mac shows it in a window while "Show phone screen" is on (off
 * by default). View only. PhoneScreen owns the stream's lifecycle and this Mac's messages on the main
 * actor; ScreenStreamReceiver reassembles and decodes frames off the main thread.
 */

/// Opens, updates and closes the viewer window (PhoneScreenWindow.swift); tests replace it.
@MainActor
protocol PhoneScreenPresenter: AnyObject {
    /// Opens the window for a new stream and returns where frames are shown.
    func open(phoneName: String, width: Int, height: Int, onStop: @escaping () -> Void) -> ScreenDisplaySink?
    func update(stats: ScreenStats)
    func resize(width: Int, height: Int)
    /// `message`: shown briefly before the window closes; nil closes it at once.
    func close(message: String?)
}

@MainActor
final class PhoneScreen: ObservableObject {
    static let streamTimeout: TimeInterval = 6

    final class Stream {
        let streamID: String
        let phoneName: String
        let receiver: ScreenStreamReceiver
        var lastActivity: Date

        init(streamID: String, phoneName: String, receiver: ScreenStreamReceiver, at: Date) {
            self.streamID = streamID
            self.phoneName = phoneName
            self.receiver = receiver
            lastActivity = at
        }
    }

    /// The phone whose screen is showing now.
    @Published private(set) var showing: String?

    /// Encrypts and sends one `screen.*` message on the current session, which adds its sequence.
    var send: (_ kind: String, _ payload: [String: Any]) -> Bool = { _, _ in false }
    var presenter: PhoneScreenPresenter?

    private(set) var stream: Stream?
    private let clock: () -> Date
    private let usesTimer: Bool
    private var ticker: Timer?

    init(clock: @escaping () -> Date = Date.init, usesTimer: Bool = true) {
        self.clock = clock
        self.usesTimer = usesTimer
    }

    /// A decrypted `screen.*` message from the connected phone. `accept` checks and records the session
    /// sequence; `enabled` is this Mac's setting for the phone. Malformed messages are dropped.
    func receive(kind: String, _ data: Data, phoneName: String, enabled: Bool, accept: (Int) -> Bool) {
        switch kind {
        case "screen.start":
            receiveStart(data, phoneName: phoneName, enabled: enabled, accept: accept)
        case "screen.frame":
            guard enabled, let part = ScreenFramePart.decode(data), accept(part.sequence),
                  let stream, part.streamID == stream.streamID else { return }
            stream.lastActivity = clock()
            stream.receiver.receive(part)
        case "screen.stats":
            guard enabled, let stats = ScreenStats.decode(data), accept(stats.header.sequence),
                  let stream, stats.header.streamID == stream.streamID else { return }
            stream.lastActivity = clock()
            presenter?.update(stats: stats)
        case "screen.stop":
            guard enabled, let stop = ScreenStop.decode(data), accept(stop.header.sequence),
                  let stream, stop.header.streamID == stream.streamID else { return }
            NSLog("PLUGIN screen stopped by phone stream=%@ reason=%@", String(stream.streamID.prefix(8)), stop.reason.rawValue)
            end(stream, message: screenEndedText(stop.reason, phone: stream.phoneName))
        default:
            break
        }
    }

    /// Stop in the window, or the window closed.
    func stopShowing() {
        guard let stream else { return }
        stop(stream, reason: .closed, message: nil)
    }

    /// "Show phone screen" was turned off here: the phone hears why.
    func disabledHere() {
        guard let stream else { return }
        stop(stream, reason: .disabled, message: L10n.text("screen.ended.disabledHere", fallback: "Show phone screen is off on this Mac"))
    }

    /// The phone turned sharing off, or its session ended: nothing more may be sent to it.
    func unavailable(disconnected: Bool) {
        guard let stream else { return }
        end(stream, message: disconnected
            ? String(format: L10n.text("screen.ended.disconnected", fallback: "%@ disconnected"), stream.phoneName)
            : String(format: L10n.text("screen.ended.phone", fallback: "Sharing stopped on %@"), stream.phoneName))
    }

    /// Stream timeouts; runs every second while a stream is open.
    func tick() {
        guard let stream else {
            ticker?.invalidate()
            ticker = nil
            return
        }
        if clock().timeIntervalSince(stream.lastActivity) >= Self.streamTimeout {
            stop(stream, reason: .timeout, message: String(format: L10n.text("screen.ended.timeout", fallback: "Lost the screen of %@"), stream.phoneName))
        }
    }

    // MARK: Private

    private func receiveStart(_ data: Data, phoneName: String, enabled: Bool, accept: (Int) -> Bool) {
        guard let header = ScreenMessageHeader.decode(data, limit: ScreenLimits.startBytes), accept(header.sequence) else { return }
        guard enabled else {
            _ = send("screen.stop", screenStopPayload(streamID: header.streamID, reason: .disabled))
            return
        }
        guard let start = ScreenStart.decode(data) else {
            NSLog("PLUGIN screen start refused reason=invalid")
            _ = send("screen.stop", screenStopPayload(streamID: header.streamID, reason: .invalid))
            return
        }
        if let previous = stream {
            // The phone started a new stream: the old one ends here without a message.
            end(previous, message: nil)
        }
        let receiver = ScreenStreamReceiver(streamID: start.header.streamID)
        let stream = Stream(streamID: start.header.streamID, phoneName: phoneName, receiver: receiver, at: clock())
        receiver.onAck = { [weak self, weak stream] frame, decodeMs, keyframe in
            guard let self, let stream, self.stream === stream else { return }
            _ = self.send("screen.ack", screenAckPayload(streamID: stream.streamID, frame: frame, decodeMs: decodeMs, keyframe: keyframe))
        }
        receiver.onSize = { [weak self, weak stream] width, height in
            guard let self, let stream, self.stream === stream else { return }
            self.presenter?.resize(width: width, height: height)
        }
        self.stream = stream
        showing = phoneName
        if let sink = presenter?.open(phoneName: phoneName, width: start.width, height: start.height, onStop: { [weak self, weak stream] in
            guard let self, let stream, self.stream === stream else { return }
            self.stop(stream, reason: .closed, message: nil)
        }) {
            receiver.attach(sink)
        }
        _ = send("screen.started", screenStartedPayload(streamID: start.header.streamID))
        NSLog("PLUGIN screen started stream=%@ size=%ldx%ld fps=%ld bitrate=%ld", String(start.header.streamID.prefix(8)),
              start.width, start.height, start.fps, start.bitrate)
        if usesTimer && ticker == nil {
            ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.tick() }
            }
        }
    }

    private func stop(_ stream: Stream, reason: ScreenStopReason, message: String?) {
        _ = send("screen.stop", screenStopPayload(streamID: stream.streamID, reason: reason))
        NSLog("PLUGIN screen stopped here stream=%@ reason=%@", String(stream.streamID.prefix(8)), reason.rawValue)
        end(stream, message: message)
    }

    private func end(_ stream: Stream, message: String?) {
        guard self.stream === stream else { return }
        self.stream = nil
        showing = nil
        stream.receiver.finish()
        presenter?.close(message: message)
    }
}

/// The line the window shows before it closes when the phone ends the stream.
func screenEndedText(_ reason: ScreenStopReason, phone: String) -> String {
    switch reason {
    case .user, .ended: String(format: L10n.text("screen.ended.phone", fallback: "Sharing stopped on %@"), phone)
    case .screenOff: String(format: L10n.text("screen.ended.screenOff", fallback: "The screen of %@ turned off"), phone)
    case .disabled: String(format: L10n.text("screen.ended.disabled", fallback: "Screen sharing is off on %@"), phone)
    case .timeout: String(format: L10n.text("screen.ended.timeout", fallback: "Lost the screen of %@"), phone)
    case .closed, .invalid, .failed: String(format: L10n.text("screen.ended.failed", fallback: "The screen of %@ stopped"), phone)
    }
}

/*
 * One stream's frames, off the main thread: reassembled and decoded on the stream's serial queue in the
 * order the session received them; decoded frames go straight to the display layer. Acks and size
 * changes go back to the main queue, which owns the window and sends this Mac's messages.
 */
final class ScreenStreamReceiver: @unchecked Sendable {
    let streamID: String

    /// Called on the main queue.
    var onAck: (_ frame: UInt32, _ decodeMs: Int, _ keyframe: Bool) -> Void = { _, _, _ in }
    var onSize: (_ width: Int, _ height: Int) -> Void = { _, _ in }

    private let queue: DispatchQueue
    private var assembler = ScreenFrameAssembler()
    private let decoder = ScreenVideoDecoder()
    private var sink: ScreenDisplaySink?
    private var waitingForKey = true
    private var lastKeyRequest: UInt64 = 0
    private var finished = false
    private var size: (Int, Int)?

    init(streamID: String) {
        self.streamID = streamID
        queue = DispatchQueue(label: "dev.bridgey.screen.\(streamID.prefix(8))", qos: .userInteractive)
    }

    func attach(_ sink: ScreenDisplaySink) { queue.async { self.sink = sink } }

    /// Stops processing; later parts are dropped.
    func finish() {
        queue.async {
            self.finished = true
            self.decoder.invalidate()
            self.sink = nil
        }
    }

    /// One frame part of this stream, already decrypted and in sequence. Main queue.
    func receive(_ part: ScreenFramePart) {
        queue.async { self.handle(part) }
    }

    /// Runs `body` after everything queued so far (tests).
    func sync(_ body: @escaping () -> Void = {}) { queue.sync(execute: body) }

    private func handle(_ part: ScreenFramePart) {
        guard !finished else { return }
        switch assembler.add(part) {
        case .pending, .ignored:
            break
        case let .lost(frame):
            waitingForKey = true
            acknowledge(frame, decodeMs: 0, wantKey: true)
        case let .complete(frame):
            if waitingForKey && !frame.key {
                acknowledge(frame.frame, decodeMs: 0, wantKey: true)
                return
            }
            switch decoder.decode(frame) {
            case let .decoded(image, decodeMs):
                waitingForKey = false
                sink?.show(image)
                let width = CVPixelBufferGetWidth(image), height = CVPixelBufferGetHeight(image)
                if size.map({ $0 != (width, height) }) ?? true {
                    size = (width, height)
                    let callback = onSize
                    DispatchQueue.main.async { callback(width, height) }
                }
                acknowledge(frame.frame, decodeMs: Int(decodeMs.rounded()), wantKey: false)
            case .needsKeyFrame:
                waitingForKey = true
                acknowledge(frame.frame, decodeMs: 0, wantKey: true)
            }
        }
    }

    /// Acks every complete frame; `keyframe` at most once a second.
    private func acknowledge(_ frame: UInt32, decodeMs: Int, wantKey: Bool) {
        var keyframe = false
        if wantKey {
            let now = DispatchTime.now().uptimeNanoseconds
            if lastKeyRequest == 0 || now - lastKeyRequest >= 1_000_000_000 {
                lastKeyRequest = now
                keyframe = true
            }
        }
        let callback = onAck
        DispatchQueue.main.async { callback(frame, decodeMs, keyframe) }
    }
}
