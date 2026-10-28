package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HANDLE = "3f2c9a10-1b2c-4d5e-8f90-a1b2c3d4e5f6"

class MacActionsTest {
    @Test fun identifiersAreFixedNamesOrOpaqueHandles() {
        assertEquals(MacActionKind.SYSTEM, MacActionIds.kind("lock"))
        assertEquals(MacActionKind.SYSTEM, MacActionIds.kind("display_sleep"))
        assertEquals(MacActionKind.VOLUME, MacActionIds.kind("volume_set"))
        assertEquals(MacActionKind.APP, MacActionIds.kind("app:$HANDLE"))
        assertEquals(MacActionKind.SHORTCUT, MacActionIds.kind("shortcut:$HANDLE"))
        listOf("", "Lock", "lock ", "app:com.apple.Safari", "app:/Applications/Safari.app", "app:${HANDLE.uppercase()}",
            "shortcut:Focus", "shortcut:$HANDLE\n", "do shell script", "app:$HANDLE;rm").forEach {
            assertNull(it, MacActionIds.kind(it))
        }
    }

    @Test fun stateDropsBadEntriesAndRejectsMalformedLists() {
        val state = macActionsState(1, listOf(
            "lock" to "Lock screen",
            "app:$HANDLE" to "Editor",
            "app:$HANDLE" to "Repeated",
            "app:com.example.Editor" to "Bundle IDs are not identifiers",
            "shortcut:$HANDLE" to "Line\nbreak",
            "sleep" to "",
            "screensaver" to "x".repeat(65),
            42 to "Not a string",
            "volume_up" to null,
        ), MacVolume(30, false))
        assertNotNull(state)
        assertEquals(listOf("lock", "app:$HANDLE"), state!!.entries.map { it.id })
        assertEquals(listOf("lock"), state.system.map { it.id })
        assertEquals(listOf("Editor"), state.apps.map { it.title })
        assertTrue(state.shortcuts.isEmpty())
        assertEquals(MacVolume(30, false), state.volume)
        assertTrue(state.has("lock"))
        assertFalse(state.has("sleep"))

        assertNull(macActionsState(2, emptyList(), null))
        assertNull(macActionsState(1, null, null))
        assertNull(macActionsState(1, List(49) { "lock" to "Lock" }, null))
        assertEquals(1, macActionsState(1, List(48) { "lock" to "Lock" }, null)!!.entries.size)
    }

    @Test fun titlesAndVolumeAreBounded() {
        assertEquals("Lights off", validMacActionTitle("Lights off"))
        assertEquals("Ф".repeat(64), validMacActionTitle("Ф".repeat(64)))
        listOf(null, 7, "", "   ", "a".repeat(65), "tab\there", "line\u2028separator").forEach {
            assertNull(it.toString(), validMacActionTitle(it))
        }
        assertEquals(MacVolume(0, true), validMacVolume(0, true))
        assertNull(validMacVolume(101, false))
        assertNull(validMacVolume(-1, false))
        assertNull(validMacVolume(50L, false))
        assertNull(validMacVolume(50, "false"))
    }

    @Test fun resultReasonsMapToFixedMessages() {
        assertEquals(R.string.mac_action_done, macActionResultRes(true, null))
        assertEquals(R.string.mac_action_refused_rate_limited, macActionResultRes(false, "rate_limited"))
        assertEquals(R.string.mac_action_refused_permission, macActionResultRes(false, "permission_required"))
        assertEquals(R.string.mac_action_refused_failed, macActionResultRes(false, "<script>"))
        assertEquals(R.string.mac_action_refused_failed, macActionResultRes(false, null))
        MacActionIds.SYSTEM.plus(MacActionIds.VOLUME).forEach { assertNotNull(it, macActionTitleRes(it)) }
        assertNull(macActionTitleRes("app:$HANDLE"))
    }

    @Test fun macActionsAreNotAdvertisedByLegacyPeers() {
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.MAC_ACTIONS))
    }
}
