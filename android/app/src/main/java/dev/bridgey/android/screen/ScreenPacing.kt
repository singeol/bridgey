package dev.bridgey.android.screen

/*
 * The phone's side of pacing a screen stream (docs/protocol.md, "Pacing and backpressure"): which frames go,
 * which are dropped until the next key frame, how the bit rate moves, and the once-a-second stats.
 * Clock values are milliseconds from one monotonic clock; nothing here touches Android.
 */

object ScreenRates {
    const val FPS = 30
    const val START_BITRATE = 4_000_000L
    const val MIN_BITRATE = 300_000L
    const val MAX_BITRATE = 8_000_000L
    const val MAX_FRAMES_IN_FLIGHT = 6
    const val MIN_WINDOW_BYTES = 64 * 1024L
    const val WINDOW_MILLIS = 250L

    /** The round trip may grow this much over its minimum before the bit rate comes down. */
    const val RTT_GROWTH_MILLIS = 80L
    const val DECREASE_SPACING_MILLIS = 500L
    const val INCREASE_AFTER_MILLIS = 2_000L

    /** Key frames are asked of the encoder at most this often. */
    const val KEYFRAME_SPACING_MILLIS = 500L
}

/** What happens to one encoded frame. */
enum class FrameVerdict {
    SEND,

    /** Waiting for a key frame: this one would not decode. Not a sign of congestion. */
    SKIP,

    /** The window is full: congestion. */
    DROP,
}

/**
 * Frames sent and not yet acknowledged, and the rule for the next one: at most 6 frames and at most
 * max(64 KiB, 250 ms at the bit rate) unacknowledged. An empty pipe always takes the next frame, so a
 * key frame bigger than the window still goes. After a drop every frame up to the next key frame is
 * dropped too (they would not decode), and a key frame is asked for.
 */
class ScreenPacer {
    private data class InFlight(val frame: Long, val bytes: Int, val captureMillis: Long, var writtenAtMillis: Long)

    private val inFlight = ArrayDeque<InFlight>()
    private var inFlightBytes = 0L

    /** True after a drop, or before the first frame: only a key frame may go next. */
    var waitingForKey = true
        private set

    val framesInFlight: Int @Synchronized get() = inFlight.size
    val bytesInFlight: Long @Synchronized get() = inFlightBytes

    fun windowBytes(bitrate: Long): Long = maxOf(ScreenRates.MIN_WINDOW_BYTES, bitrate * ScreenRates.WINDOW_MILLIS / 1000 / 8)

    /** Whether to send an encoded frame of [bytes] now. */
    @Synchronized fun admit(bytes: Int, key: Boolean, bitrate: Long): FrameVerdict {
        if (waitingForKey && !key) return FrameVerdict.SKIP
        val fits = inFlight.isEmpty() ||
            (inFlight.size < ScreenRates.MAX_FRAMES_IN_FLIGHT && inFlightBytes + bytes <= windowBytes(bitrate))
        if (!fits) {
            waitingForKey = true
            return FrameVerdict.DROP
        }
        waitingForKey = false
        return FrameVerdict.SEND
    }

    /** The encoder or the Mac needs a fresh key frame: nothing else goes until one does. */
    @Synchronized fun requireKey() { waitingForKey = true }

    /** A frame starts going out; [captureMillis] is when it was captured. */
    @Synchronized fun sending(frame: Long, bytes: Int, captureMillis: Long, nowMillis: Long) {
        inFlight.addLast(InFlight(frame, bytes, captureMillis, nowMillis))
        inFlightBytes += bytes
    }

    /** Its last part is written. */
    @Synchronized fun written(frame: Long, nowMillis: Long) {
        inFlight.lastOrNull { it.frame == frame }?.writtenAtMillis = nowMillis
    }

    /** A frame could not be written at all (the socket failed): it is not in flight. */
    @Synchronized fun unsent(frame: Long) {
        inFlight.removeAll { (it.frame == frame).also { gone -> if (gone) inFlightBytes -= it.bytes } }
    }

    /** One acknowledgement: what it says about the link, or null when it acknowledges nothing new. */
    data class AckSample(val rttMillis: Long, val latencyMillis: Long)

    /**
     * The Mac has frame [frame] and every earlier one, decoded in [decodeMs]. The round trip is from the frame's
     * last byte written to the ack, minus decoding; the latency is from capture to the ack, minus half of that
     * round trip (the ack's way back).
     */
    @Synchronized fun acknowledge(frame: Long, decodeMs: Long, nowMillis: Long): AckSample? {
        var newest: InFlight? = null
        while (inFlight.isNotEmpty() && inFlight.first().frame <= frame) {
            newest = inFlight.removeFirst()
            inFlightBytes -= newest.bytes
        }
        val acked = newest ?: return null
        if (acked.frame != frame) return null
        val rtt = (nowMillis - acked.writtenAtMillis - decodeMs).coerceAtLeast(0)
        val latency = (nowMillis - acked.captureMillis - rtt / 2).coerceAtLeast(0)
        return AckSample(rtt, latency)
    }

