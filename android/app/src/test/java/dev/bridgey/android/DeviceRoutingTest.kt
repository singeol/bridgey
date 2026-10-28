package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRoutingTest {
    private fun device(id: String, ringing: Boolean = false) = ConnectedDevice(id, id, PeerLinkState(ringing = ringing))

    @Test fun transfersBelongToTheirDevice() {
        val owners = TransferOwners()
        owners.assign("t1", "mac-a")
        owners.assign("t2", "mac-z")
        owners.assign("t3", "mac-a")
        assertEquals(setOf("t1", "t3"), owners.transfersOf("mac-a"))
        assertEquals(setOf("t2"), owners.transfersOf("mac-z"))
        assertTrue(owners.owns("t2", "mac-z"))
        assertFalse(owners.owns("t2", "mac-a"))
        assertNull(owners.owner("unknown"))
    }

    @Test fun transferOwnersStayBounded() {
        val owners = TransferOwners(limit = 2)
        owners.assign("t1", "mac-a")
        owners.assign("t2", "mac-a")
        owners.assign("t3", "mac-z")
        assertNull(owners.owner("t1"))
        assertEquals(setOf("t2"), owners.transfersOf("mac-a"))
        assertEquals("mac-z", owners.owner("t3"))
    }

    @Test fun stoppingFindReachesEveryRingingOrRequestingDevice() {
        val connections = listOf(device("mac-a", ringing = true), device("mac-m"), device("mac-q"), device("mac-z"))
        assertEquals(listOf("mac-a", "mac-m", "mac-z"), findStopTargets(connections, setOf("mac-z"), target = "mac-m"))
    }

    @Test fun stoppingFindWithNothingRingingGoesToTheSelectedDevice() {
        val connections = listOf(device("mac-a"), device("mac-z"))
        assertEquals(listOf("mac-z"), findStopTargets(connections, emptySet(), target = "mac-z"))
        assertEquals(emptyList<String>(), findStopTargets(emptyList(), emptySet(), target = null))
        assertEquals(emptyList<String>(), findStopTargets(connections, setOf("gone"), target = "gone"))
    }

    @Test fun autoDialPicksTrustedHigherIdsThatAreNotConnected() {
        // An empty string stands for a peer that did not advertise an ID.
        val peers = listOf("a-mac", "x-mac", "y-mac", "z-mac", "x-mac", "stranger", "")
        val targets = autoDialTargets(
            peers = peers,
            idOf = { it.ifEmpty { null } },
            localDeviceId = "m-phone",
            trustedIds = setOf("a-mac", "x-mac", "y-mac", "z-mac"),
            connectedIds = setOf("y-mac"),
        )
        // a-mac dials this phone itself; y-mac is connected; x-mac is listed twice.
        assertEquals(listOf("x-mac", "z-mac"), targets.map { it.second })
    }

    @Test fun aDeviceDisconnectedByTheUserStaysDisconnectedUntilTheUserConnects() {
        val held = HeldDevices()
        assertTrue(held.admits("mac-a", userInitiated = false))
        held.hold("mac-a")
        assertTrue(held.isHeld("mac-a"))
        assertFalse("its own reconnect is closed", held.admits("mac-a", userInitiated = false))
        assertTrue("other devices are not affected", held.admits("mac-b", userInitiated = false))
        assertTrue("connecting from Nearby lets it back", held.admits("mac-a", userInitiated = true))
        assertFalse(held.isHeld("mac-a"))
        assertTrue(held.admits("mac-a", userInitiated = false))
        held.hold("mac-b")
        held.release("mac-b")
        assertFalse(held.isHeld("mac-b"))
    }
}
