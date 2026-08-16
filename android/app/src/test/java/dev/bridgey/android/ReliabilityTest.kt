package dev.bridgey.android

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReliabilityTest {
    @Test fun boundedReaderAcceptsFramesAndEndOfStream() {
        val reader = BufferedReader(StringReader("first\r\nsecond"))

        assertEquals("first", reader.readProtocolLine())
        assertEquals("second", reader.readProtocolLine())
        assertNull(reader.readProtocolLine())
    }

    @Test fun boundedReaderRejectsOversizedFrameBeforeDispatch() {
        val reader = BufferedReader(StringReader("x".repeat(9)))

        assertThrows(ProtocolFrameTooLargeException::class.java) {
            reader.readProtocolLine(maxChars = 8)
        }
    }

    @Test fun reconnectBackoffIsBounded() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(::reconnectDelayMillis))
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
