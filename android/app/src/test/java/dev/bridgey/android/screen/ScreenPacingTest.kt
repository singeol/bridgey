package dev.bridgey.android.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPacingTest {
    @Test fun firstFrameMustBeAKeyFrame() {
        val pacer = ScreenPacer()
        assertEquals(FrameVerdict.SKIP, pacer.admit(1000, key = false, bitrate = 4_000_000))
        assertEquals(FrameVerdict.SEND, pacer.admit(1000, key = true, bitrate = 4_000_000))
        assertEquals(FrameVerdict.SEND, pacer.admit(1000, key = false, bitrate = 4_000_000))
    }

    @Test fun atMostSixFramesInFlight() {
        val pacer = ScreenPacer()
        repeat(6) { i ->
            assertEquals(FrameVerdict.SEND, pacer.admit(100, key = i == 0, bitrate = 4_000_000))
            pacer.sending(i + 1L, 100, 0, 0)
        }
        assertEquals(FrameVerdict.DROP, pacer.admit(100, key = false, bitrate = 4_000_000))
        // After a drop only a key frame may go, and only once there is room.
        pacer.acknowledge(6, 0, 10)
        assertEquals(FrameVerdict.SKIP, pacer.admit(100, key = false, bitrate = 4_000_000))
        assertEquals(FrameVerdict.SEND, pacer.admit(100, key = true, bitrate = 4_000_000))
    }

    @Test fun bytesInFlightStayWithinTheWindow() {
        val pacer = ScreenPacer()
        // 4 Mb/s for 250 ms is 125,000 bytes; 64 KiB is the floor.
        assertEquals(125_000L, pacer.windowBytes(4_000_000))
        assertEquals(65_536L, pacer.windowBytes(300_000))
        assertEquals(FrameVerdict.SEND, pacer.admit(100_000, key = true, bitrate = 4_000_000))
        pacer.sending(1, 100_000, 0, 0)
        assertEquals(FrameVerdict.DROP, pacer.admit(30_000, key = false, bitrate = 4_000_000))
    }

    @Test fun anEmptyPipeTakesAKeyFrameBiggerThanTheWindow() {
        val pacer = ScreenPacer()
        assertEquals(FrameVerdict.SEND, pacer.admit(500_000, key = true, bitrate = 300_000))
    }

    @Test fun anAckGivesTheRoundTripAndLatency() {
        val pacer = ScreenPacer()
        pacer.admit(1000, true, 4_000_000)
        pacer.sending(1, 1000, captureMillis = 100, nowMillis = 110)
        pacer.written(1, 112)
        pacer.admit(1000, false, 4_000_000)
        pacer.sending(2, 1000, captureMillis = 133, nowMillis = 140)
        pacer.written(2, 141)
        // Frame 1 acked at 130 after 4 ms of decoding: round trip 130 - 112 - 4 = 14; latency 130 - 100 - 7 = 23.
        assertEquals(ScreenPacer.AckSample(14, 23), pacer.acknowledge(1, 4, 130))
        assertEquals(1, pacer.framesInFlight)
        assertNull("an old ack says nothing new", pacer.acknowledge(1, 0, 150))
        assertEquals(ScreenPacer.AckSample(9, 13), pacer.acknowledge(2, 0, 150))
        assertEquals(0L, pacer.bytesInFlight)
        assertNull(pacer.oldestWrittenAtMillis())
    }

    @Test fun bitrateComesDownOnDropsAtMostEveryHalfSecond() {
        val rate = ScreenBitrate(0)
        assertEquals(3_000_000L, rate.onDrop(1000))
        assertNull(rate.onDrop(1200))
        assertEquals(2_250_000L, rate.onDrop(1500))
        repeat(20) { rate.onDrop(2000L + it * 500) }
        assertEquals(ScreenRates.MIN_BITRATE, rate.bitrate)
    }

    @Test fun bitrateComesDownWhenTheRoundTripGrows() {
        val rate = ScreenBitrate(0)
        assertNull(rate.onRtt(10, 100))
        assertNull(rate.onRtt(85, 200))
        assertEquals(3_000_000L, rate.onRtt(95, 600))
        assertEquals(10L, rate.minRttMillis)
    }

    @Test fun bitrateGrowsAfterTwoCalmSeconds() {
        val rate = ScreenBitrate(0)
        assertNull(rate.tick(1999))
        assertEquals(4_600_000L, rate.tick(2000))
        assertNull(rate.tick(3000))
        repeat(40) { rate.tick(4000L + it * 2000) }
        assertEquals(ScreenRates.MAX_BITRATE, rate.bitrate)
    }

    @Test fun statsCoverOneSecond() {
        val window = ScreenStatsWindow(0)
        repeat(30) { window.sent(10_000) }
        window.dropped()
        window.sample(ScreenPacer.AckSample(10, 40))
        window.sample(ScreenPacer.AckSample(12, 60))
        val stats = window.take(1000, 4_000_000)
        assertEquals(ScreenStats(latencyMs = 50, rttMs = 12, bitrate = 4_000_000, sentBitrate = 2_400_000, fps = 30, dropped = 1), stats)
        assertEquals(0, window.take(2000, 4_000_000).fps)
    }

    @Test fun sequenceOnlyGoesUp() {
        val guard = ScreenSequence()
        assertTrue(guard.accept(1))
        assertFalse(guard.accept(1))
        assertTrue(guard.accept(5))
        assertFalse(guard.accept(4))
        assertFalse(guard.accept(0))
        assertFalse(guard.accept(9_007_199_254_740_992L))
    }

    @Test fun captureSizeIsCappedAndAlignedToSixteen() {
        assertEquals(720 to 1600, screenCaptureSize(1080, 2400, 1600))
        assertEquals(576 to 1280, screenCaptureSize(1080, 2400, 1280))
        assertEquals(1600 to 720, screenCaptureSize(2400, 1080, 1600))
        assertEquals(720 to 1280, screenCaptureSize(720, 1280, 1600))
        assertEquals(1440 to 1600, screenCaptureSize(1440, 1600, 1600))
        assertTrue(screenCaptureSize(1080, 2400, 960).let { (w, h) -> w % 16 == 0 && h % 16 == 0 && h <= 960 })
    }
}
