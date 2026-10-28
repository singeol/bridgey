package dev.bridgey.android.screen

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStreamTest {
    private var now = 1_000L
    private var sequence = 0L
    private val json = mutableListOf<Pair<String, Map<String, Any>>>()
    private val frames = mutableListOf<ByteArray>()
    private var linkUp = true
    private var available = true
    private val keyRequests = mutableListOf<Long>()
    private val bitrates = mutableListOf<Long>()
    private var ended: ScreenStopReason? = null

    /** Like the session: every message, JSON or frame, takes the next sequence. */
    private val link = object : ScreenLink {
        override fun sendJson(kind: String, payload: (Long) -> Map<String, Any>): Boolean {
            if (linkUp) json += kind to payload(++sequence)
            return linkUp
        }

        override fun sendFrame(plaintext: (Long) -> ByteArray): Boolean {
            if (linkUp) frames += plaintext(++sequence)
            return linkUp
        }

        override fun alive(): Boolean = linkUp && available
    }

    private fun make(): ScreenStream {
        val stream = ScreenStream("MacBook", link, clock = { now }, streamId = STREAM)
        stream.encoder = object : ScreenEncoderControl {
            override fun requestKeyFrame() { keyRequests += now }
            override fun setBitrate(bitsPerSecond: Long) { bitrates += bitsPerSecond }
        }
        stream.onEnded = { ended = it }
        return stream
    }

    private fun started(stream: ScreenStream) = stream.receive(ScreenMacMessage.Started(STREAM, 1))

    private fun key(size: Int = 100) = ByteArray(size).also { it[3] = 1; it[4] = 0x67 }

    private fun delta(size: Int = 100) = ByteArray(size).also { it[2] = 1; it[3] = 0x41 }

    /** Sequence, frame number, part, and the key, parameter sets and last flags of a part. */
    private fun header(part: ByteArray): List<Any> {
        val buffer = ByteBuffer.wrap(part)
        val flags = part[1].toInt()
        return listOf(buffer.getLong(20), buffer.getInt(28).toLong(), buffer.getShort(2).toInt(), flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
    }

    @Test fun startCarriesTheSize() {
        val stream = make()
        assertTrue(stream.start(720, 1600))
        val (kind, payload) = json.single()
        assertEquals(ScreenKinds.START, kind)
        assertEquals(1L, payload["sequence"])
        assertEquals(STREAM, payload["streamId"])
        assertEquals(720, payload["width"])
        assertEquals(4_000_000L, payload["bitrate"])
    }

    @Test fun framesWaitForStartedThenAKeyFrame() {
        val stream = make()
        stream.start(720, 1600)
        stream.onEncodedFrame(key(), key = true, parameterSets = true, captureMillis = now, captureMicros = 1)
        assertTrue("nothing before screen.started", frames.isEmpty())
        started(stream)
        assertEquals(ScreenStreamPhase.STREAMING, stream.phase)
        assertEquals("started asks for a key frame", 1, keyRequests.size)
        stream.onEncodedFrame(delta(50), key = false, parameterSets = false, captureMillis = now, captureMicros = 2)
        assertTrue("a P frame before the key frame is skipped", frames.isEmpty())
        stream.onEncodedFrame(key(), key = true, parameterSets = true, captureMillis = now, captureMicros = 3)
        assertEquals(listOf<Any>(2L, 1L, 0, true, true, true), header(frames.single()))
        assertTrue("skips are not congestion", bitrates.isEmpty())
    }

    @Test fun aLargeFrameGoesInNumberedParts() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        stream.onEncodedFrame(key(32_768 * 2 + 5), key = true, parameterSets = true, captureMillis = now, captureMicros = 1)
        assertEquals(listOf(32_808, 32_808, 45), frames.map { it.size })
        assertEquals(
            listOf(listOf<Any>(2L, 1L, 0, true, true, false), listOf<Any>(3L, 1L, 1, true, true, false), listOf<Any>(4L, 1L, 2, true, true, true)),
            frames.map(::header),
        )
    }

    @Test fun aFrameOverSixtyFourPartsIsNeverSent() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        stream.onEncodedFrame(key(32_768 * 64 + 1), key = true, parameterSets = true, captureMillis = now, captureMicros = 1)
        assertTrue(frames.isEmpty())
        assertEquals(listOf(3_000_000L), bitrates)
    }

    @Test fun sequencesStayInOrderAcrossFramesStatsAndStop() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        stream.onEncodedFrame(key(40_000), key = true, parameterSets = true, captureMillis = now, captureMicros = 1)
        now += 1000
        stream.tick()
        stream.stop(ScreenStopReason.USER)
        assertEquals(listOf(2L, 3L), frames.map { header(it)[0] })
        assertEquals(listOf(ScreenKinds.START to 1L, ScreenKinds.STATS to 4L, ScreenKinds.STOP to 5L), json.map { it.first to it.second["sequence"] })
        assertEquals("user", json.last().second["reason"])
        assertEquals(ScreenStopReason.USER, ended)
    }

    @Test fun acksMoveTheWindow() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        repeat(6) { stream.onEncodedFrame(if (it == 0) key() else delta(), it == 0, it == 0, now, 1) }
        assertEquals(6, frames.size)
        stream.onEncodedFrame(delta(), false, false, now, 1)
        assertEquals("the seventh waits for an ack: dropped", 6, frames.size)
        assertEquals(listOf(3_000_000L), bitrates)
        stream.receive(ScreenMacMessage.Ack(STREAM, 2, 6, 1, false))
        stream.onEncodedFrame(delta(), false, false, now, 1)
        assertEquals("after a drop only a key frame goes", 6, frames.size)
        stream.onEncodedFrame(key(), true, true, now, 1)
        assertEquals(7, frames.size)
    }

    @Test fun keyframeAskFromTheMacIsHonoured() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        now += 1000
        stream.receive(ScreenMacMessage.Ack(STREAM, 2, 1, 0, true))
        assertEquals(2, keyRequests.size)
        stream.onEncodedFrame(delta(10), false, false, now, 1)
        assertTrue(frames.isEmpty())
    }

    @Test fun noStartedWithinFiveSecondsTimesOut() {
        val stream = make()
        stream.start(720, 1600)
        now += 4_999
        stream.tick()
        assertNull(ended)
        now += 1
        stream.tick()
        assertEquals(ScreenStopReason.TIMEOUT, ended)
        assertEquals("timeout", json.last().second["reason"])
    }

    @Test fun noAckForSixSecondsTimesOut() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        stream.onEncodedFrame(key(), true, true, now, 1)
        now += 5_000
        stream.tick()
        assertNull(ended)
        now += 1_000
        stream.tick()
        assertEquals(ScreenStopReason.TIMEOUT, ended)
    }

    @Test fun theMacStoppingEndsWithoutAnAnswer() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        stream.receive(ScreenMacMessage.Stop(STREAM, 2, ScreenStopReason.CLOSED))
        assertEquals(ScreenStopReason.CLOSED, ended)
        assertEquals(ScreenKinds.START, json.single().first)
    }

    @Test fun anotherStreamsMessagesAreIgnored() {
        val stream = make()
        stream.start(720, 1600)
        stream.receive(ScreenMacMessage.Started("00000000-0000-4000-8000-000000000000", 1))
        assertEquals(ScreenStreamPhase.STARTING, stream.phase)
    }

    @Test fun aFailedWriteEndsTheStreamQuietly() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        linkUp = false
        stream.onEncodedFrame(key(), true, true, now, 1)
        assertEquals(ScreenStopReason.FAILED, ended)
        assertEquals(ScreenStreamPhase.ENDED, stream.phase)
    }

    @Test fun losingTheFeatureEndsTheStreamOnTheNextTick() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        available = false
        stream.tick()
        assertEquals(ScreenStopReason.FAILED, ended)
        assertEquals("nothing more is sent", ScreenKinds.START, json.single().first)
    }

    @Test fun statsGoOutOnceASecond() {
        val stream = make()
        stream.start(720, 1600)
        started(stream)
        stream.onEncodedFrame(key(1000), true, true, now, 1)
        stream.receive(ScreenMacMessage.Ack(STREAM, 2, 1, 2, false))
        now += 1000
        stream.tick()
        val stats = json.last { it.first == ScreenKinds.STATS }.second
        assertEquals(1, stats["fps"])
        assertEquals(8000L, stats["sentBitrate"])
    }

    private companion object {
        const val STREAM = "6c2e9a14-3b5d-4e7f-8a90-1b2c3d4e5f60"
    }
}
