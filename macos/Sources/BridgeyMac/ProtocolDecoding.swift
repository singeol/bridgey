import Foundation

// Bridgey Protocol v1 decoding shared by the session and the tests: framing, nesting, replay and the
// payload decoders that protocol/test-vectors/protocol-v1.json pins for both platforms. The rules are in
// docs/protocol.md (Version 1 compatibility contract); Android has the same functions in
// ProtocolDecoding.kt.

/// The deepest JSON nesting a frame or payload may have (the outer object counts as 1).
let maximumProtocolJSONDepth = 32

/// The largest integer JSON carries exactly on both platforms (2^53 - 1).
let jsonMaximumSafeInteger = 9_007_199_254_740_991

/// True when `data` is one strict RFC 8259 JSON object nested at most `maxDepth` deep. JSONSerialization
/// alone may also accept input that Android rejects (such as a trailing comma), so both platforms run this
/// check first and accept exactly the same frames.
func isStrictJSONObject(_ data: Data, maxDepth: Int = maximumProtocolJSONDepth) -> Bool {
    var parser = StrictJSON(bytes: [UInt8](data), maxDepth: maxDepth)
    return parser.objectDocument()
}

private struct StrictJSON {
    let bytes: [UInt8]
    let maxDepth: Int
    var index = 0
    var depth = 0

    init(bytes: [UInt8], maxDepth: Int) {
        self.bytes = bytes
        self.maxDepth = maxDepth
    }

    mutating func objectDocument() -> Bool {
        skipSpace()
        guard peek == 0x7B, value() else { return false }
        skipSpace()
        return index == bytes.count
    }

    private var peek: UInt8? { index < bytes.count ? bytes[index] : nil }

    private mutating func skipSpace() {
        while let byte = peek, byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D { index += 1 }
    }

    private mutating func value() -> Bool {
        skipSpace()
        switch peek {
        case 0x7B: return container(close: 0x7D, object: true)
        case 0x5B: return container(close: 0x5D, object: false)
        case 0x22: return string()
        case 0x74: return literal("true")
        case 0x66: return literal("false")
        case 0x6E: return literal("null")
        default: return number()
        }
    }

    private mutating func container(close: UInt8, object: Bool) -> Bool {
        depth += 1
        guard depth <= maxDepth else { return false }
        index += 1
        skipSpace()
        if peek == close {
            index += 1
            depth -= 1
            return true
        }
        while true {
            skipSpace()
            if object {
                guard string() else { return false }
                skipSpace()
                guard peek == 0x3A else { return false }
                index += 1
            }
            guard value() else { return false }
            skipSpace()
            switch peek {
            case 0x2C: index += 1
            case close:
                index += 1
                depth -= 1
                return true
            default: return false
            }
        }
    }

    private mutating func literal(_ word: String) -> Bool {
        let expected = Array(word.utf8)
        guard index + expected.count <= bytes.count, Array(bytes[index..<index + expected.count]) == expected else { return false }
        index += expected.count
        return true
    }

    private func isDigit(_ byte: UInt8?) -> Bool { byte.map { (0x30...0x39).contains($0) } ?? false }

    private mutating func digits() {
        while isDigit(peek) { index += 1 }
    }

    private mutating func number() -> Bool {
        if peek == 0x2D { index += 1 }
        if peek == 0x30 {
            index += 1
        } else if let byte = peek, (0x31...0x39).contains(byte) {
            digits()
        } else {
            return false
        }
        if peek == 0x2E {
            index += 1
            guard isDigit(peek) else { return false }
            digits()
        }
        if peek == 0x65 || peek == 0x45 {
            index += 1
            if peek == 0x2B || peek == 0x2D { index += 1 }
            guard isDigit(peek) else { return false }
            digits()
        }
        return true
    }

