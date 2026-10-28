package dev.bridgey.android.screen

import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenProtocolTest {
    private val stream = "6c2e9a14-3b5d-4e7f-8a90-1b2c3d4e5f60"

    @Test fun headerIsFortyBytesBigEndian() {
        val header = ScreenFrameHeader(stream, sequence = 0x0102030405L, frame = 0xFFFF_FFFFL, part = 3, key = true, parameterSets = false, last = true, captureMicros = 7)
        val bytes = ScreenFrameCodec.encode(header, byteArrayOf(9, 8))
        assertEquals(42, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(5, bytes[1].toInt())
        assertEquals(listOf(0, 3), listOf(bytes[2].toInt(), bytes[3].toInt()))
        assertEquals(0x6c.toByte(), bytes[4])
        assertEquals(0x60.toByte(), bytes[19])
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2, 3, 4, 5), bytes.copyOfRange(20, 28))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), bytes.copyOfRange(28, 32))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 7), bytes.copyOfRange(32, 40))
        assertArrayEquals(byteArrayOf(9, 8), bytes.copyOfRange(40, 42))
        assertEquals(header, decodeHeader(bytes))
    }

    @Test fun partsAreBoundedAndCounted() {
        val header = ScreenFrameHeader(stream, 1, 1, 0, key = true, parameterSets = true, last = true, captureMicros = 0)
        assertEquals(32_808, ScreenFrameCodec.encode(header, ByteArray(32_768)).size)
        assertTrue(runCatching { ScreenFrameCodec.encode(header, ByteArray(32_769)) }.isFailure)
        assertTrue(runCatching { ScreenFrameCodec.encode(header, ByteArray(0)) }.isFailure)
        assertTrue(runCatching { ScreenFrameCodec.encode(header.copy(part = 64), ByteArray(1)) }.isFailure)
        assertTrue(runCatching { ScreenFrameCodec.encode(header.copy(sequence = 0), ByteArray(1)) }.isFailure)
        assertTrue(runCatching { ScreenFrameCodec.encode(header.copy(frame = 0), ByteArray(1)) }.isFailure)
        assertEquals(1, ScreenFrameCodec.partCount(1))
        assertEquals(1, ScreenFrameCodec.partCount(32_768))
        assertEquals(3, ScreenFrameCodec.partCount(32_768 * 2 + 5))
        assertEquals(64, ScreenFrameCodec.partCount(32_768 * 64))
    }

    @Test fun annexBTypes() {
        val unit = byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(unit))
        assertTrue(AnnexB.startsWithSps(unit))
        assertFalse(AnnexB.startsWithSps(byteArrayOf(0, 0, 1, 0x41)))
        assertEquals(4, AnnexB.startCodeLength(unit, 0))
        assertEquals(3, AnnexB.startCodeLength(unit, 12))
        assertEquals(0, AnnexB.startCodeLength(unit, 4))
    }

    @Test fun macMessagesDecode() {
        val ack = mapOf("version" to 1, "streamId" to stream, "sequence" to 2, "frame" to 3L, "decodeMs" to 4)
        assertEquals(ScreenMacMessage.Ack(stream, 2, 3, 4, false), decodeScreenMacMessage(ScreenKinds.ACK, ack::get))
        assertEquals(ScreenMacMessage.Ack(stream, 2, 3, 4, true), decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("keyframe" to true))::get))
        assertEquals(ScreenMacMessage.Started(stream, 9), decodeScreenMacMessage(ScreenKinds.STARTED, mapOf("version" to 1, "streamId" to stream, "sequence" to 9)::get))
        val stop = mapOf("version" to 1, "streamId" to stream.uppercase(), "sequence" to 3, "reason" to "closed")
        assertEquals(ScreenMacMessage.Stop(stream, 3, ScreenStopReason.CLOSED), decodeScreenMacMessage(ScreenKinds.STOP, stop::get))
        assertEquals(ScreenStopReason.FAILED, (decodeScreenMacMessage(ScreenKinds.STOP, (stop - "reason")::get) as ScreenMacMessage.Stop).reason)
        assertEquals(ScreenStopReason.FAILED, ScreenStopReason.fromWire("later"))
    }

    @Test fun malformedMacMessagesAreDropped() {
        val ack = mapOf<String, Any>("version" to 1, "streamId" to stream, "sequence" to 2, "frame" to 3, "decodeMs" to 4)
        assertNull(decodeScreenMacMessage(ScreenKinds.FRAME, ack::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.START, ack::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("version" to 2))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("version" to true))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("sequence" to 0))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("sequence" to 9_007_199_254_740_992L))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("sequence" to 2.0))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("streamId" to "not-a-uuid"))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("streamId" to stream.replace("-", "")))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("frame" to 0))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("frame" to 4_294_967_296L))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("decodeMs" to 60_001))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack + ("keyframe" to "yes"))::get))
        assertNull(decodeScreenMacMessage(ScreenKinds.ACK, (ack - "frame")::get))
    }

    @Test fun payloadsCarryTheDocumentedFields() {
        val start = screenStartPayload(stream, 1, ScreenStartInfo(720, 1600, 30, 4_000_000))
        assertEquals(
            mapOf("version" to 1, "streamId" to stream, "sequence" to 1L, "codec" to "h264", "width" to 720, "height" to 1600, "fps" to 30, "bitrate" to 4_000_000L),
            start,
        )
        val stats = screenStatsPayload(stream, 2, ScreenStats(70_000, -1, 60_000_000, 1, 500, 5000))
        assertEquals(60_000L, stats["latencyMs"])
        assertEquals(0L, stats["rttMs"])
        assertEquals(50_000_000L, stats["bitrate"])
        assertEquals(120, stats["fps"])
        assertEquals(1000, stats["dropped"])
        assertEquals("screen_off", screenStopPayload(stream, 3, ScreenStopReason.SCREEN_OFF)["reason"])
    }

    /** The Mac's reading of a frame header, to check the phone's writer against the documented layout. */
    private fun decodeHeader(bytes: ByteArray): ScreenFrameHeader {
        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(1, buffer.get().toInt())
        val flags = buffer.get().toInt()
        val part = buffer.short.toInt()
        val streamId = UUID(buffer.long, buffer.long).toString()
        val sequence = buffer.long
        val frame = buffer.int.toLong() and 0xFFFF_FFFFL
        val capture = buffer.long
        return ScreenFrameHeader(streamId, sequence, frame, part, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, capture)
    }
}
