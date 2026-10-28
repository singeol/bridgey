package dev.bridgey.android

import kotlin.math.abs
import kotlin.math.floor
import org.json.JSONException
import org.json.JSONObject

/*
 * Bridgey Protocol v1 decoding shared by the session and the tests: strict JSON, nesting, replay and the
 * payload decoders that protocol/test-vectors/protocol-v1.json pins for both platforms. The rules are in
 * docs/protocol.md (Version 1 compatibility contract); the Mac has the same functions in
 * ProtocolDecoding.swift.
 */

/** The deepest JSON nesting a frame or payload may have (the outer object counts as 1). */
internal const val MAX_PROTOCOL_JSON_DEPTH = 32

/** The largest integer JSON carries exactly on both platforms (2^53 - 1). */
internal const val JSON_MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/**
 * True when [text] is one strict RFC 8259 JSON object nested at most [maxDepth] deep. org.json alone also
 * accepts comments, single quotes, unquoted names and trailing text, which the Mac rejects, so both
 * platforms run this check first and accept exactly the same frames.
 */
internal fun isStrictJsonObject(text: String, maxDepth: Int = MAX_PROTOCOL_JSON_DEPTH): Boolean =
    StrictJson(text, maxDepth).objectDocument()

private class StrictJson(private val text: String, private val maxDepth: Int) {
    private var index = 0
    private var depth = 0

    fun objectDocument(): Boolean {
        skipSpace()
        if (peek() != '{' || !value()) return false
        skipSpace()
        return index == text.length
    }

    private fun peek(): Char? = if (index < text.length) text[index] else null

    private fun skipSpace() {
        while (index < text.length && text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) index++
    }

    private fun value(): Boolean {
        skipSpace()
        return when (peek()) {
            '{' -> container('}') { string() && run { skipSpace(); peek() == ':' } && run { index++; value() } }
            '[' -> container(']') { value() }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    private inline fun container(close: Char, member: () -> Boolean): Boolean {
        if (++depth > maxDepth) return false
        index++
        skipSpace()
        if (peek() == close) {
            index++
            depth--
            return true
        }
        while (true) {
            skipSpace()
            if (!member()) return false
            skipSpace()
            when (peek()) {
                ',' -> index++
                close -> {
                    index++
                    depth--
                    return true
                }
                else -> return false
            }
        }
    }

    private fun literal(word: String): Boolean {
        if (!text.startsWith(word, index)) return false
        index += word.length
        return true
    }

    private fun digits() {
        while (peek()?.let { it in '0'..'9' } == true) index++
    }

    private fun number(): Boolean {
        if (peek() == '-') index++
        when (peek()) {
            '0' -> index++
            in '1'..'9' -> digits()
            else -> return false
        }
        if (peek() == '.') {
            index++
            if (peek() !in '0'..'9') return false
            digits()
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            if (peek() !in '0'..'9') return false
            digits()
        }
        return true
    }

    private fun string(): Boolean {
        if (peek() != '"') return false
        index++
        while (true) {
            val c = peek() ?: return false
            index++
            when {
                c == '"' -> return true
                c < ' ' -> return false
                c == '\\' -> when (peek()) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> index++
                    'u' -> {
                        val unit = hex() ?: return false
                        if (unit in 0xDC00..0xDFFF) return false
                        if (unit in 0xD800..0xDBFF) {
                            if (peek() != '\\') return false
                            index++
                            if ((hex() ?: return false) !in 0xDC00..0xDFFF) return false
                        }
                    }
                    else -> return false
                }
                c.isSurrogate() -> {
                    // Decoded UTF-8 only has surrogates in valid pairs; anything else is not text.
                    if (!c.isHighSurrogate() || peek()?.isLowSurrogate() != true) return false
                    index++
                }
            }
        }
    }

    /** At `uXXXX` after a backslash: the code unit, or null. */
    private fun hex(): Int? {
        if (peek() != 'u' || index + 5 > text.length) return null
        val digits = text.substring(index + 1, index + 5)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        index += 5
        return digits.toInt(16)
    }
}

/** A strict JSON object (see [isStrictJsonObject]), or null for anything else. */
internal fun strictJsonObject(text: String): JSONObject? =
    if (!isStrictJsonObject(text)) null else runCatching { JSONObject(text) }.getOrNull()

internal fun strictJsonObject(bytes: ByteArray): JSONObject? = strictJsonObject(bytes.toString(Charsets.UTF_8))

/**
 * An integral JSON number within +-(2^53 - 1), read as the Mac reads one: 3, 3.0 and 3e0 are 3; 3.5,
 * true, "3" and larger numbers are not integers.
 */
internal fun jsonInteger(value: Any?): Long? {
    val number = value as? Number ?: return null
    val result = when (number) {
        is Int, is Long, is Short, is Byte -> number.toLong()
        is java.math.BigInteger -> if (number.bitLength() < 63) number.toLong() else return null
        else -> {
            val double = number.toDouble()
            if (!double.isFinite() || double != floor(double) || abs(double) > JSON_MAX_SAFE_INTEGER) return null
            double.toLong()
        }
    }
    return result.takeIf { abs(it) <= JSON_MAX_SAFE_INTEGER }
}

/** The most recent [capacity] accepted message IDs of one session, oldest evicted first. */
internal class ReplayWindow(private val capacity: Int = 256) {
    private val ids = LinkedHashSet<String>()

