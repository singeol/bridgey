package dev.bridgey.android

import kotlin.math.abs
import kotlin.math.hypot

/** What the touchpad turns touches into. Distances are in pixels of the touch surface. */
sealed interface TouchpadGesture {
    data class Move(val dx: Float, val dy: Float) : TouchpadGesture
    data class Scroll(val dx: Float, val dy: Float) : TouchpadGesture
    data class Click(val button: PointerButton) : TouchpadGesture
    /** The finger was held still long enough: the left button goes down (a drag can follow). */
    data object DragStart : TouchpadGesture
    data class DragMove(val dx: Float, val dy: Float) : TouchpadGesture
    data object DragEnd : TouchpadGesture
}

/**
 * The touchpad's gestures, as a state machine fed with raw touches:
 * one finger moves the pointer; a tap clicks; a two-finger tap right-clicks; two fingers scroll;
 * touch and hold, then drag, drags (holding still and lifting is a long click).
 * Pure, so every transition is unit tested; the screen feeds it and calls [onTimer] at [holdDeadline].
 */
class TouchpadGestureRecognizer(
    private val slopPx: Float,
    private val holdMillis: Long = 400,
    private val twoFingerTapMillis: Long = 300,
) {
    private enum class Phase { IDLE, ONE_DOWN, MOVING, DRAGGING, TWO_DOWN, SCROLLING, WAIT_ALL_UP }

    private var phase = Phase.IDLE
    private val pointers = LinkedHashMap<Long, Pair<Float, Float>>()
    private var downAt = 0L
    private var primaryId = -1L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** When to call [onTimer]: while one finger rests, before it moves. */
    val holdDeadline: Long? get() = if (phase == Phase.ONE_DOWN) downAt + holdMillis else null

    fun onDown(id: Long, x: Float, y: Float, timeMillis: Long): List<TouchpadGesture> {
        pointers[id] = x to y
        return when (phase) {
            Phase.IDLE -> {
                phase = Phase.ONE_DOWN
                downAt = timeMillis
                primaryId = id
                startX = x; startY = y; lastX = x; lastY = y
                emptyList()
            }
            Phase.ONE_DOWN, Phase.MOVING -> {
                // A second finger: a two-finger tap or scroll, measured from both fingers' centre.
                val moved = phase == Phase.MOVING
                phase = if (moved) Phase.SCROLLING else Phase.TWO_DOWN
                val (cx, cy) = centroid()
                startX = cx; startY = cy; lastX = cx; lastY = cy
                emptyList()
            }
            Phase.SCROLLING -> {
                val (cx, cy) = centroid()
                lastX = cx; lastY = cy
                emptyList()
            }
            // A third finger, or more fingers while dragging: ignored.
            Phase.TWO_DOWN, Phase.DRAGGING, Phase.WAIT_ALL_UP -> emptyList()
        }
    }

    /** The current positions of the pointers that moved (others keep their last position). */
    fun onMove(positions: Map<Long, Pair<Float, Float>>): List<TouchpadGesture> {
        positions.forEach { (id, point) -> if (id in pointers) pointers[id] = point }
        return when (phase) {
            Phase.ONE_DOWN -> {
                val (x, y) = firstPointer() ?: return emptyList()
                if (hypot(x - startX, y - startY) <= slopPx) return emptyList()
                phase = Phase.MOVING
                step(x, y) { dx, dy -> TouchpadGesture.Move(dx, dy) }
            }
            Phase.MOVING -> {
                val (x, y) = firstPointer() ?: return emptyList()
                step(x, y) { dx, dy -> TouchpadGesture.Move(dx, dy) }
            }
            Phase.DRAGGING -> {
                val (x, y) = firstPointer() ?: return emptyList()
                step(x, y) { dx, dy -> TouchpadGesture.DragMove(dx, dy) }
            }
            Phase.TWO_DOWN -> {
                val (cx, cy) = centroid()
                if (hypot(cx - startX, cy - startY) <= slopPx) return emptyList()
                phase = Phase.SCROLLING
                step(cx, cy) { dx, dy -> TouchpadGesture.Scroll(dx, dy) }
            }
            Phase.SCROLLING -> {
                val (cx, cy) = centroid()
                step(cx, cy) { dx, dy -> TouchpadGesture.Scroll(dx, dy) }
            }
            Phase.IDLE, Phase.WAIT_ALL_UP -> emptyList()
        }
    }

    fun onUp(id: Long, timeMillis: Long): List<TouchpadGesture> {
        if (pointers.remove(id) == null) return emptyList()
        val remaining = pointers.size
        // Another finger lifting during a drag leaves the drag alone.
        if (phase == Phase.DRAGGING && id != primaryId) return emptyList()
        val result = when (phase) {
            Phase.ONE_DOWN -> listOf(TouchpadGesture.Click(PointerButton.LEFT))
            Phase.DRAGGING -> listOf(TouchpadGesture.DragEnd)
            Phase.TWO_DOWN -> if (timeMillis - downAt <= twoFingerTapMillis) listOf(TouchpadGesture.Click(PointerButton.RIGHT)) else emptyList()
            Phase.IDLE, Phase.MOVING, Phase.SCROLLING, Phase.WAIT_ALL_UP -> emptyList()
        }
        phase = if (remaining == 0) Phase.IDLE else Phase.WAIT_ALL_UP
        return result
    }

    /** The system took the touches (a gesture navigation swipe): a drag ends, nothing clicks. */
    fun onCancel(): List<TouchpadGesture> {
        val result = if (phase == Phase.DRAGGING) listOf(TouchpadGesture.DragEnd) else emptyList()
        pointers.clear()
        phase = Phase.IDLE
        return result
    }

    fun onTimer(timeMillis: Long): List<TouchpadGesture> {
        val deadline = holdDeadline ?: return emptyList()
        if (timeMillis < deadline) return emptyList()
        phase = Phase.DRAGGING
        return listOf(TouchpadGesture.DragStart)
    }

    /** The finger that started the gesture. */
    private fun firstPointer(): Pair<Float, Float>? = pointers[primaryId] ?: pointers.values.firstOrNull()

    /** The centre of the first two fingers. */
    private fun centroid(): Pair<Float, Float> {
        val points = pointers.values.take(2)
        return points.map { it.first }.average().toFloat() to points.map { it.second }.average().toFloat()
    }

    private inline fun step(x: Float, y: Float, make: (Float, Float) -> TouchpadGesture): List<TouchpadGesture> {
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        return if (dx == 0f && dy == 0f) emptyList() else listOf(make(dx, dy))
    }
}