    private mutating func string() -> Bool {
        guard peek == 0x22 else { return false }
        index += 1
        while let byte = peek {
            index += 1
            switch byte {
            case 0x22: return true
            case 0x00..<0x20: return false
            case 0x5C:
                switch peek {
                case 0x22, 0x5C, 0x2F, 0x62, 0x66, 0x6E, 0x72, 0x74: index += 1
                case 0x75:
                    guard let unit = hex(), !(0xDC00...0xDFFF).contains(unit) else { return false }
                    if (0xD800...0xDBFF).contains(unit) {
                        guard peek == 0x5C else { return false }
                        index += 1
                        guard let low = hex(), (0xDC00...0xDFFF).contains(low) else { return false }
                    }
                default: return false
                }
            default: break
            }
        }
        return false
    }

    /// At `uXXXX` after a backslash: the code unit, or nil.
    private mutating func hex() -> Int? {
        guard peek == 0x75, index + 5 <= bytes.count else { return nil }
        let digits = bytes[index + 1..<index + 5]
        guard digits.allSatisfy({ (0x30...0x39).contains($0) || (0x41...0x46).contains($0) || (0x61...0x66).contains($0) }),
              let unit = Int(String(decoding: digits, as: UTF8.self), radix: 16) else { return nil }
        index += 5
        return unit
    }
}

/// A strict JSON object (see `isStrictJSONObject`), or nil for anything else.
func strictJSONObject(_ data: Data) -> [String: Any]? {
    guard isStrictJSONObject(data) else { return nil }
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
}

/// An integral JSON number within +-(2^53 - 1), read as Android reads one: 3, 3.0 and 3e0 are 3;
/// 3.5, true, "3" and larger numbers are not integers.
func jsonInteger(_ value: Any?) -> Int? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    let double = number.doubleValue
    guard double.isFinite, double.rounded() == double, abs(double) <= Double(jsonMaximumSafeInteger) else { return nil }
    return number.intValue
}

/// A JSON boolean. Numbers, which also bridge to NSNumber, are not booleans.
func jsonBool(_ value: Any?) -> Bool? {
    guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
    return number.boolValue
}

/// Decodes one frame (without its line ending), or throws when the frame must close the session: empty or
/// over 65,536 bytes, not a strict JSON object, nested deeper than 32, `kind` missing, empty or over 64
/// bytes, `sessionId` missing, over 128 bytes or empty (allowed only in `pairing.cancel`), or an envelope
/// field of the wrong type. Unknown fields are ignored; an empty or null optional string counts as absent.
func decodeProtocolMessage(_ data: Data) throws -> PairingMessage {
    guard !data.isEmpty, data.count <= maximumProtocolFrameBytes, let object = strictJSONObject(data) else {
        throw PairingError.invalidMessage
    }
    func string(_ key: String) throws -> String? {
        switch object[key] {
        case nil, is NSNull: return nil
        case let value as String: return value
        default: throw PairingError.invalidMessage
        }
    }
    func optional(_ key: String) throws -> String? { try string(key).flatMap { $0.isEmpty ? nil : $0 } }
    guard let kind = try string("kind"), !kind.isEmpty, kind.utf8.count <= 64,
          let sessionId = try string("sessionId"), sessionId.utf8.count <= 128,
          !sessionId.isEmpty || kind == "pairing.cancel" else {
        throw PairingError.invalidMessage
    }
    var sequence: Int64?
    switch object["sequence"] {
    case nil, is NSNull: sequence = nil
    case let value:
        guard let integer = jsonInteger(value) else { throw PairingError.invalidMessage }
        sequence = Int64(integer)
    }
    return PairingMessage(
        kind: kind,
        sessionId: sessionId,
        deviceId: try optional("deviceId"),
        deviceName: try optional("deviceName"),
        publicKey: try optional("publicKey"),
        identityKey: try optional("identityKey"),
        proof: try optional("proof"),
        signature: try optional("signature"),
        messageId: try optional("messageId"),
        nonce: try optional("nonce"),
        ciphertext: try optional("ciphertext"),
        transferId: try optional("transferId"),
        sequence: sequence
    )
}

