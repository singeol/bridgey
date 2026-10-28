package dev.bridgey.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ManifestPermissionsTest {
    @Test
    fun manifestDeclaresOnlyRequiredPermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.CHANGE_WIFI_MULTICAST_STATE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                // Experimental screen sharing: the capture runs in a mediaProjection foreground service.
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
                "android.permission.CALL_PHONE",
                "android.permission.READ_PHONE_STATE",
                "android.permission.ANSWER_PHONE_CALLS",
            ),
            declared,
        )
    }

    @Test
    fun screenSharingRunsInAPrivateMediaProjectionService() {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        val service = (0 until services.length)
            .map { services.item(it) }
            .firstOrNull {
                it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue == ".screen.ScreenShareService"
            }

        assertNotNull("ScreenShareService must be declared", service)
        assertEquals("false", service!!.attributes.getNamedItemNS(androidNamespace, "exported")?.nodeValue)
        assertEquals(
            "mediaProjection",
            service.attributes.getNamedItemNS(androidNamespace, "foregroundServiceType")?.nodeValue,
        )
    }
}
