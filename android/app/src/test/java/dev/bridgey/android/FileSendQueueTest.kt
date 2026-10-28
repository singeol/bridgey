package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSendQueueTest {
    @Test fun filesStartOneAfterAnotherInSelectionOrder() {
        val queue = FileSendQueue<String>()
        queue.enqueue("a", "first.txt")
        queue.enqueue("b", "second.txt")

        assertEquals("a" to "first.txt", queue.startNext())
        assertNull(queue.startNext())
        assertEquals(listOf("b"), queue.pendingIds())

        assertTrue(queue.finish("a"))
        assertEquals("b" to "second.txt", queue.startNext())
        assertTrue(queue.finish("b"))
        assertNull(queue.startNext())
        assertTrue(queue.isEmpty())
    }

    @Test fun finishingAnotherTransferDoesNotAdvanceTheQueue() {
        val queue = FileSendQueue<String>()
        queue.enqueue("a", "first.txt")
        queue.enqueue("b", "second.txt")
        queue.startNext()

        assertFalse(queue.finish("incoming"))
        assertFalse(queue.finish("b"))
        assertEquals("a", queue.currentId())
        assertTrue(queue.finish("a"))
        assertFalse(queue.finish("a"))
    }

    @Test fun cancellingAWaitingFileKeepsTheRest() {
        val queue = FileSendQueue<String>()
        listOf("a", "b", "c").forEach { queue.enqueue(it, "$it.txt") }
        queue.startNext()

        assertTrue(queue.removePending("b"))
        assertFalse(queue.removePending("a"))
        assertEquals("a", queue.currentId())
        assertEquals(listOf("c"), queue.pendingIds())
    }

    @Test fun cancellingTheRemainingQueueLeavesTheCurrentFile() {
        val queue = FileSendQueue<String>()
        listOf("a", "b", "c").forEach { queue.enqueue(it, "$it.txt") }
        queue.startNext()

        assertEquals(listOf("b", "c"), queue.removeAllPending())
        assertEquals("a", queue.currentId())
        assertTrue(queue.finish("a"))
        assertNull(queue.startNext())
    }

    @Test fun interruptionForgetsCurrentAndWaitingFiles() {
        val queue = FileSendQueue<String>()
        listOf("a", "b").forEach { queue.enqueue(it, "$it.txt") }
        queue.startNext()

        assertEquals(listOf("a", "b"), queue.removeAll())
        assertTrue(queue.isEmpty())
        assertFalse(queue.finish("a"))
    }

    @Test fun duplicateIdsAreIgnored() {
        val queue = FileSendQueue<String>()
        queue.enqueue("a", "first.txt")
        queue.enqueue("a", "again.txt")
        queue.startNext()
        queue.enqueue("a", "again.txt")

        assertEquals(emptyList<String>(), queue.pendingIds())
    }
}