/// Splits the byte stream of one connection into frames. A frame ends at LF, and one CR right before the
/// LF is dropped, as on Android. A partial frame over the frame limit is an error, so memory stays bounded.
struct ProtocolFrameReader {
    private(set) var buffer = Data()

    mutating func append(_ data: Data) { buffer.append(data) }

    /// The next complete frame, or nil when no complete frame is buffered. Throws for a malformed frame.
    mutating func next() throws -> PairingMessage? {
        guard let newline = buffer.firstIndex(of: 0x0A) else {
            guard !overLimit else { throw PairingError.invalidMessage }
            return nil
        }
        var line = Data(buffer[buffer.startIndex..<newline])
        buffer.removeSubrange(buffer.startIndex...newline)
        if line.last == 0x0D { line.removeLast() }
        return try decodeProtocolMessage(line)
    }

    /// The buffered partial frame is already over the limit. One byte over is allowed only for the CR
    /// that may end the frame.
    var overLimit: Bool {
        buffer.firstIndex(of: 0x0A) == nil && buffer.count > maximumProtocolFrameBytes + (buffer.last == 0x0D ? 1 : 0)
    }
}

/// The most recent `capacity` accepted message IDs of one session, oldest evicted first.
struct ReplayWindow {
    let capacity: Int
    private var ids = Set<String>()
    private var order: [String] = []
    private var head = 0

    init(capacity: Int = 256) { self.capacity = capacity }

    var count: Int { ids.count }

    /// False for an ID already in the window; otherwise remembers it.
    mutating func accept(_ id: String) -> Bool {
        guard ids.insert(id).inserted else { return false }
        order.append(id)
        if ids.count > capacity {
            ids.remove(order[head])
            head += 1
            // Compact now and then so the order list stays bounded too.
            if head >= capacity {
                order.removeFirst(head)
                head = 0
            }
        }
        return true
    }
}

// MARK: Payloads

/// What a received features.update means for the session.
enum FeatureStateDecoding: Equatable {
    /// Close the session: not strict JSON, no `features` object, a missing original key or a non-boolean
    /// known key.
    case invalid
    /// A payload version this client does not know: keep the session and the previous state.
    case ignored
    /// The peer's feature map. Keys this client does not know are ignored, whatever their value.
    case state([BridgeyFeature: Bool])
}

func decodeFeatureState(_ plaintext: Data) -> FeatureStateDecoding {
    guard let object = strictJSONObject(plaintext), let values = object["features"] as? [String: Any] else { return .invalid }
    guard jsonInteger(object["version"]) == 1 else { return .ignored }
    var features: [BridgeyFeature: Bool] = [:]
    for feature in BridgeyFeature.allCases {
        guard let value = values[feature.rawValue] else {
            if featureEnabledByLegacyPeer(feature) { return .invalid }
            features[feature] = false
            continue
        }
        guard let flag = jsonBool(value) else { return .invalid }
        features[feature] = flag
    }
    return .state(features)
}

/// A battery.update payload, or nil when it is malformed or out of range.
func decodeBatteryStatus(_ plaintext: Data) -> RemoteBatteryStatus? {
    guard let object = strictJSONObject(plaintext),
          let level = jsonInteger(object["level"]), (0...100).contains(level),
          let charging = jsonBool(object["isCharging"]) else { return nil }
    return RemoteBatteryStatus(level: level, isCharging: charging)
}

/// A clipboard.rich payload, or nil when it is malformed, empty or over 32 KiB.
func decodeRichClipboard(_ plaintext: Data) -> RichClipboardContent? {
    guard let object = strictJSONObject(plaintext),
          jsonInteger(object["version"]) == 1,
          let text = object["text"] as? String, let html = object["html"] as? String else { return nil }
    return RichClipboardContent(text: text, html: html)
}
