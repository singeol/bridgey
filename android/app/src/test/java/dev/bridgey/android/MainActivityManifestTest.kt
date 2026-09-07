package dev.bridgey.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MainActivityManifestTest {
    @Test
    fun shareIntentsReuseTheExistingMainActivityTask() {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .firstOrNull {
                it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue == ".MainActivity"
            }

        assertNotNull("MainActivity must be declared", mainActivity)
        assertEquals(
            "singleTask",
            mainActivity!!.attributes.getNamedItemNS(androidNamespace, "launchMode")?.nodeValue,
        )
        assertEquals(
            "never",
            mainActivity.attributes.getNamedItemNS(androidNamespace, "documentLaunchMode")?.nodeValue,
        )
    }
}