    @Synchronized fun clear() {
        inFlight.clear()
        inFlightBytes = 0
        waitingForKey = true
    }

    /** When the oldest frame still waiting for its ack was written, or null with nothing in flight. */
    @Synchronized fun oldestWrittenAtMillis(): Long? = inFlight.firstOrNull()?.writtenAtMillis
}

/**
 * The bit rate: down by a quarter on a drop or when the round trip grows 80 ms over its minimum (at most
 * every 500 ms), up by 15 percent after 2 seconds without either, between 300 kb/s and 8 Mb/s.
 */
class ScreenBitrate(startMillis: Long, initial: Long = ScreenRates.START_BITRATE) {
    var bitrate: Long = initial.coerceIn(ScreenRates.MIN_BITRATE, ScreenRates.MAX_BITRATE)
        private set
    var minRttMillis: Long? = null
        private set
    private var lastDecreaseAt = Long.MIN_VALUE / 2
    private var calmSince = startMillis

    /** Returns the new bit rate when it changed. */
    @Synchronized fun onRtt(rttMillis: Long, nowMillis: Long): Long? {
        val min = minOf(minRttMillis ?: rttMillis, rttMillis)
        minRttMillis = min
        return if (rttMillis > min + ScreenRates.RTT_GROWTH_MILLIS) congested(nowMillis) else grow(nowMillis)
    }

    @Synchronized fun onDrop(nowMillis: Long): Long? = congested(nowMillis)

    /** Called on the stats tick, so the rate grows even when acks are few. */
    @Synchronized fun tick(nowMillis: Long): Long? = grow(nowMillis)

    private fun congested(nowMillis: Long): Long? {
        calmSince = nowMillis
        if (nowMillis - lastDecreaseAt < ScreenRates.DECREASE_SPACING_MILLIS) return null
        lastDecreaseAt = nowMillis
        return set(bitrate * 3 / 4)
    }

    private fun grow(nowMillis: Long): Long? {
        if (nowMillis - calmSince < ScreenRates.INCREASE_AFTER_MILLIS) return null
        calmSince = nowMillis
        return set(bitrate * 115 / 100)
    }

    private fun set(value: Long): Long? {
        val next = value.coerceIn(ScreenRates.MIN_BITRATE, ScreenRates.MAX_BITRATE)
        if (next == bitrate) return null
        bitrate = next
        return next
    }
}

/** Counts for the current second; [take] gives the stats and starts the next second. */
class ScreenStatsWindow(private var startedAtMillis: Long) {
    private var frames = 0
    private var dropped = 0
    private var bytes = 0L
    private var latencySum = 0L
    private var latencyCount = 0
    private var lastLatency = 0L
    private var lastRtt = 0L

    @Synchronized fun sent(frameBytes: Int) { frames++; bytes += frameBytes }
    @Synchronized fun dropped() { dropped++ }
    @Synchronized fun sample(sample: ScreenPacer.AckSample) {
        latencySum += sample.latencyMillis
        latencyCount++
        lastLatency = sample.latencyMillis
        lastRtt = sample.rttMillis
    }

    @Synchronized fun take(nowMillis: Long, bitrate: Long): ScreenStats {
        val elapsed = (nowMillis - startedAtMillis).coerceAtLeast(1)
        val stats = ScreenStats(
            latencyMs = if (latencyCount > 0) latencySum / latencyCount else lastLatency,
            rttMs = lastRtt,
            bitrate = bitrate,
            sentBitrate = bytes * 8 * 1000 / elapsed,
            fps = ((frames * 1000L + elapsed / 2) / elapsed).toInt(),
            dropped = dropped,
        )
        startedAtMillis = nowMillis
        frames = 0
        dropped = 0
        bytes = 0
        latencySum = 0
        latencyCount = 0
        return stats
    }
}

/** The peer's screen.* messages in one session: each sequence must be higher than the last accepted one. */
class ScreenSequence {
    private var last = 0L

    @Synchronized fun accept(sequence: Long): Boolean {
        if (sequence !in 1..ScreenLimits.MAX_SEQUENCE || sequence <= last) return false
        last = sequence
        return true
    }
}

/** The capture size: the long side at most [cap], aspect kept, both sides multiples of 16 (at least 16). */
fun screenCaptureSize(sourceWidth: Int, sourceHeight: Int, cap: Int): Pair<Int, Int> {
    val long = maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
    val scale = minOf(1.0, cap.toDouble() / long)
    fun side(value: Int) = (((value * scale).toInt() / 16) * 16).coerceIn(16, 4096)
    return side(sourceWidth) to side(sourceHeight)
}

/** The long-side caps tried in order until the encoder supports the size. */
val SCREEN_SIZE_CAPS = listOf(1600, 1280, 960)
