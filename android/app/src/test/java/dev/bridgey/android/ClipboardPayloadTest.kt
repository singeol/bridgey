package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPayloadTest {
    @Test fun richClipboardRequiresFallbackTextAndHtml() {
        assertNull(RichClipboardContent.create("text", null))
        assertNull(RichClipboardContent.create("", "<b>text</b>"))
        assertEquals("<b>text</b>", RichClipboardContent.create("text", "<b>text</b>")?.html)
    }

    @Test fun clipboardContentIsBoundedBeforeEncryption() {
        assertTrue(clipboardTextFits("x".repeat(MAX_CLIPBOARD_CONTENT_BYTES)))
        assertNull(RichClipboardContent.create("x".repeat(MAX_CLIPBOARD_CONTENT_BYTES), "<b>x</b>"))
    }
}
