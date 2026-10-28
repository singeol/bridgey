package dev.bridgey.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Node

private const val MAC = "11111111-2222-3333-4444-555555555555"
private const val OTHER = "99999999-2222-3333-4444-555555555555"
private const val APP = "app:3f2c9a10-1b2c-4d5e-8f90-a1b2c3d4e5f6"

class MacControlsTest {
    private val actions = MacActionsState(
        listOf(
            MacActionEntry("lock", "Lock screen"),
            MacActionEntry("volume_mute", "Mute"),
            MacActionEntry("volume_set", "Set volume"),
            MacActionEntry(APP, "Editor"),
        ),
        MacVolume(35, true),
    )

    private fun mac(
        id: String = MAC,
        connected: Boolean = true,
        live: Boolean = true,
        actions: MacActionsState? = this.actions,
        ringing: Boolean = false,
    ) = ControlMac(id, "Studio Mac", connected, live, actions, pingAvailable = connected, findAvailable = connected, ringing = ringing)

    @Test fun controlsFollowTheMacListInAStableOrder() {
        val specs = controlSpecs(listOf(mac()))
        assertEquals(
            listOf("$MAC|action:lock", "$MAC|volume", "$MAC|action:volume_mute", "$MAC|action:$APP", "$MAC|ping", "$MAC|find"),
            specs.map { it.controlId },
        )
        assertTrue(specs.all { it.state == ControlState.READY })
        assertEquals("Editor", specs[3].customTitle)
        assertNull(specs[0].customTitle)
        assertEquals(35, specs[1].volume?.level)
        assertTrue(specs[2].checked)
    }

    @Test fun offlineMacsKeepTheirSavedControlsButCannotRunThem() {
        val specs = controlSpecs(listOf(mac(connected = false, live = false), mac(id = OTHER, connected = false, actions = null)))
        assertTrue(specs.all { it.state == ControlState.OFFLINE })
        assertEquals(6 + 2, specs.size)
        specs.forEach { assertNull(controlCommand(it, 50f, true)) }
    }

    @Test fun actionsWithoutALiveListAreUnavailable() {
        val specs = controlSpecs(listOf(mac(live = false)))
        assertEquals(ControlState.UNAVAILABLE, specs.first { it.kind == ControlKind.ACTION }.state)
        assertEquals(ControlState.READY, specs.first { it.kind == ControlKind.PING }.state)
    }

    @Test fun tapsMapToCommandsTheMacStillChecks() {
        val specs = controlSpecs(listOf(mac(ringing = true))).associateBy { it.controlId }
        assertEquals(ControlCommand.Run("lock"), controlCommand(specs.getValue("$MAC|action:lock")))
        assertEquals(ControlCommand.Run("volume_set", 100), controlCommand(specs.getValue("$MAC|volume"), 140f))
        assertEquals(ControlCommand.Run("volume_set", 0), controlCommand(specs.getValue("$MAC|volume"), -3f))
        assertNull(controlCommand(specs.getValue("$MAC|volume"), Float.NaN))
        assertNull(controlCommand(specs.getValue("$MAC|volume")))
        assertEquals(ControlCommand.Run("volume_mute"), controlCommand(specs.getValue("$MAC|action:volume_mute"), booleanValue = false))
        assertEquals(ControlCommand.Run(APP), controlCommand(specs.getValue("$MAC|action:$APP")))
        assertEquals(ControlCommand.Ping, controlCommand(specs.getValue("$MAC|ping")))
        assertEquals(ControlCommand.Find(false), controlCommand(specs.getValue("$MAC|find")))
        assertEquals(ControlCommand.Find(true), controlCommand(specs.getValue("$MAC|find"), booleanValue = true))
    }

    @Test fun widgetPrefersTheConnectedMac() {
        val offline = mac(id = OTHER, connected = false)
        val online = mac()
        assertEquals(MAC, widgetMac(listOf(offline, online))?.deviceId)
        assertEquals(OTHER, widgetMac(listOf(mac(connected = false, actions = null), offline))?.deviceId)
        assertNull(widgetMac(emptyList()))
        assertEquals(
            listOf("lock", "display_sleep", "sleep", "volume_down", "volume_mute", "volume_up"),
            MAC_WIDGET_BUTTONS.map { it.second },
        )
    }

    @Test fun controlsAndWidgetTapsCannotBeTriggeredByOtherApplications() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        fun component(tag: String, name: String): Node {
            val nodes = doc.getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) }.first { it.attributes.getNamedItemNS(ns, "name")?.nodeValue == name }
        }
        fun attribute(node: Node, name: String) = node.attributes.getNamedItemNS(ns, name)?.nodeValue
        assertEquals("android.permission.BIND_CONTROLS", attribute(component("service", ".MacControlsService"), "permission"))
        assertEquals("false", attribute(component("receiver", ".MacWidgetActionReceiver"), "exported"))
        val provider = component("receiver", ".MacWidgetProvider")
        val actions = (provider as org.w3c.dom.Element).getElementsByTagName("action")
        assertEquals(listOf("android.appwidget.action.APPWIDGET_UPDATE"),
            (0 until actions.length).map { attribute(actions.item(it), "name") })
    }
}
