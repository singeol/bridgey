package dev.bridgey.android.screen

import java.nio.ByteBuffer
import java.util.UUID

/*
 * Screen sharing, phone to Mac (`screen`, docs/protocol.md): the wire format. Pure Kotlin with no
 * Android or org.json types, so every rule is covered by JVM tests.
 */

object ScreenKinds {
    const val START = "screen.start"
    const val STARTED = "screen.started"
    const val FRAME = "screen.frame"
    const val ACK = "screen.ack"
    const val STATS = "screen.stats"
    const val STOP = "screen.stop"
}

object ScreenLimits {
    const val MESSAGE_MAX_BYTES = 512
    const val START_MAX_BYTES = 1_024
    const val HEADER_BYTES = 40
    const val PART_MAX_BYTES = 32_768
    const val MAX_PARTS = 64
    const val MAX_FRAME_NUMBER = 4_294_967_295L
    const val MAX_SEQUENCE = 9_007_199_254_740_991L
    const val MAX_MILLIS = 60_000L
    const val MAX_BITRATE = 50_000_000L

    /** The phone gives up after 5 seconds without screen.started, or 6 seconds without an ack while frames are out. */
    const val STARTED_TIMEOUT_MILLIS = 5_000L
    const val ACK_TIMEOUT_MILLIS = 6_000L
}

enum class ScreenStopReason(val wire: String) {
    USER("user"),
    CLOSED("closed"),
    ENDED("ended"),
    SCREEN_OFF("screen_off"),
    DISABLED("disabled"),
    INVALID("invalid"),
    TIMEOUT("timeout"),
    FAILED("failed"),
    ;

    companion object {
        /** An unknown or missing reason reads as failed. */
        fun fromWire(value: Any?): ScreenStopReason = entries.firstOrNull { it.wire == value } ?: FAILED
    }
}

