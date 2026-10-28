package dev.bridgey.android

/** The feature state assumed for a peer until its first `features.update`. */
internal fun defaultRemoteFeatureState(): Map<BridgeyFeature, Boolean> =
    BridgeyFeature.entries.associateWith(::featureEnabledByLegacyPeer)

/**
 * What this phone knows about one connected device. Every device has its own copy,
 * so one device's features, battery or ringing state never leak into another's.
 */
data class PeerLinkState(
    val remoteFeatures: Map<BridgeyFeature, Boolean> = defaultRemoteFeatureState(),
    val featureStateReceived: Boolean = false,
    val battery: RemoteBatteryStatus? = null,
    /** The device reported that it is ringing (Find device). */
    val ringing: Boolean = false,
)

/** An authenticated device as the UI sees it. */
data class ConnectedDevice(val id: String, val name: String, val link: PeerLinkState)

sealed interface Admission<out S> {
    /** The session is now the device's session; [replaced], if any, must be closed. */
    data class Accepted<S>(val replaced: S?) : Admission<S>

    /** Another live session for the device wins; this one must be closed. */
    data object Rejected : Admission<Nothing>
}

/**
 * Sessions keyed by device: pending sessions (handshake or pairing code) on their own,
 * and at most one authenticated session per device ID. See "Multiple devices" in
 * docs/architecture.md for the duplicate rule, which both platforms share.
 * Every method is synchronized because each session runs on its own thread.
 */
class SessionRegistry<S : Any>(private val localDeviceId: String) {
    private class Entry<S>(val session: S, val initiatedLocally: Boolean) {
        var deviceId: String? = null
        var peerName = ""
        var link = PeerLinkState()
    }

    private val pending = ArrayList<Entry<S>>()
    /** Authenticated sessions in connection order. */
    private val active = ArrayList<Entry<S>>()

    @Synchronized fun addPending(session: S, initiatedLocally: Boolean) {
        if (contains(session)) return
        pending += Entry(session, initiatedLocally)
    }

    @get:Synchronized val pendingSessions: List<S> get() = pending.map { it.session }

    @Synchronized fun isPending(session: S): Boolean = pending.any { it.session === session }

    @Synchronized fun isActive(session: S): Boolean = active.any { it.session === session }

    @Synchronized fun contains(session: S): Boolean = isPending(session) || isActive(session)

    /** Moves a pending session to the authenticated sessions under [deviceId]. */
    @Synchronized fun authenticate(session: S, deviceId: String, peerName: String): Admission<S> {
        val index = pending.indexOfFirst { it.session === session }
        if (index < 0) return Admission.Rejected
        val entry = pending[index]
        entry.deviceId = deviceId
        entry.peerName = peerName
        val existingIndex = active.indexOfFirst { it.deviceId == deviceId }
        if (existingIndex < 0) {
            pending.removeAt(index)
            active += entry
            return Admission.Accepted(null)
        }
        val existing = active[existingIndex]
        if (!prefers(entry, existing)) {
            entry.deviceId = null
            return Admission.Rejected
        }
        pending.removeAt(index)
        entry.link = existing.link
        active.removeAt(existingIndex)
        active += entry
        return Admission.Accepted(existing.session)
    }

    /**
     * Keeps the session dialled by the side with the lower device ID; if both were
     * dialled by the same side, the newer one wins.
     */
    private fun prefers(candidate: Entry<S>, existing: Entry<S>): Boolean {
        val candidateLower = dialledByLowerId(candidate)
        val existingLower = dialledByLowerId(existing)
        return if (candidateLower != existingLower) candidateLower else true
    }

    private fun dialledByLowerId(entry: Entry<S>): Boolean {
        val remote = entry.deviceId ?: return false
        return if (entry.initiatedLocally) localDeviceId < remote else remote < localDeviceId
    }

    /** Forgets the session. Returns the device ID when it was that device's authenticated session. */
    @Synchronized fun remove(session: S): String? {
        val pendingIndex = pending.indexOfFirst { it.session === session }
        if (pendingIndex >= 0) {
            pending.removeAt(pendingIndex)
            return null
        }
        val index = active.indexOfFirst { it.session === session }
        if (index < 0) return null
        return active.removeAt(index).deviceId
    }

    /** Forgets every session and returns them, pending ones first. */
    @Synchronized fun removeAll(): List<S> {
        val all = pending.map { it.session } + active.map { it.session }
        pending.clear()
        active.clear()
        return all
    }

    @Synchronized fun sessionFor(deviceId: String): S? = active.firstOrNull { it.deviceId == deviceId }?.session

    @Synchronized fun deviceIdOf(session: S): String? = active.firstOrNull { it.session === session }?.deviceId

    /** Connected device IDs in connection order. */
    @get:Synchronized val connectedIds: List<String> get() = active.mapNotNull { it.deviceId }

    @get:Synchronized val connections: List<ConnectedDevice>
        get() = active.mapNotNull { entry -> entry.deviceId?.let { ConnectedDevice(it, entry.peerName, entry.link) } }

    @Synchronized fun link(deviceId: String): PeerLinkState? = active.firstOrNull { it.deviceId == deviceId }?.link

    @Synchronized fun update(deviceId: String, transform: (PeerLinkState) -> PeerLinkState): Boolean {
        val entry = active.firstOrNull { it.deviceId == deviceId } ?: return false
        entry.link = transform(entry.link)
        return true
    }

    /**
     * The device user actions go to: [selected] while it is connected, otherwise the
     * most recently connected device.
     */
    @Synchronized fun target(selected: String?): String? {
        if (selected != null && active.any { it.deviceId == selected }) return selected
        return active.lastOrNull()?.deviceId
    }

    /** Connected devices for a broadcast, in connection order. */
    @Synchronized fun broadcastTargets(include: (String, PeerLinkState) -> Boolean): List<Pair<String, S>> =
        active.mapNotNull { entry ->
            val id = entry.deviceId ?: return@mapNotNull null
            if (include(id, entry.link)) id to entry.session else null
        }
}
