package dev.bridgey.android

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/*
 * Remote input, feature `remote_input` (docs/protocol.md, "Remote input"). This phone is the
 * controller: it moves a Mac's pointer, clicks, scrolls, drags, types bounded text, presses a short
 * list of named keys and drives presentations. Pure, so the bounds, the key allowlist, the
 * sequence and the rate limits are unit tested on the JVM.
 */

object RemoteInputLimits {
    const val MAX_PLAINTEXT_BYTES = 2_048
    const val MAX_POINTER_DELTA = 512
    const val MAX_PIXEL_SCROLL = 1_000
    const val MAX_LINE_SCROLL = 10
    const val MAX_TEXT_SCALARS = 256
    const val MAX_TEXT_BYTES = 1_024
    const val LASER_SCALE = 10_000
    const val MOTION_PER_SECOND = 120.0
    const val DISCRETE_PER_SECOND = 20.0
    const val CONTROL_PER_SECOND = 10.0
    const val HEARTBEAT_MILLIS = 2_000L
    const val START_TIMEOUT_MILLIS = 5_000L
    const val MAX_SEQUENCE = 9_007_199_254_740_991L
}

enum class RemoteMode(val wire: String) { TOUCHPAD("touchpad"), PRESENTATION("presentation") }

/** Why a session ended. Only USER, LEFT_SCREEN and SCREEN_OFF are sent by this phone. */
enum class RemoteStopReason(val wire: String) {
    USER("user"),
    LEFT_SCREEN("left_screen"),
    SCREEN_OFF("screen_off"),
    LOCAL_STOP("local_stop"),
    TIMEOUT("timeout"),
    DISABLED("disabled"),
    PERMISSION_REQUIRED("permission_required"),
    BUSY("busy"),
    INVALID("invalid"),
    UNSUPPORTED("unsupported"),

    /** Local only: the connection closed. */
    DISCONNECTED("disconnected"),

    /** Local only: the Mac did not answer the start within five seconds. */
    NO_ANSWER("no_answer"),
    ;

    companion object {
        fun fromWire(value: String?): RemoteStopReason? = entries.firstOrNull { it.wire == value }
    }
}

enum class PointerButton(val wire: String) { LEFT("left"), RIGHT("right"), MIDDLE("middle") }

enum class ScrollUnit(val wire: String) { PIXEL("pixel"), LINE("line") }

/**
 * The keys a phone may press: named keys only, with Shift as the one modifier on the wire.
 * [PREVIOUS_APP] is the single shortcut, and the Mac picks its keys (Command-Tab).
 */
enum class RemoteKey(val wire: String) {
    LEFT("left"), RIGHT("right"), UP("up"), DOWN("down"),
    RETURN("return"), ESCAPE("escape"), TAB("tab"), SPACE("space"), BACKSPACE("backspace"),
    PAGE_UP("page_up"), PAGE_DOWN("page_down"), HOME("home"), END("end"), F5("f5"),
    PREVIOUS_APP("previous_app"),
}

enum class PresentAction(val wire: String) {
    NEXT("next"), PREVIOUS("previous"), START("start"), END("end"), BLACK("black"),
    LASER_ON("laser_on"), LASER_MOVE("laser_move"), LASER_OFF("laser_off"),
    ;

    val carriesPosition: Boolean get() = this == LASER_ON || this == LASER_MOVE
}

enum class RateClass { MOTION, DISCRETE, CONTROL }

/** One controller-to-host message, before its session ID and sequence are added. */
sealed interface RemoteInput {
    val kind: String
    val rateClass: RateClass
    fun fields(json: JSONObject)

    /** The same bounds the host checks; invalid input is never sent. */
    fun isValid(): Boolean = true

    data class Start(val mode: RemoteMode) : RemoteInput {
        override val kind get() = "input.session.start"
        override val rateClass get() = RateClass.CONTROL
        override fun fields(json: JSONObject) { json.put("mode", mode.wire) }
    }

    data object Heartbeat : RemoteInput {
        override val kind get() = "input.session.heartbeat"
        override val rateClass get() = RateClass.CONTROL
        override fun fields(json: JSONObject) = Unit
    }

    data class Stop(val reason: RemoteStopReason) : RemoteInput {
        override val kind get() = "input.session.stop"
        override val rateClass get() = RateClass.CONTROL
        override fun fields(json: JSONObject) { json.put("reason", reason.wire) }
        override fun isValid() = reason in setOf(RemoteStopReason.USER, RemoteStopReason.LEFT_SCREEN, RemoteStopReason.SCREEN_OFF)
    }

