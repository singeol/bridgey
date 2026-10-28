package dev.bridgey.android

import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How remote input reaches a Mac: the pairing coordinator in the app, a fake in tests. */
interface RemoteInputTransport {
    /** Encrypts and sends one input message. Blocking; called on the outbox thread only. */
    fun send(deviceId: String, kind: String, payload: ByteArray): Boolean

    /** Remote control is on here and the Mac advertises it for this phone. */
    fun isAvailable(deviceId: String): Boolean
}

sealed interface RemoteSessionState {
    data object Idle : RemoteSessionState
    data class Starting(val deviceId: String, val mode: RemoteMode) : RemoteSessionState
    data class Active(val deviceId: String, val mode: RemoteMode, val inputSessionId: String) : RemoteSessionState
    data class Ended(val deviceId: String, val reason: RemoteStopReason) : RemoteSessionState
}

/**
 * The phone side of remote input: one session at a time, to one Mac. Every message gets the next
 * sequence number and goes through one ordered outbox, so the Mac sees them in order; motion is
 * batched by the screen to 60 a second and every class is rate limited as the Mac does. A heartbeat
 * every two seconds keeps the session while the screen is open.
 */
class RemoteInputController(
    private val scope: CoroutineScope,
    private val transport: RemoteInputTransport,
    private val clock: () -> Long,
    outboxDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Timers in the app; tests drive [tick] themselves. */
    private val runsTimers: Boolean = true,
) {
    private val mutableState = MutableStateFlow<RemoteSessionState>(RemoteSessionState.Idle)
    val state: StateFlow<RemoteSessionState> = mutableState.asStateFlow()

    private class Outgoing(val deviceId: String, val kind: String, val payload: ByteArray)

    private val outbox = Channel<Outgoing>(capacity = OUTBOX_CAPACITY)
    private val lock = Any()
    private var inputSessionId: String? = null
    private var sequence = 0L
    private var hostSequence = InputSequence()
    private var limiter = RemoteRateLimiter()
    private var startedAt = 0L
    private var lastSentAt = 0L
    private var heartbeatMillis = RemoteInputLimits.HEARTBEAT_MILLIS
    private var timer: Job? = null

    /** Input dropped here: over the rate limit, invalid, or the outbox was full. */
    var dropped = 0
        private set

    init {
        scope.launch(outboxDispatcher) {
            for (item in outbox) transport.send(item.deviceId, item.kind, item.payload)
        }
    }

    /** Starts a session with [deviceId], ending any other first. */
    fun start(deviceId: String, mode: RemoteMode) {
        synchronized(lock) {
            endLocked(RemoteStopReason.USER, notify = true, show = false)
            if (!transport.isAvailable(deviceId)) {
                mutableState.value = RemoteSessionState.Ended(deviceId, RemoteStopReason.DISABLED)
                return
            }
            val id = UUID.randomUUID().toString()
            inputSessionId = id
            sequence = 0
            hostSequence = InputSequence()
            limiter = RemoteRateLimiter()
            startedAt = clock()
            mutableState.value = RemoteSessionState.Starting(deviceId, mode)
            enqueueLocked(deviceId, RemoteInput.Start(mode))
        }
        if (runsTimers) startTimer()
    }

    /** Sends [input] in the running session. False when it was not sent (no session, limit, invalid). */
    fun send(input: RemoteInput): Boolean = synchronized(lock) {
        val active = mutableState.value as? RemoteSessionState.Active ?: return false
        if (input is RemoteInput.Start || input is RemoteInput.Stop || !input.isValid()) {
            dropped += 1
            return false
        }
        if (!limiter.allow(input.rateClass, clock())) {
            dropped += 1
            return false
        }
        enqueueLocked(active.deviceId, input)
    }

    /** The person stopped, left the screen or the screen turned off. */
    fun stop(reason: RemoteStopReason = RemoteStopReason.USER) {
        synchronized(lock) { endLocked(reason, notify = true, show = true) }
    }

    /** Forgets an ended session, for example when its screen closes. */
    fun clear() {
        synchronized(lock) {
            endLocked(RemoteStopReason.USER, notify = true, show = false)
            mutableState.value = RemoteSessionState.Idle
        }
    }

    /** A decrypted `input.*` message from a connected device. */
    fun onHostMessage(deviceId: String, kind: String, data: ByteArray) {
        val message = RemoteInputCodec.decodeHostMessage(kind, data) ?: return
        if (message is HostMessage.StartFromPeer) {
            // This phone cannot be controlled.
            outbox.trySend(Outgoing(deviceId, "input.session.stop", replyUnsupported(message.inputSessionId)))
            return
        }
        synchronized(lock) {
            val current = mutableState.value
            val deviceOfSession = when (current) {
                is RemoteSessionState.Starting -> current.deviceId
                is RemoteSessionState.Active -> current.deviceId
                else -> return
            }
            if (deviceOfSession != deviceId || message.inputSessionId != inputSessionId) return
            if (!hostSequence.accept(message.sequence)) return
            when (message) {
                is HostMessage.Started -> if (current is RemoteSessionState.Starting) {
                    heartbeatMillis = message.heartbeatMillis
                    mutableState.value = RemoteSessionState.Active(deviceId, current.mode, inputSessionId!!)
                }
                is HostMessage.Stopped -> endLocked(message.reason, notify = false, show = true)
                is HostMessage.StartFromPeer -> Unit
            }
        }
    }

    /** Connected devices changed: a session whose Mac went away, or stopped allowing it, ends. */
    fun connectionsChanged(connectedIds: Set<String>) {
        synchronized(lock) {
            val deviceId = sessionDeviceLocked() ?: return
            when {
                deviceId !in connectedIds -> endLocked(RemoteStopReason.DISCONNECTED, notify = false, show = true)
                !transport.isAvailable(deviceId) -> endLocked(RemoteStopReason.DISABLED, notify = true, show = true)
            }
        }
    }

    /** Heartbeats and the start timeout; runs every half second during a session. */
    fun tick() {
        synchronized(lock) {
            val now = clock()
            when (val current = mutableState.value) {
                is RemoteSessionState.Starting -> if (now - startedAt >= RemoteInputLimits.START_TIMEOUT_MILLIS) {
                    endLocked(RemoteStopReason.NO_ANSWER, notify = true, show = true)
                }
                is RemoteSessionState.Active -> if (now - lastSentAt >= heartbeatMillis) {
                    enqueueLocked(current.deviceId, RemoteInput.Heartbeat)
                }
                else -> Unit
            }
        }
    }

    private fun startTimer() {
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                val running = mutableState.value.let { it is RemoteSessionState.Starting || it is RemoteSessionState.Active }
                if (!running) break
                tick()
            }
        }
    }

    private fun sessionDeviceLocked(): String? = when (val current = mutableState.value) {
        is RemoteSessionState.Starting -> current.deviceId
        is RemoteSessionState.Active -> current.deviceId
        else -> null
    }

    private fun enqueueLocked(deviceId: String, input: RemoteInput): Boolean {
        val id = inputSessionId ?: return false
        sequence += 1
        val queued = outbox.trySend(Outgoing(deviceId, input.kind, RemoteInputCodec.encode(input, id, sequence))).isSuccess
        if (queued) lastSentAt = clock() else dropped += 1
        return queued
    }

    private fun endLocked(reason: RemoteStopReason, notify: Boolean, show: Boolean) {
        val deviceId = sessionDeviceLocked() ?: return
        if (notify) {
            val wire = if (reason in SENDABLE_REASONS) reason else RemoteStopReason.USER
            enqueueLocked(deviceId, RemoteInput.Stop(wire))
        }
        inputSessionId = null
        timer?.cancel()
        timer = null
        mutableState.value = if (show) RemoteSessionState.Ended(deviceId, reason) else RemoteSessionState.Idle
    }

    private fun replyUnsupported(inputSessionId: String): ByteArray =
        org.json.JSONObject()
            .put("version", 1).put("inputSessionId", inputSessionId).put("sequence", 1)
            .put("reason", RemoteStopReason.UNSUPPORTED.wire)
            .toString().toByteArray(Charsets.UTF_8)

    private companion object {
        const val OUTBOX_CAPACITY = 256
        const val TICK_MILLIS = 500L
        val SENDABLE_REASONS = setOf(RemoteStopReason.USER, RemoteStopReason.LEFT_SCREEN, RemoteStopReason.SCREEN_OFF)
    }
}