    val size: Int @Synchronized get() = ids.size

    /** False for an ID already in the window; otherwise remembers it. */
    @Synchronized fun accept(id: String): Boolean {
        if (!ids.add(id)) return false
        while (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}

/**
 * Decodes one frame (without its line ending), or throws [JSONException] when the frame must close the
 * session: not a strict JSON object, nested deeper than 32, `kind` missing, empty or over 64 bytes,
 * `sessionId` missing, over 128 bytes or empty (allowed only in `pairing.cancel`), or an envelope field of
 * the wrong type. Unknown fields are ignored; an empty or null optional string counts as absent.
 */
internal fun decodeProtocolMessage(line: String): Message {
    if (!isStrictJsonObject(line)) throw JSONException("not a strict JSON object")
    val json = JSONObject(line)
    fun string(name: String): String? = when (val value = json.opt(name)) {
        null, JSONObject.NULL -> null
        is String -> value
        else -> throw JSONException("$name is not a string")
    }
    fun optional(name: String): String? = string(name)?.takeIf(String::isNotEmpty)
    val kind = string("kind")?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 64 }
        ?: throw JSONException("invalid kind")
    val sessionId = string("sessionId")
        ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 128 && (it.isNotEmpty() || kind == "pairing.cancel") }
        ?: throw JSONException("invalid sessionId")
    val sequence = when (val value = json.opt("sequence")) {
        null, JSONObject.NULL -> null
        else -> jsonInteger(value) ?: throw JSONException("sequence is not an integer")
    }
    return Message(
        kind = kind,
        sessionId = sessionId,
        deviceId = optional("deviceId"),
        deviceName = optional("deviceName"),
        publicKey = optional("publicKey"),
        identityKey = optional("identityKey"),
        proof = optional("proof"),
        signature = optional("signature"),
        messageId = optional("messageId"),
        nonce = optional("nonce"),
        ciphertext = optional("ciphertext"),
        transferId = optional("transferId"),
        sequence = sequence,
    )
}

/** What a received features.update means for the session. */
internal sealed interface FeatureStateDecoding {
    /** Close the session: not strict JSON, no `features` object, a missing original key or a non-boolean known key. */
    data object Invalid : FeatureStateDecoding

    /** A payload version this client does not know: keep the session and the previous state. */
    data object Ignored : FeatureStateDecoding

    /** The peer's feature map. Keys this client does not know are ignored, whatever their value. */
    data class State(val features: Map<BridgeyFeature, Boolean>) : FeatureStateDecoding
}

internal fun decodeFeatureState(plaintext: ByteArray): FeatureStateDecoding {
    val payload = strictJsonObject(plaintext) ?: return FeatureStateDecoding.Invalid
    val values = payload.opt("features") as? JSONObject ?: return FeatureStateDecoding.Invalid
    if (jsonInteger(payload.opt("version")) != 1L) return FeatureStateDecoding.Ignored
    val features = BridgeyFeature.entries.associateWith { feature ->
        if (!values.has(feature.key)) {
            if (featureEnabledByLegacyPeer(feature)) return FeatureStateDecoding.Invalid
            return@associateWith false
        }
        values.opt(feature.key) as? Boolean ?: return FeatureStateDecoding.Invalid
    }
    return FeatureStateDecoding.State(features)
}

/** A battery.update payload, or null when it is malformed or out of range. */
internal fun decodeBatteryStatus(plaintext: ByteArray): RemoteBatteryStatus? {
    val payload = strictJsonObject(plaintext) ?: return null
    val level = jsonInteger(payload.opt("level"))?.takeIf { it in 0..100 } ?: return null
    val charging = payload.opt("isCharging") as? Boolean ?: return null
    return RemoteBatteryStatus(level.toInt(), charging)
}