/** An integer JSON value (org.json gives Int or Long); null for anything else, including booleans and decimals. */
internal fun screenInteger(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

/** A UUID string in canonical 8-4-4-4-12 form, lowercased; null otherwise. */
internal fun canonicalUuid(value: Any?): String? {
    val text = value as? String ?: return null
    if (text.length != 36) return null
    val uuid = runCatching { UUID.fromString(text) }.getOrNull() ?: return null
    return uuid.toString().takeIf { it.equals(text, ignoreCase = true) }
}

/** What the Mac sends on a stream, decoded. */
sealed interface ScreenMacMessage {
    val streamId: String
    val sequence: Long

    data class Started(override val streamId: String, override val sequence: Long) : ScreenMacMessage

    data class Ack(
        override val streamId: String,
        override val sequence: Long,
        val frame: Long,
        val decodeMs: Long,
        val keyframe: Boolean,
    ) : ScreenMacMessage

    data class Stop(override val streamId: String, override val sequence: Long, val reason: ScreenStopReason) : ScreenMacMessage
}

/**
 * screen.started, screen.ack or screen.stop from the Mac, read through [field] (a parsed JSON object's
 * lookup). Null for anything malformed: it is dropped, never answered.
 */
fun decodeScreenMacMessage(kind: String, field: (String) -> Any?): ScreenMacMessage? {
    if (screenInteger(field("version")) != 1L) return null
    val streamId = canonicalUuid(field("streamId")) ?: return null
    val sequence = screenInteger(field("sequence"))?.takeIf { it in 1..ScreenLimits.MAX_SEQUENCE } ?: return null
    return when (kind) {
        ScreenKinds.STARTED -> ScreenMacMessage.Started(streamId, sequence)
        ScreenKinds.ACK -> {
            val frame = screenInteger(field("frame"))?.takeIf { it in 1..ScreenLimits.MAX_FRAME_NUMBER } ?: return null
            val decodeMs = screenInteger(field("decodeMs"))?.takeIf { it in 0..ScreenLimits.MAX_MILLIS } ?: return null
            val keyframe = when (val value = field("keyframe")) {
                null -> false
                is Boolean -> value
                else -> return null
            }
            ScreenMacMessage.Ack(streamId, sequence, frame, decodeMs, keyframe)
        }
        ScreenKinds.STOP -> ScreenMacMessage.Stop(streamId, sequence, ScreenStopReason.fromWire(field("reason")))
        else -> null
    }
}

/** What the phone puts in screen.start. */
data class ScreenStartInfo(val width: Int, val height: Int, val fps: Int, val bitrate: Long)

private fun streamBase(streamId: String, sequence: Long): MutableMap<String, Any> =
    linkedMapOf("version" to 1, "streamId" to streamId, "sequence" to sequence)

fun screenStartPayload(streamId: String, sequence: Long, info: ScreenStartInfo): Map<String, Any> =
    streamBase(streamId, sequence).apply {
        put("codec", "h264")
        put("width", info.width)
        put("height", info.height)
        put("fps", info.fps)
        put("bitrate", info.bitrate)
    }

/** One second of the phone's view of the stream, for screen.stats and the log. */
data class ScreenStats(
    val latencyMs: Long,
    val rttMs: Long,
    val bitrate: Long,
    val sentBitrate: Long,
    val fps: Int,
    val dropped: Int,
) {
    /** Clamped to the ranges the Mac accepts. */
    fun bounded(): ScreenStats = ScreenStats(
        latencyMs.coerceIn(0, ScreenLimits.MAX_MILLIS),
        rttMs.coerceIn(0, ScreenLimits.MAX_MILLIS),
        bitrate.coerceIn(0, ScreenLimits.MAX_BITRATE),
        sentBitrate.coerceIn(0, ScreenLimits.MAX_BITRATE),
        fps.coerceIn(0, 120),
        dropped.coerceIn(0, 1000),
    )
}

fun screenStatsPayload(streamId: String, sequence: Long, stats: ScreenStats): Map<String, Any> {
    val s = stats.bounded()
    return streamBase(streamId, sequence).apply {
        put("latencyMs", s.latencyMs)
        put("rttMs", s.rttMs)
        put("bitrate", s.bitrate)
        put("sentBitrate", s.sentBitrate)
        put("fps", s.fps)
        put("dropped", s.dropped)
    }
}

fun screenStopPayload(streamId: String, sequence: Long, reason: ScreenStopReason): Map<String, Any> =
    streamBase(streamId, sequence).apply { put("reason", reason.wire) }

/** The 40-byte binary header of one screen.frame part. */
data class ScreenFrameHeader(
    val streamId: String,
    val sequence: Long,
    val frame: Long,
    val part: Int,
    val key: Boolean,
    val parameterSets: Boolean,
    val last: Boolean,
    val captureMicros: Long,
) {
    val flags: Int get() = (if (key) 1 else 0) or (if (parameterSets) 2 else 0) or (if (last) 4 else 0)
}

object ScreenFrameCodec {
    /** The header, then [length] bytes of [payload] from [offset] (1 to 32,768), big-endian as documented. */
    fun encode(header: ScreenFrameHeader, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): ByteArray {
        require(length in 1..ScreenLimits.PART_MAX_BYTES && header.part in 0 until ScreenLimits.MAX_PARTS)
        require(header.sequence in 1..ScreenLimits.MAX_SEQUENCE && header.frame in 1..ScreenLimits.MAX_FRAME_NUMBER)
        require(header.captureMicros >= 0)
        val uuid = UUID.fromString(header.streamId)
        return ByteBuffer.allocate(ScreenLimits.HEADER_BYTES + length).apply {
            put(1.toByte())
            put(header.flags.toByte())
            putShort(header.part.toShort())
            putLong(uuid.mostSignificantBits)
            putLong(uuid.leastSignificantBits)
            putLong(header.sequence)
            putInt(header.frame.toInt())
            putLong(header.captureMicros)
            put(payload, offset, length)
        }.array()
    }

    /** How many parts an access unit of [size] bytes needs. */
    fun partCount(size: Int): Int = (size + ScreenLimits.PART_MAX_BYTES - 1) / ScreenLimits.PART_MAX_BYTES
}

/** Annex B helpers: start codes and NAL unit types. */
object AnnexB {
    /** 4 or 3 when a start code (00 00 00 01 or 00 00 01) begins at [index], else 0. */
    fun startCodeLength(data: ByteArray, index: Int): Int = when {
        index + 4 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
            data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> 4
        index + 3 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte() -> 3
        else -> 0
    }

    /** The NAL unit types in an Annex B buffer, in order. */
    fun nalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        while (i < data.size) {
            val length = startCodeLength(data, i)
            if (length > 0) {
                if (i + length < data.size) types += data[i + length].toInt() and 0x1F
                i += length
            } else {
                i++
            }
        }
        return types
    }

    /** The access unit starts with an SPS (type 7). */
    fun startsWithSps(data: ByteArray): Boolean = nalTypes(data).firstOrNull() == 7
}