    data class Pointer(val dx: Int, val dy: Int) : RemoteInput {
        override val kind get() = "input.pointer"
        override val rateClass get() = RateClass.MOTION
        override fun fields(json: JSONObject) { json.put("dx", dx).put("dy", dy) }
        override fun isValid() = validDelta(dx, dy, RemoteInputLimits.MAX_POINTER_DELTA)
    }

    data class Button(val button: PointerButton, val down: Boolean) : RemoteInput {
        override val kind get() = "input.button"
        override val rateClass get() = RateClass.DISCRETE
        override fun fields(json: JSONObject) { json.put("button", button.wire).put("action", if (down) "down" else "up") }
    }

    data class Scroll(val dx: Int, val dy: Int, val unit: ScrollUnit = ScrollUnit.PIXEL) : RemoteInput {
        override val kind get() = "input.scroll"
        override val rateClass get() = RateClass.MOTION
        override fun fields(json: JSONObject) { json.put("dx", dx).put("dy", dy).put("unit", unit.wire) }
        override fun isValid() = validDelta(
            dx, dy, if (unit == ScrollUnit.PIXEL) RemoteInputLimits.MAX_PIXEL_SCROLL else RemoteInputLimits.MAX_LINE_SCROLL,
        )
    }

    data object DragStart : RemoteInput {
        override val kind get() = "input.drag"
        override val rateClass get() = RateClass.DISCRETE
        override fun fields(json: JSONObject) { json.put("phase", "start") }
    }

    data class DragMove(val dx: Int, val dy: Int) : RemoteInput {
        override val kind get() = "input.drag"
        override val rateClass get() = RateClass.MOTION
        override fun fields(json: JSONObject) { json.put("phase", "move").put("dx", dx).put("dy", dy) }
        override fun isValid() = validDelta(dx, dy, RemoteInputLimits.MAX_POINTER_DELTA)
    }

    data object DragEnd : RemoteInput {
        override val kind get() = "input.drag"
        override val rateClass get() = RateClass.DISCRETE
        override fun fields(json: JSONObject) { json.put("phase", "end") }
    }

    /** Typed as text on the Mac. Never logged or stored. */
    data class Text(val text: String) : RemoteInput {
        override val kind get() = "input.text"
        override val rateClass get() = RateClass.DISCRETE
        override fun fields(json: JSONObject) { json.put("text", text) }
        override fun isValid() = isValidRemoteText(text)
        override fun toString() = "Text(${text.length} chars)"
    }

    data class Key(val key: RemoteKey, val shift: Boolean = false) : RemoteInput {
        override val kind get() = "input.key"
        override val rateClass get() = RateClass.DISCRETE
        override fun fields(json: JSONObject) {
            json.put("key", key.wire)
            if (shift) json.put("modifiers", JSONArray().put("shift"))
        }
        override fun isValid() = !(shift && key == RemoteKey.PREVIOUS_APP)
    }

    data class Present(val action: PresentAction, val x: Int? = null, val y: Int? = null) : RemoteInput {
        override val kind get() = "input.present"
        override val rateClass get() = if (action == PresentAction.LASER_MOVE) RateClass.MOTION else RateClass.DISCRETE
        override fun fields(json: JSONObject) {
            json.put("action", action.wire)
            if (action.carriesPosition) json.put("x", x).put("y", y)
        }
        override fun isValid(): Boolean {
            if (!action.carriesPosition) return x == null && y == null
            val range = 0..RemoteInputLimits.LASER_SCALE
            return x != null && y != null && x in range && y in range
        }
    }
}

private fun validDelta(dx: Int, dy: Int, limit: Int): Boolean =
    dx in -limit..limit && dy in -limit..limit && (dx != 0 || dy != 0)

/** 1 to 256 Unicode scalars, at most 1,024 UTF-8 bytes, no control characters (Return and Tab are keys). */
fun isValidRemoteText(text: String): Boolean {
    val scalars = text.codePointCount(0, text.length)
    if (scalars !in 1..RemoteInputLimits.MAX_TEXT_SCALARS) return false
    if (text.toByteArray(Charsets.UTF_8).size > RemoteInputLimits.MAX_TEXT_BYTES) return false
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.getType(codePoint) == Character.CONTROL.toInt()) return false
        // An unpaired surrogate cannot be encoded as UTF-8 faithfully.
        if (Character.getType(codePoint) == Character.SURROGATE.toInt()) return false
        index += Character.charCount(codePoint)
    }
    return true
}

