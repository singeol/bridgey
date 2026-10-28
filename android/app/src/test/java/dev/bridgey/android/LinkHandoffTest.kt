package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkHandoffTest {
    @Test fun sharedTextYieldsOnlyOneSafeWebLink() {
        assertEquals("https://example.com/a?b=c", sharedWebLink("https://example.com/a?b=c"))
        assertEquals("https://example.com/story", sharedWebLink("A headline worth reading\nhttps://example.com/story"))
        assertEquals("http://example.com", sharedWebLink("  http://example.com  "))
        listOf(
            "no link here",
            "two https://example.com/one and https://example.com/two",
            "javascript:alert(1)",
            "see file:///etc/passwd",
            "https://user:pass@example.com",
            "x".repeat(8193) + " https://example.com",
        ).forEach { assertNull(it.take(40), sharedWebLink(it)) }
    }

    @Test fun hostIsShownInsteadOfTheWholeLink() {
        assertEquals("example.com", linkHost("https://example.com/private/path?token=1"))
    }
}
