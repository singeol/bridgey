package dev.bridgey.android

/**
 * Orders outgoing files so that only one is offered at a time. The next file
 * starts after the current one completes, fails, or is cancelled. Each file
 * still uses its own `files.offer`, so the wire protocol is unchanged.
 */
internal class FileSendQueue<T> {
    private val pending = ArrayDeque<Pair<String, T>>()
    private var current: String? = null

    @Synchronized fun currentId(): String? = current

    @Synchronized fun pendingIds(): List<String> = pending.map { it.first }

    @Synchronized fun isEmpty(): Boolean = current == null && pending.isEmpty()

    @Synchronized fun enqueue(id: String, item: T) {
        if (id == current || pending.any { it.first == id }) return
        pending.addLast(id to item)
    }

    /** Returns the next file to start, or null while a file is in progress or nothing is waiting. */
    @Synchronized fun startNext(): Pair<String, T>? {
        if (current != null) return null
        val next = pending.removeFirstOrNull() ?: return null
        current = next.first
        return next
    }

    /** Marks the current file as done. Returns false when the ID is not the current file. */
    @Synchronized fun finish(id: String): Boolean {
        if (current != id) return false
        current = null
        return true
    }

    /** Removes a file that has not started yet. */
    @Synchronized fun removePending(id: String): Boolean = pending.removeAll { it.first == id }

    /** Removes every file that has not started yet and returns their IDs in queue order. */
    @Synchronized fun removeAllPending(): List<String> = pendingIds().also { pending.clear() }

    /** Forgets the current and waiting files and returns their IDs, current file first. */
    @Synchronized fun removeAll(): List<String> = (listOfNotNull(current) + pendingIds()).also {
        current = null
        pending.clear()
    }
}