/**
 * Pointer speed: Mac points per phone dp, with gentle acceleration for quick swipes.
 * [level] is the Settings choice, 1 (slow) to 5 (fast); 3 is the default.
 */
fun pointerGain(level: Int, speedDpPerMillis: Float): Float {
    val base = POINTER_BASE_GAIN[(level.coerceIn(1, 5)) - 1]
    val boost = 1f + minOf(abs(speedDpPerMillis), 2.5f) * 0.45f
    return base * boost
}

private val POINTER_BASE_GAIN = floatArrayOf(0.9f, 1.3f, 1.8f, 2.4f, 3.2f)

/** Two-finger scrolling: Mac pixels per phone dp. The content follows the fingers. */
const val SCROLL_GAIN = 2f

/**
 * Sums movement between sends (the screen flushes 60 times a second), keeps the fractions for
 * the next flush, and splits large moves to fit the message bounds.
 */
class MotionBatcher {
    private var pointerX = 0f
    private var pointerY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    fun addPointer(dx: Float, dy: Float) { pointerX += dx; pointerY += dy }
    fun addDrag(dx: Float, dy: Float) { dragX += dx; dragY += dy }
    fun addScroll(dx: Float, dy: Float) { scrollX += dx; scrollY += dy }

    fun flush(): List<RemoteInput> = buildList {
        drain(pointerX, pointerY, RemoteInputLimits.MAX_POINTER_DELTA) { dx, dy -> add(RemoteInput.Pointer(dx, dy)) }
            .also { (rx, ry) -> pointerX = rx; pointerY = ry }
        drain(dragX, dragY, RemoteInputLimits.MAX_POINTER_DELTA) { dx, dy -> add(RemoteInput.DragMove(dx, dy)) }
            .also { (rx, ry) -> dragX = rx; dragY = ry }
        drain(scrollX, scrollY, RemoteInputLimits.MAX_PIXEL_SCROLL) { dx, dy -> add(RemoteInput.Scroll(dx, dy)) }
            .also { (rx, ry) -> scrollX = rx; scrollY = ry }
    }

    /** Emits the whole-number part in chunks within ±[limit]; returns the fractions left over. */
    private inline fun drain(x: Float, y: Float, limit: Int, emit: (Int, Int) -> Unit): Pair<Float, Float> {
        var whole = x.toInt() to y.toInt()
        val rest = (x - whole.first) to (y - whole.second)
        while (whole.first != 0 || whole.second != 0) {
            val cx = whole.first.coerceIn(-limit, limit)
            val cy = whole.second.coerceIn(-limit, limit)
            emit(cx, cy)
            whole = (whole.first - cx) to (whole.second - cy)
        }
        return rest
    }
}
