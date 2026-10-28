package dev.bridgey.android

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHealthTest {
    @Test fun routesAreClassesNeverAddresses() {
        listOf("192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.4", "127.0.0.1", "/192.168.1.20", "fe80::1%wlan0", "fd12::4", "::1",
            "::ffff:192.168.0.2").forEach { assertEquals(it, RouteClass.LOCAL, classifyRoute(it)) }
        assertEquals(RouteClass.SHARED, classifyRoute("100.101.102.103"))
        assertEquals(RouteClass.OTHER, classifyRoute("8.8.8.8"))
        assertEquals(RouteClass.OTHER, classifyRoute("2001:db8::1"))
        listOf(null, "", "mac.local", "999.1.1.1", "1.2.3", "fe80::zz").forEach { assertEquals(it.toString(), RouteClass.UNKNOWN, classifyRoute(it)) }
    }

    @Test fun roundTripsComeOnlyFromOwnPings() {
        val health = ConnectionHealth { ZoneOffset.UTC }
        health.connected("192.168.1.2", dialledHere = true, wallMillis = 1_000)
        health.heartbeatSent("a", 100)
        health.heartbeatAnswered("unknown", 150)
        assertEquals(0, health.snapshot.value.rttSamples)
        health.heartbeatAnswered("a", 130)
        health.heartbeatAnswered("a", 400)
        listOf("b" to 50L, "c" to 10L).forEach { (id, rtt) -> health.heartbeatSent(id, 1000); health.heartbeatAnswered(id, 1000 + rtt) }
        val snapshot = health.snapshot.value
        assertEquals(3, snapshot.rttSamples)
        assertEquals(10L, snapshot.lastRttMillis)
        assertEquals(30L, snapshot.medianRttMillis)
        assertEquals(RouteClass.LOCAL, snapshot.route)
        assertEquals(true, snapshot.dialledHere)
    }

    @Test fun disconnectsKeepOnlyAReasonAndAttemptsResetDaily() {
        val day = 86_400_000L
        val health = ConnectionHealth { ZoneOffset.UTC }
        health.disconnected(DisconnectReason.USER, 10)
        assertNull(health.snapshot.value.lastDisconnect)
        health.attempt(day + 1)
        health.attempt(day + 2)
        health.connected("8.8.8.8", dialledHere = false, wallMillis = day + 3)
        health.disconnected(DisconnectReason.HEARTBEAT, day + 4)
        val snapshot = health.current(day + 5)
        assertFalse(snapshot.connected)
        assertEquals(RouteClass.UNKNOWN, snapshot.route)
        assertNull(snapshot.dialledHere)
        assertEquals(DisconnectReason.HEARTBEAT, snapshot.lastDisconnect)
        assertEquals(2, snapshot.attemptsToday)
        assertEquals(0, health.current(2 * day + 1).attemptsToday)
        assertTrue(DisconnectReason.entries.all { it.token.matches(Regex("[a-z_]+")) })
    }
}
