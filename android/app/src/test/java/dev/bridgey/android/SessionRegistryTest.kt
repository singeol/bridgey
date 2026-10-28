package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several devices per phone, duplicates and per-device isolation. Mirrors the Mac SessionRegistryTests. */
class SessionRegistryTest {
    private class FakeSession(val name: String)

    // The phone's ID sorts between the two Macs: "a-mac" dials it, it dials "z-mac".
    private val phoneId = "m-phone"
    private val macA = "a-mac"
    private val macZ = "z-mac"

    private fun SessionRegistry<FakeSession>.connect(session: FakeSession, id: String, local: Boolean): Admission<FakeSession> {
        addPending(session, initiatedLocally = local)
        return authenticate(session, id, session.name)
    }

    @Test fun severalDevicesSideBySide() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        val a = FakeSession("Studio Mac")
        val z = FakeSession("MacBook")
        assertEquals(Admission.Accepted<FakeSession>(null), registry.connect(a, macA, local = false))
        assertEquals(Admission.Accepted<FakeSession>(null), registry.connect(z, macZ, local = true))
        assertEquals(listOf(macA, macZ), registry.connectedIds)
        assertSame(a, registry.sessionFor(macA))
        assertEquals(macZ, registry.deviceIdOf(z))
        assertEquals(listOf("Studio Mac", "MacBook"), registry.connections.map { it.name })
    }

    @Test fun pairingSurvivesTrustedDeviceReconnecting() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        val stale = FakeSession("Studio Mac (old)")
        registry.connect(stale, macA, local = false)
        val pairing = FakeSession("New Mac pairing")
        registry.addPending(pairing, initiatedLocally = false)
        val reconnect = FakeSession("Studio Mac (reconnect)")
        assertEquals(Admission.Accepted(stale), registry.connect(reconnect, macA, local = false))
        assertTrue(registry.isPending(pairing))
        assertNull(registry.remove(stale))
        assertSame(reconnect, registry.sessionFor(macA))
        assertEquals(Admission.Accepted<FakeSession>(null), registry.authenticate(pairing, macZ, "MacBook"))
        assertEquals(listOf(macA, macZ), registry.connectedIds)
        assertTrue(registry.pendingSessions.isEmpty())
    }

    @Test fun duplicateKeepsSocketDialledByLowerId() {
        // m-phone < z-mac: the phone dials, so its socket wins whichever arrives second.
        val registry = SessionRegistry<FakeSession>(phoneId)
        val phoneDialled = FakeSession("phone dialled")
        val macDialled = FakeSession("mac dialled")
        registry.connect(phoneDialled, macZ, local = true)
        assertEquals(Admission.Rejected, registry.connect(macDialled, macZ, local = false))
        assertSame(phoneDialled, registry.sessionFor(macZ))
        assertTrue(registry.isPending(macDialled))
        assertNull(registry.remove(macDialled))

        val reversed = SessionRegistry<FakeSession>(phoneId)
        val first = FakeSession("mac dialled")
        val second = FakeSession("phone dialled")
        reversed.connect(first, macZ, local = false)
        assertEquals(Admission.Accepted(first), reversed.connect(second, macZ, local = true))
    }

    @Test fun duplicateFromTheSameSideKeepsTheNewerSession() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        val older = FakeSession("older")
        val newer = FakeSession("newer")
        registry.connect(older, macA, local = false)
        assertEquals(Admission.Accepted(older), registry.connect(newer, macA, local = false))
        assertSame(newer, registry.sessionFor(macA))
    }

    @Test fun samePairSeenFromBothEndsKeepsTheSameSocket() {
        // Socket A is dialled by the Mac ("a-mac" < "m-phone"), socket B by the phone.
        val a = "A"
        val b = "B"
        val phone = SessionRegistry<String>(phoneId)
        phone.addPending(a, initiatedLocally = false)
        phone.authenticate(a, macA, "Mac")
        phone.addPending(b, initiatedLocally = true)
        phone.authenticate(b, macA, "Mac")
        val mac = SessionRegistry<String>(macA)
        mac.addPending(b, initiatedLocally = false)
        mac.authenticate(b, phoneId, "Phone")
        mac.addPending(a, initiatedLocally = true)
        mac.authenticate(a, phoneId, "Phone")
        assertEquals("A", phone.sessionFor(macA))
        assertEquals("A", mac.sessionFor(phoneId))
    }

    @Test fun replacedSessionKeepsTheDeviceState() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        registry.connect(FakeSession("old"), macA, local = false)
        registry.update(macA) { it.copy(battery = RemoteBatteryStatus(55, false)) }
        registry.connect(FakeSession("new"), macA, local = false)
        assertEquals(RemoteBatteryStatus(55, false), registry.link(macA)?.battery)
    }

    @Test fun perDeviceStateIsIsolated() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        registry.connect(FakeSession("a"), macA, local = false)
        registry.connect(FakeSession("z"), macZ, local = true)
        registry.update(macA) {
            it.copy(
                battery = RemoteBatteryStatus(80, true),
                ringing = true,
                remoteFeatures = it.remoteFeatures + (BridgeyFeature.CLIPBOARD to false),
            )
        }
        assertEquals(RemoteBatteryStatus(80, true), registry.link(macA)?.battery)
        assertNull(registry.link(macZ)?.battery)
        assertEquals(false, registry.link(macZ)?.ringing)
        assertEquals(PeerLinkState().remoteFeatures, registry.link(macZ)?.remoteFeatures)
        assertFalse(registry.update("unknown") { it })
    }

    @Test fun broadcastTargetsOnlyDevicesWithTheFeature() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        val a = FakeSession("a")
        registry.connect(a, macA, local = false)
        registry.connect(FakeSession("z"), macZ, local = true)
        registry.update(macZ) { it.copy(remoteFeatures = it.remoteFeatures + (BridgeyFeature.BATTERY to false)) }
        registry.addPending(FakeSession("pairing"), initiatedLocally = false)
        val targets = registry.broadcastTargets { _, link -> link.remoteFeatures[BridgeyFeature.BATTERY] != false }
        assertEquals(listOf(macA), targets.map { it.first })
        assertSame(a, targets.single().second)
        assertEquals(2, registry.broadcastTargets { _, _ -> true }.size)
    }

    @Test fun targetIsSelectionWhileConnectedElseLatest() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        assertNull(registry.target(null))
        registry.connect(FakeSession("a"), macA, local = false)
        val z = FakeSession("z")
        registry.connect(z, macZ, local = true)
        assertEquals(macZ, registry.target(null))
        assertEquals(macA, registry.target(macA))
        assertEquals(macZ, registry.target("gone"))
        assertEquals(macZ, registry.remove(z))
        assertEquals(macA, registry.target(null))
    }

    @Test fun removeAllReturnsPendingThenActive() {
        val registry = SessionRegistry<FakeSession>(phoneId)
        val active = FakeSession("active")
        val waiting = FakeSession("waiting")
        registry.connect(active, macA, local = false)
        registry.addPending(waiting, initiatedLocally = true)
        assertEquals(listOf(waiting, active), registry.removeAll())
        assertTrue(registry.connectedIds.isEmpty())
        assertFalse(registry.contains(waiting))
    }
}
