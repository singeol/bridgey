package dev.bridgey.android.screen

import java.util.UUID

/** How a stream reaches its Mac: messages encrypted on the current session, each given the next session sequence. */
interface ScreenLink {
    /** Writes one JSON stream message built for [sequence]. False when the session is gone. */
    fun sendJson(kind: String, payload: (sequence: Long) -> Map<String, Any>): Boolean

    /** Writes one screen.frame part built for [sequence]. False when the session is gone. */
    fun sendFrame(plaintext: (sequence: Long) -> ByteArray): Boolean

    /** The session is still the current one and screen sharing is still available on both sides. */
    fun alive(): Boolean
}

/** What the stream asks of the encoder. */
interface ScreenEncoderControl {
    fun requestKeyFrame()
    fun setBitrate(bitsPerSecond: Long)
}

enum class ScreenStreamPhase { STARTING, STREAMING, ENDED }

/**
 * One stream from this phone to the connected Mac: screen.start, waiting for screen.started, frames paced
 * on the Mac's acks, screen.stats once a second, and screen.stop. Thread safe: frames come from the
 * encoder thread, acks from the session reader, ticks from a timer.
 */
class ScreenStream(
    val macName: String,
    private val link: ScreenLink,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    val streamId: String = UUID.randomUUID().toString(),
    /** Where screen.stop is written: a stop may come from the main thread, which must not write to sockets. */
    private val stopWriter: (Runnable) -> Unit = { it.run() },
) {
    @Volatile var encoder: ScreenEncoderControl? = null

    /** Called once when the stream ends, with the reason. */
    @Volatile var onEnded: (ScreenStopReason) -> Unit = {}

    /** Called once the Mac answered screen.started. */
    @Volatile var onStarted: () -> Unit = {}

    @Volatile var phase = ScreenStreamPhase.STARTING
        private set

    private val sendLock = Any()
    private val pacer = ScreenPacer()
    private val startedAt = clock()
    private val bitrate = ScreenBitrate(startedAt)
    private val window = ScreenStatsWindow(startedAt)
    private var frameNumber = 0L
    private var lastKeyRequestAt = Long.MIN_VALUE / 2
    @Volatile private var lastAckAt = startedAt

    val currentBitrate: Long get() = bitrate.bitrate

    /** Sends screen.start. False when the session is gone. */
    fun start(width: Int, height: Int): Boolean {
        val info = ScreenStartInfo(width, height, ScreenRates.FPS, bitrate.bitrate)
        val sent = synchronized(sendLock) { link.sendJson(ScreenKinds.START) { screenStartPayload(streamId, it, info) } }
        log("PLUGIN screen start stream=${streamId.take(8)} size=${width}x$height bitrate=${bitrate.bitrate} sent=$sent")
        if (!sent) end(ScreenStopReason.FAILED, tell = false)
        return sent
    }

    /** A message from the Mac, already checked against the session sequence; ignored unless it is for this stream. */
    fun receive(message: ScreenMacMessage) {
        if (message.streamId != streamId || phase == ScreenStreamPhase.ENDED) return
        val now = clock()
        when (message) {
            is ScreenMacMessage.Started -> {
                synchronized(this) {
                    if (phase != ScreenStreamPhase.STARTING) return
                    phase = ScreenStreamPhase.STREAMING
                    lastAckAt = now
                }
                pacer.requireKey()
                requestKey(now, force = true)
                log("PLUGIN screen started stream=${streamId.take(8)}")
                onStarted()
            }
            is ScreenMacMessage.Ack -> {
                lastAckAt = now
                pacer.acknowledge(message.frame, message.decodeMs, now)?.let { sample ->
                    window.sample(sample)
                    bitrate.onRtt(sample.rttMillis, now)?.let { encoder?.setBitrate(it) }
                }
                if (message.keyframe) {
                    pacer.requireKey()
                    requestKey(now, force = false)
                }
            }
            is ScreenMacMessage.Stop -> {
                log("PLUGIN screen stopped by the Mac stream=${streamId.take(8)} reason=${message.reason.wire}")
                end(message.reason, tell = false)
            }
        }
    }

    /**
     * One encoded access unit from the encoder thread. Sent in parts when the pacer admits it; otherwise
     * dropped, with a key frame asked for and the bit rate lowered. Blocks while the parts are written.
     */
    fun onEncodedFrame(accessUnit: ByteArray, key: Boolean, parameterSets: Boolean, captureMillis: Long, captureMicros: Long) {
        if (phase != ScreenStreamPhase.STREAMING || accessUnit.isEmpty()) return
        val now = clock()
        if (accessUnit.size > ScreenLimits.PART_MAX_BYTES * ScreenLimits.MAX_PARTS) {
            window.dropped()
            pacer.requireKey()
            bitrate.onDrop(now)?.let { encoder?.setBitrate(it) }
            requestKey(now, force = false)
            return
        }
        when (pacer.admit(accessUnit.size, key, bitrate.bitrate)) {
            FrameVerdict.SEND -> Unit
            FrameVerdict.SKIP -> {
                window.dropped()
                requestKey(now, force = false)
                return
            }
            FrameVerdict.DROP -> {
                window.dropped()
                bitrate.onDrop(now)?.let { encoder?.setBitrate(it) }
                requestKey(now, force = false)
                return
            }
        }
        val ok = synchronized(sendLock) {
            if (phase != ScreenStreamPhase.STREAMING) return
            val frame = ++frameNumber
            val count = ScreenFrameCodec.partCount(accessUnit.size)
            pacer.sending(frame, accessUnit.size, captureMillis, now)
            val written = (0 until count).all { index ->
                val offset = index * ScreenLimits.PART_MAX_BYTES
                val length = minOf(ScreenLimits.PART_MAX_BYTES, accessUnit.size - offset)
                link.sendFrame { sequence ->
                    ScreenFrameCodec.encode(
                        ScreenFrameHeader(streamId, sequence, frame, index, key, parameterSets, index == count - 1, captureMicros),
                        accessUnit, offset, length,
                    )
                }
            }
            if (written) pacer.written(frame, clock()) else pacer.unsent(frame)
            written
        }
        if (ok) window.sent(accessUnit.size) else end(ScreenStopReason.FAILED, tell = false)
    }

    /** Once a second: liveness, timeouts, the bit rate's growth, and screen.stats. */
    fun tick() {
        val now = clock()
        if (phase == ScreenStreamPhase.ENDED) return
        if (!link.alive()) {
            log("PLUGIN screen session gone stream=${streamId.take(8)}")
            end(ScreenStopReason.FAILED, tell = false)
            return
        }
        when (phase) {
            ScreenStreamPhase.ENDED -> return
            ScreenStreamPhase.STARTING -> if (now - startedAt >= ScreenLimits.STARTED_TIMEOUT_MILLIS) {
                log("PLUGIN screen no screen.started stream=${streamId.take(8)}")
                end(ScreenStopReason.TIMEOUT, tell = true)
            }
            ScreenStreamPhase.STREAMING -> {
                val oldest = pacer.oldestWrittenAtMillis()
                if (oldest != null && now - lastAckAt >= ScreenLimits.ACK_TIMEOUT_MILLIS && now - oldest >= ScreenLimits.ACK_TIMEOUT_MILLIS) {
                    log("PLUGIN screen no ack for 6 s stream=${streamId.take(8)}")
                    end(ScreenStopReason.TIMEOUT, tell = true)
                    return
                }
                bitrate.tick(now)?.let { encoder?.setBitrate(it) }
                val stats = window.take(now, bitrate.bitrate)
                synchronized(sendLock) {
                    if (phase == ScreenStreamPhase.STREAMING) link.sendJson(ScreenKinds.STATS) { screenStatsPayload(streamId, it, stats) }
                }
                log(
                    "PLUGIN screen stats latency=${stats.latencyMs}ms rtt=${stats.rttMs}ms bitrate=${stats.bitrate} sent=${stats.sentBitrate} " +
                        "fps=${stats.fps} dropped=${stats.dropped} inflight=${pacer.framesInFlight}/${pacer.bytesInFlight}B",
                )
            }
        }
    }

    /** The encoder restarted (a new size): the next frame must be a key frame with its SPS and PPS. */
    fun encoderRestarted() = pacer.requireKey()

    /** This phone ends the stream: screen.stop to the Mac unless [tell] is false, then onEnded. */
    fun stop(reason: ScreenStopReason, tell: Boolean = true) = end(reason, tell)

    private fun end(reason: ScreenStopReason, tell: Boolean) {
        synchronized(this) {
            if (phase == ScreenStreamPhase.ENDED) return
            phase = ScreenStreamPhase.ENDED
        }
        if (tell) {
            stopWriter(Runnable { synchronized(sendLock) { link.sendJson(ScreenKinds.STOP) { screenStopPayload(streamId, it, reason) } } })
        }
        pacer.clear()
        log("PLUGIN screen end stream=${streamId.take(8)} reason=${reason.wire} told=$tell")
        onEnded(reason)
    }

    private fun requestKey(now: Long, force: Boolean) {
        synchronized(this) {
            if (!force && now - lastKeyRequestAt < ScreenRates.KEYFRAME_SPACING_MILLIS) return
            lastKeyRequestAt = now
        }
        encoder?.requestKeyFrame()
    }
}
