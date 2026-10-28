package dev.bridgey.android.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * MediaProjection into a hardware H.264 encoder, feeding [stream]. Kept thin: sizes, pacing and the wire
 * live in plain classes that are tested on the JVM. The VirtualDisplay is created once; a new size (a
 * rotation, or the shared app's window changing on Android 14+) restarts only the encoder and resizes the
 * display onto its new surface. [handler] runs on the service's capture thread.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val stream: ScreenStream,
    private val handler: Handler,
    private val onFailure: (ScreenStopReason) -> Unit,
) : ScreenEncoderControl {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var drain: Thread? = null
    @Volatile private var released = false
    private var size: Pair<Int, Int>? = null
    private var sourceSize: Pair<Int, Int>? = null
    private var codecName: String? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) sourceChanged(defaultDisplaySize())
        }
    }

    /** Starts capture and sends screen.start. Blocking (the socket); call it off the main thread. */
    fun start(): Boolean {
        val source = defaultDisplaySize()
        sourceSize = source
        val target = chooseSize(source) ?: return fail("no H.264 encoder takes this screen size")
        val created = createCodec(target) ?: return fail("the encoder did not start")
        synchronized(lock) {
            codec = created.first
            surface = created.second
            size = target
            val metrics = context.resources.displayMetrics
            display = projection.createVirtualDisplay(
                "Bridgey screen", target.first, target.second, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, created.second, null, handler,
            )
            startDrain(created.first)
        }
        context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        stream.encoder = this
        Log.i("Bridgey", "PLUGIN screen capture ${target.first}x${target.second} from ${source.first}x${source.second} codec=$codecName")
        return stream.start(target.first, target.second)
    }

    /** Android 14+: the captured content (a single app, or the screen) has a new size. */
    fun capturedContentResized(width: Int, height: Int) {
        if (width > 0 && height > 0) sourceChanged(width to height)
    }

    private fun sourceChanged(source: Pair<Int, Int>) {
        if (released || source == sourceSize) return
        sourceSize = source
        val target = chooseSize(source) ?: return
        if (target == size) return
        restart(target)
    }

    private fun restart(target: Pair<Int, Int>) {
        val old: MediaCodec?
        val oldSurface: Surface?
        val oldDrain: Thread?
        synchronized(lock) {
            if (released) return
            old = codec
            oldSurface = surface
            oldDrain = drain
            codec = null
            drain = null
        }
        // Outside the lock: the drain thread may be inside the stream asking for a key frame.
        runCatching { old?.signalEndOfInputStream() }
        oldDrain?.interrupt()
        runCatching { oldDrain?.join(500) }
        runCatching { old?.stop() }
        runCatching { old?.release() }
        val created = createCodec(target) ?: run {
            fail("the encoder did not restart")
            return
        }
        synchronized(lock) {
            if (released) {
                runCatching { created.first.release() }
                created.second.release()
                return
            }
            codec = created.first
            surface = created.second
            size = target
            display?.resize(target.first, target.second, context.resources.displayMetrics.densityDpi)
            display?.surface = created.second
            oldSurface?.release()
            stream.encoderRestarted()
            startDrain(created.first)
        }
        Log.i("Bridgey", "PLUGIN screen resized to ${target.first}x${target.second}")
    }

    override fun requestKeyFrame() {
        synchronized(lock) {
            runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    override fun setBitrate(bitsPerSecond: Long) {
        synchronized(lock) {
            runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond.toInt()) }) }
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            stream.encoder = null
            context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
            drain?.interrupt()
            drain = null
            runCatching { display?.release() }
            display = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            surface?.release()
            surface = null
        }
    }

    private fun fail(why: String): Boolean {
        Log.i("Bridgey", "PLUGIN screen capture failed: $why")
        onFailure(ScreenStopReason.FAILED)
        return false
    }

    @Suppress("DEPRECATION")
    private fun defaultDisplaySize(): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display?.getRealMetrics(metrics)
        return if (metrics.widthPixels > 0) metrics.widthPixels to metrics.heightPixels
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    /** The first cap (1600, 1280, 960) whose size the chosen encoder supports. */
    private fun chooseSize(source: Pair<Int, Int>): Pair<Int, Int>? {
        val info = encoderInfo() ?: return null
        val video = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        return SCREEN_SIZE_CAPS.map { screenCaptureSize(source.first, source.second, it) }
            .firstOrNull { (w, h) -> runCatching { video.isSizeSupported(w, h) }.getOrDefault(false) }
    }

    /** A hardware H.264 encoder when there is one, else any. */
    private fun encoderInfo(): MediaCodecInfo? {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        val hardware = encoders.firstOrNull { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }
        return (hardware ?: encoders.firstOrNull())?.also { codecName = it.name }
    }

    private fun createCodec(target: Pair<Int, Int>): Pair<MediaCodec, Surface>? = runCatching {
        val info = encoderInfo() ?: error("no encoder")
        val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val vbr = capabilities.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        val created = MediaCodec.createByCodecName(info.name)
        // Some encoders refuse an optional key and fail the whole configure. Each attempt drops only the keys that
        // tend to be refused, so the frame rate cap and no B-frames survive the fallback.
        val attempts = listOf(
            encoderFormat(target, vbr, optional = true),
            encoderFormat(target, vbr, optional = false),
            plainFormat(target),
        )
        var configured = false
        for ((index, format) in attempts.withIndex()) {
            try {
                created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
                if (index > 0) Log.i("Bridgey", "PLUGIN screen encoder configured with fallback $index")
                break
            } catch (error: Exception) {
                created.reset()
            }
        }
        if (!configured) {
            created.release()
            error("the encoder refused every format")
        }
        val input = created.createInputSurface()
        created.start()
        created to input
    }.onFailure { Log.w("Bridgey", "PLUGIN screen encoder failed", it) }.getOrNull()

    /**
     * The format the stream wants. [optional] adds the keys some encoders refuse: real-time priority and low
     * latency. The frame rate cap (applied by the input surface, not the codec) and no B-frames are always set.
     * KEY_PREPEND_HEADER_TO_SYNC_FRAMES is never set: the emulator's encoder fails configure on it, and the drain
     * loop puts the cached SPS and PPS before every key frame itself.
     */
    private fun encoderFormat(target: Pair<Int, Int>, vbr: Boolean, optional: Boolean): MediaFormat =
        plainFormat(target).apply {
            if (vbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setFloat("max-fps-to-encoder", ScreenRates.FPS.toFloat())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (optional) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

    private fun plainFormat(target: Pair<Int, Int>): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, target.first, target.second).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, stream.currentBitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, ScreenRates.FPS)
            // Key frames come on demand (a new Mac window, a drop); the regular interval is only a safety net.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

    /** Drains [codec] on its own thread: codec config is kept and put before every key frame. */
    private fun startDrain(codec: MediaCodec) {
        val thread = Thread({ drainLoop(codec) }, "bridgey-screen-encoder")
        drain = thread
        thread.start()
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var parameterSets: ByteArray? = null
        while (!released && !Thread.currentThread().isInterrupted) {
            val index = try {
                codec.dequeueOutputBuffer(info, 100_000L)
            } catch (_: IllegalStateException) {
                break
            }
            if (index < 0) continue
            val bytes = try {
                val buffer = codec.getOutputBuffer(index)
                ByteArray(info.size).also { out ->
                    buffer?.position(info.offset)
                    buffer?.limit(info.offset + info.size)
                    buffer?.get(out)
                }
            } catch (_: IllegalStateException) {
                break
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            if (bytes.isEmpty()) continue
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                parameterSets = bytes
                continue
            }
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            var accessUnit = bytes
            var withSets = false
            if (key) {
                if (AnnexB.startsWithSps(bytes)) {
                    withSets = true
                } else {
                    parameterSets?.let {
                        accessUnit = it + bytes
                        withSets = true
                    }
                }
            }
            val nowNanos = System.nanoTime()
            val ptsNanos = info.presentationTimeUs * 1_000
            // Surface timestamps are on the monotonic clock; anything implausible counts as captured now.
            val captureNanos = if (info.presentationTimeUs > 0 && nowNanos - ptsNanos in 0..5_000_000_000L) ptsNanos else nowNanos
            stream.onEncodedFrame(accessUnit, key, withSets, captureNanos / 1_000_000, captureNanos / 1_000)
        }
    }
}
