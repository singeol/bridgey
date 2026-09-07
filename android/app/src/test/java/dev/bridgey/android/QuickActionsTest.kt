package dev.bridgey.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class QuickActionsTest {
    @Test fun encryptedRequestSequenceRejectsReplayIndependentlyOfOuterMessageId() {
        val sequence = QuickRequestSequence()
        assertTrue(sequence.accept("media", 4))
        assertFalse(sequence.accept("media", 4))
        assertFalse(sequence.accept("media", 3))
        assertTrue(sequence.accept("links", 1))
        assertTrue(sequence.accept("media", 5))
        assertFalse(sequence.accept("other", 6))
        assertFalse(sequence.accept("media", Long.MAX_VALUE))
    }
    @Test fun webLinksAllowOnlyExplicitBrowserNavigation() {
        listOf("https://example.com/path?q=one#two", "http://localhost:8080/", "https://[::1]/").forEach {
            assertEquals(it, validatedWebLink(it))
        }
        assertEquals("https://example.com", validatedWebLink("  https://example.com\n"))
        listOf("", "file:///etc/passwd", "javascript:alert(1)", "tel:+12345678", "bridgey://call", "https://",
            "https://user:pass@example.com", "https://example.com:0", "https://example.com:65536",
            "https://exa mple.com", "https://example.com/\nfoo", "https://example.com/\\evil",
            "https://example.com/\u0000", "https://example.com/" + "a".repeat(4096)).forEach {
            assertNull("Unsafe or oversized link: ${it.take(60)}", validatedWebLink(it))
        }
    }

    @Test fun newFeaturesAreNotAdvertisedByLegacyPeers() {
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.LINKS))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.MEDIA))
    }

    @Test fun newComponentsCannotBeInvokedByArbitraryApplications() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val confirmation = (0 until activities.length).map { activities.item(it) }.first {
            it.attributes.getNamedItemNS(ns, "name")?.nodeValue == ".ConfirmCallActivity"
        }
        assertEquals("false", confirmation.attributes.getNamedItemNS(ns, "exported").nodeValue)
        val services = doc.getElementsByTagName("service")
        val tile = (0 until services.length).map { services.item(it) }.first {
            it.attributes.getNamedItemNS(ns, "name")?.nodeValue == ".ClipboardTileService"
        }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.attributes.getNamedItemNS(ns, "permission").nodeValue)
    }
}
