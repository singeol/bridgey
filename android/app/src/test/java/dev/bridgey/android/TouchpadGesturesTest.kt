package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadGesturesTest {
    private val g = TouchpadGestureRecognizer(slopPx = 10f, holdMillis = 400, twoFingerTapMillis = 300)

    private fun move(vararg points: Pair<Long, Pair<Float, Float>>) = g.onMove(mapOf(*points))

    // Gesture recogniser state machine

    @Test fun aTapClicks() {
        assertTrue(g.onDown(1, 100f, 100f, 0).isEmpty())
        assertTrue("within the slop", move(1L to (104f to 103f)).isEmpty())
        assertEquals(listOf(TouchpadGesture.Click(PointerButton.LEFT)), g.onUp(1, 120))
        assertNull(g.holdDeadline)
    }

    @Test fun oneFingerMovesThePointerOnceItLeavesTheSlop() {
        g.onDown(1, 100f, 100f, 0)
        assertEquals(listOf(TouchpadGesture.Move(15f, 0f)), move(1L to (115f to 100f)))
        assertEquals(listOf(TouchpadGesture.Move(5f, -2f)), move(1L to (120f to 98f)))
        assertTrue("no movement, nothing to send", move(1L to (120f to 98f)).isEmpty())
        assertTrue("a move never clicks", g.onUp(1, 900).isEmpty())
        assertNull("no hold once moving", g.holdDeadline)
    }

    @Test fun twoFingerTapRightClicks() {
        g.onDown(1, 100f, 100f, 0)
        g.onDown(2, 200f, 100f, 30)
        assertTrue(move(1L to (102f to 101f), 2L to (201f to 99f)).isEmpty())
        assertEquals(listOf(TouchpadGesture.Click(PointerButton.RIGHT)), g.onUp(1, 150))
        assertTrue("the second finger lifting adds nothing", g.onUp(2, 160).isEmpty())
        // And the next touch starts fresh.
        g.onDown(3, 50f, 50f, 1_000)
        assertEquals(listOf(TouchpadGesture.Click(PointerButton.LEFT)), g.onUp(3, 1_050))
    }

    @Test fun aSlowTwoFingerTouchDoesNotClick() {
        g.onDown(1, 100f, 100f, 0)
        g.onDown(2, 200f, 100f, 20)
        assertTrue(g.onUp(1, 500).isEmpty())
        assertTrue(g.onUp(2, 510).isEmpty())
    }

    @Test fun twoFingersScrollByTheirCentre() {
        g.onDown(1, 100f, 100f, 0)
        g.onDown(2, 200f, 100f, 10)
        assertEquals(listOf(TouchpadGesture.Scroll(0f, 20f)), move(1L to (100f to 120f), 2L to (200f to 120f)))
        assertEquals(listOf(TouchpadGesture.Scroll(0f, 5f)), move(1L to (100f to 130f)))
        assertTrue("no right click after scrolling", g.onUp(1, 100).isEmpty())
        assertTrue("one finger left: no pointer jump", move(2L to (300f to 300f)).isEmpty())
        assertTrue(g.onUp(2, 120).isEmpty())
    }

    @Test fun aSecondFingerWhileMovingScrolls() {
        g.onDown(1, 0f, 0f, 0)
        move(1L to (50f to 0f))
        g.onDown(2, 150f, 0f, 100)
        assertEquals(listOf(TouchpadGesture.Scroll(0f, 10f)), move(1L to (50f to 10f), 2L to (150f to 10f)))
    }

    @Test fun holdThenDragDrags() {
        g.onDown(1, 100f, 100f, 0)
        assertEquals(400L, g.holdDeadline)
        assertTrue("too early", g.onTimer(399).isEmpty())
        assertEquals(listOf(TouchpadGesture.DragStart), g.onTimer(400))
        assertNull(g.holdDeadline)
        assertEquals("small moves count while dragging", listOf(TouchpadGesture.DragMove(3f, 0f)), move(1L to (103f to 100f)))
        assertEquals(listOf(TouchpadGesture.DragMove(0f, 40f)), move(1L to (103f to 140f)))
        assertEquals(listOf(TouchpadGesture.DragEnd), g.onUp(1, 2_000))
    }

    @Test fun holdingStillAndLiftingIsALongClick() {
        g.onDown(1, 100f, 100f, 0)
        assertEquals(listOf(TouchpadGesture.DragStart), g.onTimer(450))
        assertEquals(listOf(TouchpadGesture.DragEnd), g.onUp(1, 900))
    }

    @Test fun anotherFingerDuringADragLeavesItAlone() {
        g.onDown(1, 100f, 100f, 0)
        g.onTimer(400)
        assertTrue(g.onDown(2, 300f, 300f, 500).isEmpty())
        assertTrue(g.onUp(2, 600).isEmpty())
        assertEquals(listOf(TouchpadGesture.DragMove(10f, 0f)), move(1L to (110f to 100f)))
        assertEquals(listOf(TouchpadGesture.DragEnd), g.onUp(1, 700))
    }

    @Test fun cancelEndsADragAndNeverClicks() {
        g.onDown(1, 100f, 100f, 0)
        g.onTimer(400)
        assertEquals(listOf(TouchpadGesture.DragEnd), g.onCancel())
        g.onDown(1, 100f, 100f, 1_000)
        assertTrue(g.onCancel().isEmpty())
        assertTrue("released pointers are gone", g.onUp(1, 1_100).isEmpty())
    }

    @Test fun movingBeforeTheHoldCancelsIt() {
        g.onDown(1, 100f, 100f, 0)
        move(1L to (130f to 100f))
        assertTrue(g.onTimer(1_000).isEmpty())
        assertTrue(g.onUp(1, 1_100).isEmpty())
    }

    @Test fun aThirdFingerIsIgnored() {
        g.onDown(1, 0f, 0f, 0)
        g.onDown(2, 100f, 0f, 10)
        assertTrue(g.onDown(3, 200f, 0f, 20).isEmpty())
        assertEquals(listOf(TouchpadGesture.Click(PointerButton.RIGHT)), g.onUp(3, 100))
    }

    // Batching and gain

    @Test fun batcherSendsWholePointsAndKeepsFractions() {
        val batcher = MotionBatcher()
        batcher.addPointer(1.4f, -0.6f)
        assertEquals(listOf(RemoteInput.Pointer(1, 0)), batcher.flush())
        batcher.addPointer(0.7f, -0.6f)
        assertEquals("0.4 + 0.7 and -0.6 - 0.6", listOf(RemoteInput.Pointer(1, -1)), batcher.flush())
        assertTrue(batcher.flush().isEmpty())
    }

    @Test fun batcherSplitsLargeMovesToTheMessageBound() {
        val batcher = MotionBatcher()
        batcher.addPointer(1_200f, -30f)
        assertEquals(listOf(RemoteInput.Pointer(512, -30), RemoteInput.Pointer(512, 0), RemoteInput.Pointer(176, 0)), batcher.flush())
        batcher.addScroll(0f, -2_500f)
        assertEquals(listOf(RemoteInput.Scroll(0, -1_000), RemoteInput.Scroll(0, -1_000), RemoteInput.Scroll(0, -500)), batcher.flush())
        batcher.addDrag(3f, 4f)
        assertEquals(listOf(RemoteInput.DragMove(3, 4)), batcher.flush())
        assertTrue(batcher.flush().all { it.isValid() })
    }

    @Test fun pointerSpeedGrowsWithTheSettingAndWithQuickSwipes() {
        val slow = pointerGain(1, 0f)
        val normal = pointerGain(3, 0f)
        val fast = pointerGain(5, 0f)
        assertTrue(slow < normal && normal < fast)
        assertTrue(pointerGain(3, 2f) > normal)
        assertEquals("acceleration is capped", pointerGain(3, 2.5f), pointerGain(3, 50f), 0.0001f)
        assertEquals("out of range clamps", pointerGain(5, 0f), pointerGain(9, 0f), 0.0001f)
    }
}
