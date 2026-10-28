package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFolderBrowseTest {
    private fun file(name: String) = BrowseEntry(name, isDirectory = false, size = 1, modified = null)
    private fun dir(name: String) = BrowseEntry(name, isDirectory = true, size = null, modified = null)

    @Test fun pathsStayInsideTheSharedFolder() {
        assertTrue(BrowseRules.isValidPath(""))
        assertTrue(BrowseRules.isValidPath("DCIM/Camera"))
        listOf("..", "../x", "a/../b", "/abs", "a/", "a//b", ".", "a/./b", "nul\u0000").forEach {
            assertFalse(it, BrowseRules.isValidPath(it))
        }
    }

    @Test fun hiddenAndUnsendableNamesAreNotVisible() {
        assertTrue(BrowseRules.isVisible("photo.jpg"))
        assertFalse(BrowseRules.isVisible(".nomedia"))
        assertFalse(BrowseRules.isVisible("line\nbreak"))
        assertFalse(BrowseRules.isVisible("n".repeat(256)))
    }

    @Test fun foldersComeFirstThenNamesIgnoringCase() {
        val sorted = sortBrowseEntries(listOf(file("b.txt"), dir("Zed"), file("A.txt"), dir("alpha"), file("a.txt")))
        assertEquals(listOf("alpha", "Zed", "A.txt", "a.txt", "b.txt"), sorted.map(BrowseEntry::name))
    }

    @Test fun pagesAreBoundedByCountAndBytes() {
        val entries = (0 until 450).map { file("file-%03d.txt".format(it)) }

        val first = browsePage(entries, 0)
        assertEquals(200, first.entries.size)
        assertEquals(450, first.total)
        assertEquals(200, first.nextOffset)
        val last = browsePage(entries, 400)
        assertEquals(50, last.entries.size)
        assertNull(last.nextOffset)
        assertEquals(0, browsePage(entries, 999).entries.size)

        val long = (0 until 300).map { file("n".repeat(250) + it) }
        val page = browsePage(long, 0)
        assertTrue(page.entries.size < 200)
        assertTrue(page.entries.sumOf(::browseEntryBytes) <= BrowseRules.PAGE_ENTRY_BUDGET)
        assertEquals(page.entries.size, page.nextOffset)
    }

    @Test fun requestBudgetLimitsConcurrencyAndRate() {
        val budget = BrowseRequestBudget(maxInFlight = 2, maxPerWindow = 3, windowMillis = 1_000)
        assertTrue(budget.tryStart(0))
        assertTrue(budget.tryStart(0))
        assertFalse(budget.tryStart(0))
        budget.finish()
        assertTrue(budget.tryStart(10))
        budget.finish()
        assertFalse(budget.tryStart(20))
        assertTrue(budget.tryStart(1_000))
    }
}