/** Text from the keyboard field, cut to what one message may carry and stripped of controls. */
fun boundedRemoteText(value: String): String {
    val builder = StringBuilder()
    var scalars = 0
    var index = 0
    while (index < value.length && scalars < RemoteInputLimits.MAX_TEXT_SCALARS) {
        val codePoint = value.codePointAt(index)
        index += Character.charCount(codePoint)
        val type = Character.getType(codePoint)
        if (type == Character.CONTROL.toInt() || type == Character.SURROGATE.toInt()) continue
        builder.appendCodePoint(codePoint)
        scalars += 1
    }
    return builder.toString()
}

object RemoteInputCodec {
    /** The encrypted plaintext of [input] in session [inputSessionId] with [sequence]. */
    fun encode(input: RemoteInput, inputSessionId: String, sequence: Long): ByteArray {
        val json = JSONObject().put("version", 1).put("inputSessionId", inputSessionId).put("sequence", sequence)
        input.fields(json)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** A host-to-controller message, or null for anything malformed. */
    fun decodeHostMessage(kind: String, data: ByteArray): HostMessage? {
        if (data.size > RemoteInputLimits.MAX_PLAINTEXT_BYTES) return null
        val json = runCatching { JSONObject(data.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        if (json.integer("version") != 1L) return null
        val sessionId = json.optString("inputSessionId").takeIf { id -> runCatching { UUID.fromString(id) }.isSuccess } ?: return null
        val sequence = json.integer("sequence")?.takeIf { it in 1..RemoteInputLimits.MAX_SEQUENCE } ?: return null
        return when (kind) {
            "input.session.started" -> {
                val heartbeat = json.integer("heartbeatSeconds")?.takeIf { it in 1..30 } ?: (RemoteInputLimits.HEARTBEAT_MILLIS / 1000)
                HostMessage.Started(sessionId.lowercase(), sequence, heartbeat * 1000)
            }
            "input.session.stop" -> HostMessage.Stopped(
                sessionId.lowercase(), sequence, RemoteStopReason.fromWire(json.optString("reason")) ?: RemoteStopReason.INVALID,
            )
            "input.session.start" -> HostMessage.StartFromPeer(sessionId.lowercase(), sequence)
            else -> null
        }
    }

    private fun JSONObject.integer(name: String): Long? = when (val value = opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}

sealed interface HostMessage {
    val inputSessionId: String
    val sequence: Long

    data class Started(override val inputSessionId: String, override val sequence: Long, val heartbeatMillis: Long) : HostMessage
    data class Stopped(override val inputSessionId: String, override val sequence: Long, val reason: RemoteStopReason) : HostMessage

    /** A peer tried to control this phone; phones cannot be controlled and answer `unsupported`. */
    data class StartFromPeer(override val inputSessionId: String, override val sequence: Long) : HostMessage
}

/** A per-session high-water mark: each accepted sequence must be higher than the last. */
class InputSequence {
    var last = 0L
        private set

    @Synchronized fun accept(sequence: Long): Boolean {
        if (sequence !in 1..RemoteInputLimits.MAX_SEQUENCE || sequence <= last) return false
        last = sequence
        return true
    }
}

/** A token bucket: [ratePerSecond] tokens a second, holding at most one second's worth. */
class TokenBucket(private val ratePerSecond: Double, private val capacity: Double = ratePerSecond) {
    private var tokens = capacity
    private var updatedAt: Long? = null

    @Synchronized fun allow(nowMillis: Long): Boolean {
        val last = updatedAt
        if (last == null) {
            updatedAt = nowMillis
        } else if (nowMillis > last) {
            tokens = minOf(capacity, tokens + (nowMillis - last) / 1000.0 * ratePerSecond)
            updatedAt = nowMillis
        }
        if (tokens < 1) return false
        tokens -= 1
        return true
    }
}

/** The three buckets of one session, as the host applies them. */
class RemoteRateLimiter {
    private val motion = TokenBucket(RemoteInputLimits.MOTION_PER_SECOND)
    private val discrete = TokenBucket(RemoteInputLimits.DISCRETE_PER_SECOND)
    private val control = TokenBucket(RemoteInputLimits.CONTROL_PER_SECOND)

    fun allow(rateClass: RateClass, nowMillis: Long): Boolean = when (rateClass) {
        RateClass.MOTION -> motion.allow(nowMillis)
        RateClass.DISCRETE -> discrete.allow(nowMillis)
        RateClass.CONTROL -> control.allow(nowMillis)
    }
}
