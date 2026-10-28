package dev.bridgey.android

import java.util.Locale

/**
 * Rules for browsing shared folders (docs/protocol.md, "Browsing shared
 * folders"). Pure Kotlin so the limits, ordering and paging run in JVM tests.
 */
internal object BrowseRules {
    const val MAX_PAGE_ENTRIES = 200
    const val MAX_REQUEST_BYTES = 8 * 1024
    const val MAX_ANSWER_BYTES = 32 * 1024

    /** Room left for the answer's own fields, the root ID and an echoed path of up to 1,024 bytes. */
    const val PAGE_ENTRY_BUDGET = MAX_ANSWER_BYTES - 4 * 1024
    const val MAX_IN_FLIGHT = 4
    const val MAX_PER_WINDOW = 20
    const val WINDOW_MILLIS = 10_000L

    /** `""` is the shared folder itself; anything else follows the folder path rules. */
    fun isValidPath(path: String): Boolean = path.isEmpty() || FolderRules.isValidPath(path)

    /** Hidden items and names the protocol cannot carry are neither listed nor resolved. */
    fun isVisible(name: String): Boolean = FolderRules.isValidName(name) && !name.startsWith(".")
}

internal data class BrowseEntry(val name: String, val isDirectory: Boolean, val size: Long?, val modified: Long?)

internal data class BrowsePage(val entries: List<BrowseEntry>, val total: Int, val nextOffset: Int?)

/** Folders first, then files; names ignoring case, then exactly, so the order is stable between pages. */
internal fun sortBrowseEntries(entries: List<BrowseEntry>): List<BrowseEntry> = entries.sortedWith(
    compareBy<BrowseEntry> { !it.isDirectory }
        .thenBy { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.name },
)

/** An upper bound for one entry's JSON, so a page never exceeds the answer limit. */
internal fun browseEntryBytes(entry: BrowseEntry): Int =
    utf8Length(entry.name) + entry.name.count { it == '"' || it == '\\' } + 80

/** The entries from [offset], at most [BrowseRules.MAX_PAGE_ENTRIES] and within [budget] bytes. */
internal fun browsePage(sorted: List<BrowseEntry>, offset: Int, budget: Int = BrowseRules.PAGE_ENTRY_BUDGET): BrowsePage {
    val start = offset.coerceIn(0, sorted.size)
    val page = ArrayList<BrowseEntry>()
    var bytes = 0
    for (index in start until sorted.size) {
        val size = browseEntryBytes(sorted[index])
        if (page.size == BrowseRules.MAX_PAGE_ENTRIES || (page.isNotEmpty() && bytes + size > budget)) break
        page += sorted[index]
        bytes += size
    }
    val next = start + page.size
    return BrowsePage(page, sorted.size, next.takeIf { it < sorted.size })
}

/** At most [maxInFlight] requests at a time and [maxPerWindow] started per [windowMillis]. */
internal class BrowseRequestBudget(
    private val maxInFlight: Int = BrowseRules.MAX_IN_FLIGHT,
    private val maxPerWindow: Int = BrowseRules.MAX_PER_WINDOW,
    private val windowMillis: Long = BrowseRules.WINDOW_MILLIS,
) {
    private val started = ArrayDeque<Long>()
    private var inFlight = 0

    @Synchronized fun tryStart(nowMillis: Long): Boolean {
        while (started.isNotEmpty() && nowMillis - started.first() >= windowMillis) started.removeFirst()
        if (inFlight >= maxInFlight || started.size >= maxPerWindow) return false
        started.addLast(nowMillis)
        inFlight++
        return true
    }

    @Synchronized fun finish() {
        if (inFlight > 0) inFlight--
    }

    @Synchronized fun reset() {
        started.clear()
        inFlight = 0
    }
}
