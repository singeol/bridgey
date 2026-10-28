package dev.bridgey.android

import android.content.Context
import android.os.PowerManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

// Connection health for Settings and the diagnostics report. Private by design: the route is a
// class, never an address; there are no device names or identifiers; disconnect reasons are fixed
// tokens. The round trip uses the existing heartbeat ping and pong, so nothing new goes on the wire.

internal enum class RouteClass(val token: String) {
    /** Private LAN, link-local or loopback addresses. */
    LOCAL("local_network"),

    /** 100.64.0.0/10, used by carrier-grade NAT and some VPNs. */
    SHARED("shared_address_space"),
    OTHER("other"),
    UNKNOWN("unknown"),
}

/** Classifies a numeric host without resolving names. Hostnames and anything malformed are UNKNOWN. */
internal fun classifyRoute(rawHost: String?): RouteClass {
    val host = rawHost?.trim()?.removePrefix("/")?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.lowercase()
    if (host.isNullOrEmpty()) return RouteClass.UNKNOWN
    ipv4(host)?.let { return classifyIpv4(it) }
    if (!host.contains(':') || host.any { !(it.isDigit() || it in 'a'..'f' || it == ':' || it == '.') }) return RouteClass.UNKNOWN
    if (host.startsWith("::ffff:")) ipv4(host.removePrefix("::ffff:"))?.let { return classifyIpv4(it) }
    if (host == "::1") return RouteClass.LOCAL
    val first = host.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return RouteClass.UNKNOWN
    return when {
        (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00 -> RouteClass.LOCAL
        else -> RouteClass.OTHER
    }
}

private fun ipv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return null
    return parts.map(String::toInt).takeIf { bytes -> bytes.all { it <= 255 } }?.toIntArray()
}

private fun classifyIpv4(b: IntArray): RouteClass = when {
    b[0] == 10 || b[0] == 127 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
        (b[0] == 169 && b[1] == 254) -> RouteClass.LOCAL
    b[0] == 100 && b[1] in 64..127 -> RouteClass.SHARED
    else -> RouteClass.OTHER
}

/** Why the last connection ended. */
internal enum class DisconnectReason(val token: String) {
    HEARTBEAT("heartbeat_timeout"),
    CONNECTION_LOST("connection_lost"),
    USER("user"),
    PROTOCOL("protocol_error"),
}

internal data class ConnectionHealthSnapshot(
    val connected: Boolean = false,
    val route: RouteClass = RouteClass.UNKNOWN,
    /** True when this device opened the connection; null while disconnected. */
    val dialledHere: Boolean? = null,
    val connectedSinceMillis: Long? = null,
    val lastRttMillis: Long? = null,
    val medianRttMillis: Long? = null,
    val rttSamples: Int = 0,
    val lastDisconnect: DisconnectReason? = null,
    val lastDisconnectAtMillis: Long? = null,
    val attemptsToday: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("connected", connected)
        put("route", route.token)
        dialledHere?.let { put("openedBy", if (it) "this_device" else "peer") }
        connectedSinceMillis?.let { put("connectedSince", Instant.ofEpochMilli(it).toString()) }
        lastRttMillis?.let { put("lastRoundTripMs", it) }
        medianRttMillis?.let { put("medianRoundTripMs", it) }
        put("roundTripSamples", rttSamples)
        lastDisconnect?.let { put("lastDisconnectReason", it.token) }
        lastDisconnectAtMillis?.let { put("lastDisconnectAt", Instant.ofEpochMilli(it).toString()) }
        put("connectionAttemptsToday", attemptsToday)
    }
}

/** The lower median of [samples], or null. */
internal fun medianOf(samples: Collection<Long>): Long? = samples.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }

/** Health facts for the current or last connection, in memory only. Thread safe. */
internal class ConnectionHealth(private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val pings = LinkedHashMap<String, Long>()
    private val rtts = ArrayDeque<Long>()
    private var attemptsDay: LocalDate? = null
    private var attempts = 0
    private val mutableSnapshot = MutableStateFlow(ConnectionHealthSnapshot())
    val snapshot: StateFlow<ConnectionHealthSnapshot> = mutableSnapshot.asStateFlow()

    /** A connection attempt started, by either device. */
    @Synchronized fun attempt(wallMillis: Long) {
        val today = Instant.ofEpochMilli(wallMillis).atZone(zone()).toLocalDate()
        if (attemptsDay != today) { attemptsDay = today; attempts = 0 }
        attempts += 1
        publish { it.copy(attemptsToday = attempts) }
    }

    /** The session is authenticated. [host] only selects the route class and is not kept. */
    @Synchronized fun connected(host: String?, dialledHere: Boolean, wallMillis: Long) {
        pings.clear()
        rtts.clear()
        publish {
            it.copy(connected = true, route = classifyRoute(host), dialledHere = dialledHere, connectedSinceMillis = wallMillis,
                lastRttMillis = null, medianRttMillis = null, rttSamples = 0)
        }
    }

    @Synchronized fun disconnected(reason: DisconnectReason, wallMillis: Long) {
        if (!mutableSnapshot.value.connected) return
        pings.clear()
        publish {
            it.copy(connected = false, route = RouteClass.UNKNOWN, dialledHere = null, connectedSinceMillis = null,
                lastDisconnect = reason, lastDisconnectAtMillis = wallMillis)
        }
    }

    @Synchronized fun heartbeatSent(messageId: String, elapsedMillis: Long) {
        pings[messageId] = elapsedMillis
        while (pings.size > MAX_PENDING_PINGS) pings.remove(pings.keys.first())
    }

    /** A pong echoing [messageId]; one this device did not send is ignored. */
    @Synchronized fun heartbeatAnswered(messageId: String?, elapsedMillis: Long) {
        val sentAt = messageId?.let(pings::remove) ?: return
        rtts.addLast((elapsedMillis - sentAt).coerceAtLeast(0))
        while (rtts.size > RTT_SAMPLES) rtts.removeFirst()
        publish { it.copy(lastRttMillis = rtts.last(), medianRttMillis = medianOf(rtts), rttSamples = rtts.size) }
    }

    /** The snapshot with today's attempt count, which resets at local midnight. */
    @Synchronized fun current(wallMillis: Long): ConnectionHealthSnapshot {
        val today = Instant.ofEpochMilli(wallMillis).atZone(zone()).toLocalDate()
        return mutableSnapshot.value.copy(attemptsToday = if (attemptsDay == today) attempts else 0)
    }

    private fun publish(change: (ConnectionHealthSnapshot) -> ConnectionHealthSnapshot) {
        mutableSnapshot.value = change(mutableSnapshot.value)
    }

    companion object {
        const val RTT_SAMPLES = 10
        const val MAX_PENDING_PINGS = 8
    }
}

/** Android may stop background connections for apps that are not exempt from battery optimization. */
internal fun batteryOptimizationIgnored(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
