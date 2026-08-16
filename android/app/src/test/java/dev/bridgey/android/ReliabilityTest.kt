package dev.bridgey.android

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReliabilityTest {
    @Test fun boundedReaderAcceptsFramesAndEndOfStream() {
        val reader = ByteArrayInputStream("first\r\nsecond".toByteArray())

        assertEquals("first", reader.readProtocolLine())
        assertEquals("second", reader.readProtocolLine())
        assertNull(reader.readProtocolLine())
    }

    @Test fun boundedReaderRejectsOversizedFrameBeforeDispatch() {
        val reader = ByteArrayInputStream("x".repeat(9).toByteArray())

        assertThrows(ProtocolFrameTooLargeException::class.java) {
            reader.readProtocolLine(maxBytes = 8)
        }
    }

    @Test fun boundedReaderAcceptsFrameAtLimit() {
        val reader = ByteArrayInputStream(("x".repeat(8) + "\n").toByteArray())

        assertEquals("x".repeat(8), reader.readProtocolLine(maxBytes = 8))
    }

    @Test fun boundedReaderRejectsMalformedUtf8() {
        val reader = ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()))

        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            reader.readProtocolLine()
        }
    }

    @Test fun reconnectBackoffIsBounded() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(::reconnectDelayMillis))
    }

    @Test fun heartbeatTimeoutRequiresNegotiatedSupport() {
        assertFalse(heartbeatExpired(false, lastReceivedAtMillis = 0, nowMillis = 60_000))
        assertFalse(heartbeatExpired(true, lastReceivedAtMillis = 40_000, nowMillis = 60_000))
        assertEquals(true, heartbeatExpired(true, lastReceivedAtMillis = 30_000, nowMillis = 60_000))
    }

    @Test fun interruptedTransfersBecomeRecoverableHistory() {
        val active = FileTransferState("a", "one", "Sending", true, 42, 2, true)
        val complete = FileTransferState("b", "two", "Saved", false, 100, 1, false)

        val recovered = recoverInterruptedTransfers(mapOf(active.id to active, complete.id to complete))

        assertFalse(recovered.getValue("a").active)
        assertEquals("Transfer interrupted — reconnect to retry", recovered.getValue("a").status)
        assertNull(recovered.getValue("a").progressPercent)
        assertEquals("Saved", recovered.getValue("b").status)
    }
}
