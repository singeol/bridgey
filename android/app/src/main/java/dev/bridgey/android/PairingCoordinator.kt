package dev.bridgey.android

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.os.StatFs
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedWriter
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import org.json.JSONArray

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val peerName: String) : PairingState
    data class Verification(val peerName: String, val code: String) : PairingState
    data class Connected(val deviceId: String, val peerName: String) : PairingState
    data class Failed(val message: String) : PairingState
}

enum class ClipboardSendResult {
    DELIVERED,
    EMPTY,
    DISABLED,
    NOT_CONNECTED,
    CONNECTION_LOST,
    NO_ACKNOWLEDGEMENT,
    TOO_LARGE,
}

data class FileTransferState(
    val id: String,
    val name: String,
    val status: String,
    val active: Boolean,
    val progressPercent: Int?,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val retryable: Boolean = false,
    val queued: Boolean = false,
)

data class TrustedDevice(val id: String, val name: String)

data class RemoteBatteryStatus(val level: Int, val isCharging: Boolean)

class PairingCoordinator(
    context: Context,
    private val localDeviceId: String,
    localDeviceName: String,
    private val port: Int = 42_458,
    private val settings: BridgeySettings,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val quickActions = QuickActions(appContext, scope, ::isFeatureAvailable, ::sendQuickPayload)
    private val mutableState = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = mutableState.asStateFlow()
    private val identity = AndroidIdentity(context.applicationContext)
    private val trust = AndroidTrustRegistry(context.applicationContext)
    val trustedDeviceIds: StateFlow<Set<String>> = trust.trustedDeviceIds
    val trustedDevices: StateFlow<List<TrustedDevice>> = trust.trustedDevices
    @Volatile private var localDeviceName = localDeviceName
    private val mutableClipboardStatus = MutableStateFlow<String?>(null)
    val clipboardStatus: StateFlow<String?> = mutableClipboardStatus.asStateFlow()
    private val pendingClipboardSends = ConcurrentHashMap<String, (ClipboardSendResult) -> Unit>()
    private val pendingFileAccepts = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingFileCompletions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutableFileTransferStatus = MutableStateFlow<String?>(null)
    val fileTransferStatus: StateFlow<String?> = mutableFileTransferStatus.asStateFlow()
    private val mutableFileTransferActive = MutableStateFlow(false)
    val fileTransferActive: StateFlow<Boolean> = mutableFileTransferActive.asStateFlow()
    private val mutableFileTransfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    val fileTransfers: StateFlow<Map<String, FileTransferState>> = mutableFileTransfers.asStateFlow()
    private val mutablePhoneRinging = MutableStateFlow(false)
    val phoneRinging: StateFlow<Boolean> = mutablePhoneRinging.asStateFlow()
    private val mutableMacRinging = MutableStateFlow(false)
    val macRinging: StateFlow<Boolean> = mutableMacRinging.asStateFlow()
    private val mutableRemoteBattery = MutableStateFlow<RemoteBatteryStatus?>(null)
    val remoteBattery: StateFlow<RemoteBatteryStatus?> = mutableRemoteBattery.asStateFlow()
    private val mutablePingStatus = MutableStateFlow<String?>(null)
    val pingStatus: StateFlow<String?> = mutablePingStatus.asStateFlow()
    private var pendingPingId: String? = null
    private val mutableRemoteFeatures = MutableStateFlow(defaultRemoteFeatureState())
    val remoteFeatures: StateFlow<Map<BridgeyFeature, Boolean>> = mutableRemoteFeatures.asStateFlow()
    private val incomingFiles = ConcurrentHashMap<String, IncomingFileTransfer>()
    private val cancelledTransferIds = ConcurrentHashMap.newKeySet<String>()
    private val outgoingFileJobs = ConcurrentHashMap<String, Job>()
    private val outgoingFileSources = ConcurrentHashMap<String, Uri>()
    private val fileSendQueue = FileSendQueue<Uri>()
    @Volatile private var folderAssembly: FolderAssembly? = null
    @Volatile private var incomingFolder: IncomingFolder? = null
    private val folderFileOwners = ConcurrentHashMap<String, String>()
    private val endedFolderIds = ConcurrentHashMap.newKeySet<String>()
    private val sharedFolderStorage = SharedFolderStorage(appContext.contentResolver)
    private val browseBudget = BrowseRequestBudget()
    val deviceId: String get() = localDeviceId
    private var server: ServerSocket? = null
    /** Pending sessions (handshake or pairing code) and one authenticated session per device. */
    private val registry = SessionRegistry<Session>(localDeviceId)
    /** The session whose pairing code is shown, or the connection the user started. */
    @Volatile private var pairingSession: Session? = null
    /** The pairing in progress only: connecting, verification or failed, else idle. */
    @Volatile private var pairingFlow: PairingState = PairingState.Idle
    @Volatile private var publishedTarget: String? = null
    @Volatile private var localBattery: Pair<Int, Boolean>? = null
    private val mutableConnections = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    /** Every connected device with its own features, battery and ringing state, in connection order. */
    val connections: StateFlow<List<ConnectedDevice>> = mutableConnections.asStateFlow()
    private val mutableSelectedDeviceId = MutableStateFlow<String?>(null)
    /** The device the user picked for actions; null means the most recently connected one. */
    val selectedDeviceId: StateFlow<String?> = mutableSelectedDeviceId.asStateFlow()
    /** The device user actions go to. */
    private val targetId: String? get() = registry.target(mutableSelectedDeviceId.value)
    /** The target device's session, which user actions use. */
    private val session: Session? get() = targetId?.let(registry::sessionFor)
    /** Trusted devices this phone is dialling by itself, so each is dialled once. */
    private val dialing = ConcurrentHashMap.newKeySet<String>()
    private val held = HeldDevices()
    private val transferOwners = TransferOwners()
    /** Devices that made this phone ring; stopping tells them. */
    private val ringingRequesters = ConcurrentHashMap.newKeySet<String>()
    /** The last media state of each Mac; the media card shows the selected one. */
    private val mediaStates = ConcurrentHashMap<String, JSONObject>()
    /** While a quick message is handled, the device that sent it: policy and replies use it. */
    private val quickReplyTarget = ThreadLocal<Session?>()
    private var acceptJob: Job? = null
    private var findRingtone: Ringtone? = null
    private val diagnostics = BridgeyDiagnostics()
    private val remoteCallRequest = RemoteCallRequest(appContext)
    private var lastRemoteCallRequestAt = 0L

    init {
        scope.launch {
            settings.state.collect {
                if (!featureEnabled(BridgeyFeature.CLIPBOARD)) mutableClipboardStatus.value = null
                registry.connectedIds.filterNot { settings.isEnabled(BridgeyFeature.BATTERY, it) }.forEach { id ->
                    registry.update(id) { it.copy(battery = null) }
                }
                publish()
                if (!featureEnabled(BridgeyFeature.PING)) clearPingStatus()
                quickActions.policyChanged()
                sendFeatureState()
            }
        }
    }

    fun start() {
        if (acceptJob != null) return
        diagnostics.record("transport", "listener_started")
        acceptJob = scope.launch {
            runCatching {
                ServerSocket(port).also { server = it }.use { listener ->
                    while (!listener.isClosed) {
                        val socket = listener.accept()
                        // Each connection reads on its own coroutine, so a new one never waits for another to end.
                        scope.launch { handle(socket, initiatedLocally = false, peerHint = null) }
                    }
                }
            }.onFailure { if (server?.isClosed == false) failPairing("Pairing listener failed") }
        }
    }

    fun pair(host: String, port: Int, peerName: String) {
        diagnostics.record("pairing", "connection_started")
        android.util.Log.i("Bridgey", "PAIRING started peer=$peerName")
        pairingFlow = PairingState.Connecting(peerName)
        publish()
        scope.launch {
            runCatching { Socket(host, port) }
                .onSuccess { handle(it, initiatedLocally = true, peerHint = peerName) }
                .onFailure { failPairing("Could not connect to $peerName") }
        }
    }

    /** Dials a trusted device in the background. Unlike [pair], it never shows connection progress. */
    fun reconnect(host: String, port: Int, peerName: String, deviceId: String) {
        if (held.isHeld(deviceId) || registry.sessionFor(deviceId) != null || !dialing.add(deviceId)) return
        scope.launch {
            runCatching { Socket(host, port) }
                .onSuccess { handle(it, initiatedLocally = true, peerHint = peerName, reconnectId = deviceId) }
                .onFailure {
                    dialing.remove(deviceId)
                    android.util.Log.i("Bridgey", "TRANSPORT reconnect failed peerId=${deviceId.take(8)}")
                }
        }
    }

    /** Picks the device that user actions go to. */
    fun selectDevice(deviceId: String?) {
        mutableSelectedDeviceId.value = deviceId
        publish()
    }

    fun confirm() {
        scope.launch {
            val current = pairingSession ?: return@launch
            if (current.pairingKey == null || current.localConfirmed) return@launch
            confirm(current)
        }
    }

    /** Cancels the pairing in progress or clears its failure. Connected devices stay connected. */
    fun cancel() {
        val current = pairingSession
        pairingSession = null
        current?.send(Message(kind = "pairing.cancel", sessionId = current.id))
        current?.close()
        pairingFlow = PairingState.Idle
        publish()
    }

    /** Disconnects the selected device, or cancels the pairing when no device is connected. */
    fun dismiss() {
        val target = targetId
        if (target == null) cancel() else disconnect(target)
    }

    /**
     * The user disconnected [deviceId] on this phone: it stays disconnected, also when it
     * tries to reconnect, until the user connects to it again from Nearby.
     */
    fun disconnectByUser(deviceId: String) {
        held.hold(deviceId)
        disconnect(deviceId)
        android.util.Log.i("Bridgey", "TRANSPORT disconnected by user peerId=${deviceId.take(8)}")
    }

    /** Disconnects one device; other sessions stay. */
    fun disconnect(deviceId: String) {
        val current = registry.sessionFor(deviceId) ?: return
        registry.remove(current)
        current.close()
        deviceDisconnected(deviceId)
    }

    fun forget(deviceId: String) {
        trust.remove(deviceId)
        settings.removeDevice(deviceId)
        disconnect(deviceId)
        android.util.Log.i("Bridgey", "PAIRING revoked peerId=${deviceId.take(8)}")
    }

    /**
     * Recomputes the public single-device flows from the target device. A pairing code
     * waiting for confirmation is shown even while another device is connected.
     */
    @Synchronized private fun publish() {
        val target = targetId
        val connected = registry.connections
        mutableConnections.value = connected
        val device = target?.let { id -> connected.firstOrNull { it.id == id } }
        val flow = pairingFlow
        mutableState.value = when {
            flow is PairingState.Verification -> flow
            device != null -> PairingState.Connected(device.id, device.name)
            else -> flow
        }
        val link = device?.link ?: PeerLinkState()
        mutableRemoteFeatures.value = link.remoteFeatures
        mutableRemoteBattery.value = link.battery
        mutableMacRinging.value = link.ringing
        if (target != publishedTarget) {
            publishedTarget = target
            clearPingStatus()
            quickActions.targetChanged()
            target?.let(mediaStates::get)?.let { quickActions.receive("media.state", it) }
        }
    }

    /** One device's session ended: only its transfers, ringing and media state go. */
    private fun deviceDisconnected(deviceId: String) {
        interruptTransfers(deviceId)
        mediaStates.remove(deviceId)
        ringingRequesters.remove(deviceId)
        if (ringingRequesters.isEmpty()) stopPhoneRinging()
        if (registry.connectedIds.isEmpty()) mutableSelectedDeviceId.value = null
        publish()
        diagnostics.record("transport", "disconnected", "reconnecting")
        android.util.Log.i("Bridgey", "TRANSPORT disconnected peerId=${deviceId.take(8)}")
    }

    /** Shows [message] for the pairing the user sees; connected devices are not touched. */
    private fun failPairing(message: String) {
        pairingSession = null
        pairingFlow = PairingState.Failed(message)
        publish()
    }

    fun updateDeviceName(value: String) {
        localDeviceName = value.trim().take(64).ifBlank { "Android device" }
    }

    private fun featureEnabled(feature: BridgeyFeature, current: Session? = session): Boolean =
        settings.isEnabled(feature, current?.remoteDeviceId?.takeIf(String::isNotEmpty))

    /** Whether the device on [current] has [feature] on, from its last `features.update`. */
    private fun remoteEnabled(current: Session, feature: BridgeyFeature): Boolean =
        registry.link(current.remoteDeviceId)?.remoteFeatures?.get(feature) != false

    fun isFeatureAvailable(feature: BridgeyFeature): Boolean {
        quickReplyTarget.get()?.let { sender ->
            return isFeatureAvailable(feature, sender.remoteDeviceId, registry.link(sender.remoteDeviceId))
        }
        return effectiveFeatureAvailable(featureEnabled(feature), mutableRemoteFeatures.value[feature] != false)
    }

    /** [feature] is on here for [deviceId] and on that device itself. */
    private fun isFeatureAvailable(feature: BridgeyFeature, deviceId: String, link: PeerLinkState?): Boolean =
        effectiveFeatureAvailable(settings.isEnabled(feature, deviceId), link?.remoteFeatures?.get(feature) != false)

    /** Every connected device where [feature] is available, for a broadcast. */
    private fun sessionsWith(feature: BridgeyFeature): List<Session> =
        registry.broadcastTargets { id, link -> isFeatureAvailable(feature, id, link) }.map { it.second }

    fun sendClipboard() {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(appContext)?.toString()
        if (text.isNullOrEmpty()) {
            mutableClipboardStatus.value = "Clipboard unavailable. Copy text, return to Bridgey, and try again."
        } else {
            sendClipboardContent(text, item.htmlText)
        }
    }

    fun sendText(text: String, onResult: (ClipboardSendResult) -> Unit = {}) =
        sendClipboardContent(text, html = null, onResult = onResult)

    private fun sendClipboardContent(
        text: String,
        html: String?,
        onResult: (ClipboardSendResult) -> Unit = {},
    ) {
        if (!isFeatureAvailable(BridgeyFeature.CLIPBOARD)) {
            mutableClipboardStatus.value = "Clipboard is turned off on one of your devices"
            onResult(ClipboardSendResult.DISABLED)
            return
        }
        if (text.isEmpty()) {
            mutableClipboardStatus.value = "Clipboard is empty"
            onResult(ClipboardSendResult.EMPTY)
            return
        }
        if (!clipboardTextFits(text)) {
            mutableClipboardStatus.value = "Clipboard exceeds 32 KiB. Send large text or diagnostics as a file."
            onResult(ClipboardSendResult.TOO_LARGE)
            return
        }
        val connectedSession = session
        if (connectedSession == null) {
            mutableClipboardStatus.value = "Not connected — clipboard was not sent"
            onResult(ClipboardSendResult.NOT_CONNECTED)
            return
        }
        scope.launch {
            val current = connectedSession
            if (!registry.isActive(current)) {
                mutableClipboardStatus.value = "Not connected — clipboard was not sent"
                onResult(ClipboardSendResult.NOT_CONNECTED)
                return@launch
            }
            val messageId = UUID.randomUUID().toString()
            pendingClipboardSends[messageId] = onResult
            val richContent = RichClipboardContent.create(text, html)
            val plaintext = richContent?.encode() ?: text.toByteArray(Charsets.UTF_8)
            val encrypted = Crypto.encrypt(current.pairingKey!!, plaintext)
            val message = Message(
                kind = if (richContent == null) "clipboard.update" else "clipboard.rich",
                sessionId = current.id,
                messageId = messageId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            )
            if (!current.send(message)) {
                pendingClipboardSends.remove(messageId)?.invoke(ClipboardSendResult.CONNECTION_LOST)
                mutableClipboardStatus.value = "Connection lost"
                current.close()
                return@launch
            }
            mutableClipboardStatus.value = "Sending…"
            android.util.Log.i("Bridgey", "PLUGIN clipboard sent")
            delay(3_000)
            if (pendingClipboardSends.containsKey(messageId) && registry.isActive(current)) {
                if (!current.send(message)) {
                    pendingClipboardSends.remove(messageId)?.invoke(ClipboardSendResult.CONNECTION_LOST)
                    mutableClipboardStatus.value = "Connection lost"
                    current.close()
                    return@launch
                }
                delay(3_000)
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "No delivery acknowledgement"
                    callback(ClipboardSendResult.NO_ACKNOWLEDGEMENT)
                }
            }
        }
    }

    /** Sends this phone's battery to every device with Battery on and remembers it for devices that connect later. */
    fun sendBattery(level: Int, isCharging: Boolean) {
        localBattery = level to isCharging
        sessionsWith(BridgeyFeature.BATTERY).forEach { sendBattery(it, level, isCharging) }
    }

    private fun sendBattery(connectedSession: Session, level: Int, isCharging: Boolean) {
        scope.launch {
            if (!registry.isActive(connectedSession)) return@launch
            val payload = JSONObject()
                .put("level", level.coerceIn(0, 100))
                .put("isCharging", isCharging)
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, payload)
            if (connectedSession.send(
                    Message(
                        kind = "battery.update",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                android.util.Log.i("Bridgey", "PLUGIN battery sent level=$level charging=$isCharging")
            }
        }
    }

    fun sendPing() {
        val current = session
        if (current == null) {
            mutablePingStatus.value = "Mac is not connected"
            return
        }
        if (!isFeatureAvailable(BridgeyFeature.PING)) {
            mutablePingStatus.value = "Ping requires Bridgey 0.6 on both devices"
            return
        }
        val messageId = UUID.randomUUID().toString()
        val encrypted = Crypto.encrypt(current.pairingKey!!, JSONObject().put("version", 1).toString().toByteArray())
        pendingPingId = messageId
        mutablePingStatus.value = "Pinging Mac…"
        scope.launch {
            if (!registry.isActive(current) || !current.send(
                    Message(
                        kind = "ping.request",
                        sessionId = current.id,
                        messageId = messageId,
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                if (pendingPingId == messageId) {
                    pendingPingId = null
                    mutablePingStatus.value = "Ping could not be sent"
                }
                return@launch
            }
            delay(5_000)
            if (pendingPingId == messageId) {
                pendingPingId = null
                mutablePingStatus.value = "Mac did not acknowledge the ping"
            }
        }
    }

    private fun receivePing(current: Session, message: Message) {
        if (!featureEnabled(BridgeyFeature.PING, current)) {
            sendFeatureState()
            return
        }
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted ping"),
            message.ciphertext ?: return fail("Invalid encrypted ping"),
        ) ?: return fail("Invalid encrypted ping")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getInt("version") }.getOrNull() != 1) {
            return fail("Invalid ping")
        }
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appContext, "Ping from ${current.peerName}", android.widget.Toast.LENGTH_SHORT).show()
            RingtoneManager.getRingtone(appContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
        current.send(Message(kind = "ping.ack", sessionId = current.id, messageId = messageId))
        android.util.Log.i("Bridgey", "PLUGIN ping received")
    }

    private fun clearPingStatus() {
        pendingPingId = null
        mutablePingStatus.value = null
    }

    private fun sendQuickPayload(kind: String, payload: JSONObject): Boolean {
        val current = quickReplyTarget.get() ?: session ?: return false
        val key = current.pairingKey ?: return false
        val encrypted = Crypto.encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
        scope.launch {
            if (registry.isActive(current)) current.send(Message(kind = kind, sessionId = current.id,
                messageId = UUID.randomUUID().toString(), nonce = encrypted.nonce, ciphertext = encrypted.ciphertext))
        }
        return true
    }

    private fun receiveQuickPayload(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val key = current.pairingKey ?: return
        val id = message.messageId ?: return
        if (!current.acceptMessageId(id)) return
        val data = Crypto.decrypt(key, message.nonce ?: return, message.ciphertext ?: return) ?: return
        if (data.size > if (message.kind == "media.state") 32768 else 8192) return
        val payload = runCatching { JSONObject(data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (message.kind == "quick.request") {
            val raw = payload.opt("sequence")
            if (raw !is Int && raw !is Long) return
            val sequence = (raw as Number).toLong()
            if (!current.quickSequence.accept(payload.optString("feature"), sequence)) return
        }
        if (message.kind == "media.state") {
            mediaStates[current.remoteDeviceId] = payload
            if (targetId != current.remoteDeviceId) return
        }
        // Policy checks and the reply use the device that sent this, not the selected one.
        quickReplyTarget.set(current)
        try {
            quickActions.receive(message.kind, payload)
        } finally {
            quickReplyTarget.remove()
        }
    }

    fun sendNotification(
        packageName: String,
        applicationName: String,
        notificationId: String,
        title: String,
        text: String,
        timestamp: Long,
        actions: List<ForwardedNotificationAction> = emptyList(),
        applicationIcon: String? = null,
        callType: String? = null,
    ) {
        val targets = sessionsWith(BridgeyFeature.NOTIFICATIONS)
        if (targets.isEmpty()) return
        scope.launch {
            val payload = JSONObject()
                .put("packageName", packageName.take(256))
                .put("applicationName", applicationName.take(128))
                .put("notificationId", notificationId.take(512))
                .put("title", title.take(1_024))
                .put("text", text.take(8_192))
                .put("timestamp", timestamp)
                .apply {
                    if (applicationIcon != null && applicationIcon.length <= MAX_NOTIFICATION_ICON_BASE64_LENGTH) {
                        put("applicationIcon", applicationIcon)
                    }
                    if (callType in setOf("incoming", "ongoing", "screening", "unknown")) {
                        put("callType", callType)
                    }
                }
                .put("actions", JSONArray().apply {
                    actions.take(4).forEach { action ->
                        put(JSONObject()
                            .put("actionToken", action.token)
                            .put("title", action.title.take(64))
                            .put("allowsReply", action.allowsReply))
                    }
                })
                .toString()
                .toByteArray()
            targets.filter(registry::isActive).forEach { connectedSession ->
                val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, payload)
                if (connectedSession.send(
                        Message(
                            kind = "notifications.post",
                            sessionId = connectedSession.id,
                            messageId = UUID.randomUUID().toString(),
                            nonce = encrypted.nonce,
                            ciphertext = encrypted.ciphertext,
                        ),
                    )
                ) {
                    android.util.Log.i("Bridgey", "PLUGIN notification sent package=$packageName")
                }
            }
        }
    }

    fun sendNotificationRemoved(notificationId: String) {
        sendNotificationReference("notifications.remove", notificationId)
    }

    private fun sendNotificationReference(kind: String, notificationId: String) {
        if (notificationId.isBlank()) return
        val targets = sessionsWith(BridgeyFeature.NOTIFICATIONS)
        if (targets.isEmpty()) return
        scope.launch {
            val payload = JSONObject()
                .put("notificationId", notificationId.take(512))
                .toString()
                .toByteArray()
            targets.filter(registry::isActive).forEach { connectedSession ->
                val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, payload)
                connectedSession.send(
                    Message(
                        kind = kind,
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            }
        }
    }

    fun findMac() {
        if (!isFeatureAvailable(BridgeyFeature.FIND_DEVICE)) return
        scope.launch { sendFindCommand("find.start") }
    }

    /** Stops ringing here and on every device that is ringing or made this phone ring. */
    fun stopFinding() {
        stopPhoneRinging()
        val targets = findStopTargets(registry.connections, ringingRequesters.toSet(), targetId)
        ringingRequesters.clear()
        scope.launch { targets.mapNotNull(registry::sessionFor).forEach { sendFindCommand("find.stop", it) } }
    }

    private fun sendFindCommand(kind: String, current: Session? = session): Boolean {
        if (current == null) return false
        if (kind == "find.start" && !isFeatureAvailable(BridgeyFeature.FIND_DEVICE)) return false
        val payload = JSONObject().put("alertId", "active").toString().toByteArray()
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        return current.send(
            Message(
                kind = kind,
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    private fun receiveFindCommand(current: Session, message: Message, start: Boolean) {
        if (start && !settings.isEnabled(BridgeyFeature.FIND_DEVICE, current.remoteDeviceId)) return
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted find-device message"),
            message.ciphertext ?: return fail("Invalid encrypted find-device message"),
        ) ?: return fail("Invalid encrypted find-device message")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getString("alertId") }.isFailure) {
            return fail("Invalid find-device message")
        }
        if (start) {
            ringingRequesters += current.remoteDeviceId
            startPhoneRinging()
            sendFindCommand(if (mutablePhoneRinging.value) "find.started" else "find.stopped", current)
        } else {
            stopPhoneRinging()
            ringingRequesters.clear()
            registry.update(current.remoteDeviceId) { it.copy(ringing = false) }
            publish()
            sendFindCommand("find.stopped", current)
        }
        android.util.Log.i("Bridgey", "PLUGIN find-device ${if (start) "started" else "stopped"}")
    }

    private fun startPhoneRinging() {
        if (mutablePhoneRinging.value) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = true
        findRingtone = ringtone
        mutablePhoneRinging.value = true
        ringtone.play()
    }

    private fun stopPhoneRinging() {
        runCatching { findRingtone?.stop() }
        findRingtone = null
        mutablePhoneRinging.value = false
    }

    fun sendFile(uri: Uri): List<String> = sendFiles(listOf(uri))

    /**
     * Adds files to the outgoing queue. Files are offered one after another,
     * each as its own transfer, so a failure or cancellation affects only that file.
     */
    fun sendFiles(uris: List<Uri>): List<String> = sendFiles(uris, targetId)

    /** Queues files for [deviceId]; the queue stays sequential across devices. */
    private fun sendFiles(uris: List<Uri>, deviceId: String?): List<String> {
        if (uris.isEmpty()) return emptyList()
        if (deviceId != null && !isFeatureAvailable(BridgeyFeature.FILES, deviceId, registry.link(deviceId))) {
            mutableFileTransferStatus.value = "File transfer is turned off on one of your devices"
            return emptyList()
        }
        if (deviceId == null || registry.sessionFor(deviceId) == null) {
            mutableFileTransferStatus.value = "Not connected — file was not sent"
            return emptyList()
        }
        val queuedAt = System.currentTimeMillis()
        val status = appContext.getString(R.string.file_queued)
        val queued = uris.distinct().mapIndexed { index, uri ->
            val transferId = UUID.randomUUID().toString()
            outgoingFileSources[transferId] = uri
            transferOwners.assign(transferId, deviceId)
            FileTransferState(
                id = transferId,
                name = "Selected file",
                status = status,
                active = true,
                progressPercent = null,
                startedAtMillis = queuedAt + index,
                queued = true,
            ) to uri
        }
        mutableFileTransfers.value = mutableFileTransfers.value + queued.associate { (transfer, _) -> transfer.id to transfer }
        queued.forEach { (transfer, uri) -> fileSendQueue.enqueue(transfer.id, uri) }
        pruneTransferHistory()
        refreshFileTransferSummary(status)
        scope.launch {
            queued.forEach { (transfer, uri) ->
                val name = runCatching { displayName(uri) }.getOrNull() ?: return@forEach
                mutableFileTransfers.update { transfers ->
                    val current = transfers[transfer.id]?.takeIf(FileTransferState::queued) ?: return@update transfers
                    transfers + (transfer.id to current.copy(name = name))
                }
            }
        }
        startNextQueuedFile()
        return queued.map { (transfer, _) -> transfer.id }
    }

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)?.take(255)
        }

    private fun startNextQueuedFile() {
        val (transferId, uri) = fileSendQueue.startNext() ?: return
        startFile(transferId, uri)
    }

    private fun finishQueuedFile(transferId: String) {
        if (fileSendQueue.finish(transferId)) startNextQueuedFile()
    }

    /** Ends every file for [deviceId] (or every device) that has not started yet. They remain retryable. */
    private fun stopFileQueue(status: String, deviceId: String? = null) {
        fileSendQueue.pendingIds()
            .filter { deviceId == null || transferOwners.owns(it, deviceId) }
            .filter(fileSendQueue::removePending)
            .forEach { removeFileTransfer(it, status) }
    }

    private fun startFile(transferId: String, uri: Uri) {
        val deviceId = transferOwners.owner(transferId)
        val connectedSession = deviceId?.let(registry::sessionFor)
        if (deviceId == null || connectedSession == null) {
            removeFileTransfer(transferId, "Transfer interrupted — reconnect to retry")
            finishQueuedFile(transferId)
            return
        }
        if (!isFeatureAvailable(BridgeyFeature.FILES, deviceId, registry.link(deviceId))) {
            removeFileTransfer(transferId, "File transfer is turned off on one of your devices")
            finishQueuedFile(transferId)
            return
        }
        diagnostics.record("transfer", "send_started")
        updateFileTransfer(transferId, mutableFileTransfers.value[transferId]?.name ?: "Selected file", "Preparing…", true)
        val job = scope.launch {
            val resolver = appContext.contentResolver
            val metadata = runCatching {
                var name = "file"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                    }
                }
                Triple(name.take(255), resolver.getType(uri) ?: "application/octet-stream", size)
            }.getOrElse {
                updateFileTransfer(transferId, "Selected file", "Could not read the selected file", false)
                return@launch
            }
            if (metadata.third < 0) {
                mutableFileTransferStatus.value = "This file provider did not report a file size"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }
            if (metadata.third > MAX_FILE_SIZE) {
                mutableFileTransferStatus.value = "File is larger than 10 GB"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }

            updateFileTransfer(transferId, metadata.first, "Preparing ${metadata.first}…", true)
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                } ?: error("File unavailable")
                Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
            }.getOrElse {
                mutableFileTransferStatus.value = "Could not read the selected file"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }

            val offerPayload = JSONObject()
                .put("transferId", transferId)
                .put("name", metadata.first)
                .put("mimeType", metadata.second)
                .put("size", metadata.third)
                .put("sha256", hash)
                .toString().toByteArray()
            val offer = Crypto.encrypt(connectedSession.pairingKey!!, offerPayload)
            val accepted = CompletableDeferred<Boolean>()
            pendingFileAccepts[transferId] = accepted
            if (!connectedSession.send(Message(
                    kind = "files.offer",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    transferId = transferId,
                    nonce = offer.nonce,
                    ciphertext = offer.ciphertext,
                ))) {
                pendingFileAccepts.remove(transferId)
                mutableFileTransferStatus.value = "Connection lost — file was not sent"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }
            updateFileTransfer(transferId, metadata.first, "Waiting for Mac…", true)
            if (withTimeoutOrNull(10_000) { accepted.await() } != true) {
                pendingFileAccepts.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, metadata.first, "Mac did not accept the file", false)
                return@launch
            }

            val completed = CompletableDeferred<Boolean>()
            pendingFileCompletions[transferId] = completed
            val sent = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    var total = 0L
                    var sequence = 0L
                    val progress = TransferProgress(metadata.third)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, buffer.copyOf(count))
                        check(connectedSession.send(Message(
                            kind = "files.chunk",
                            sessionId = connectedSession.id,
                            messageId = UUID.randomUUID().toString(),
                            transferId = transferId,
                            sequence = sequence++,
                            nonce = encrypted.nonce,
                            ciphertext = encrypted.ciphertext,
                        )))
                        total += count
                        progress.status(total)?.let {
                            updateFileTransfer(transferId, metadata.first, "Sending ${metadata.first}: $it", true)
                        }
                    }
                    updateFileTransfer(transferId, metadata.first, "Verifying ${metadata.first} on Mac…", true)
                } ?: error("File unavailable")
                val completionPayload = Crypto.encrypt(
                    connectedSession.pairingKey!!,
                    JSONObject().put("transferId", transferId).put("sha256", hash).toString().toByteArray(),
                )
                check(connectedSession.send(Message(
                    kind = "files.complete",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = completionPayload.nonce,
                    ciphertext = completionPayload.ciphertext,
                )))
            }.isSuccess
            if (!sent) {
                pendingFileCompletions.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, metadata.first, "File transfer failed", false)
                return@launch
            }
            if (withTimeoutOrNull(15_000) { completed.await() } == true) {
                outgoingFileSources.remove(transferId)
                updateFileTransfer(transferId, metadata.first, "${metadata.first} saved on Mac", false)
                diagnostics.record("transfer", "send_completed")
            } else {
                pendingFileCompletions.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, metadata.first, "Mac did not confirm the saved file", false)
            }
        }
        outgoingFileJobs[transferId] = job
        job.invokeOnCompletion {
            outgoingFileJobs.remove(transferId, job)
            finishQueuedFile(transferId)
        }
    }

    fun cancelFileTransfer(transferId: String) {
        incomingFolder?.takeIf { it.id == transferId }?.let { folder ->
            val current = transferOwners.owner(folder.id)?.let(registry::sessionFor)
            scope.launch {
                if (current != null && registry.isActive(current)) {
                    current.send(Message(kind = "folders.cancel", sessionId = current.id, transferId = folder.id))
                    folder.currentTransferId?.let { current.send(Message(kind = "files.cancel", sessionId = current.id, transferId = it)) }
                }
            }
            endIncomingFolder(folder, "Transfer cancelled")
            diagnostics.record("transfer", "cancelled")
            return
        }
        if (fileSendQueue.removePending(transferId)) {
            removeFileTransfer(transferId, "Transfer cancelled")
            diagnostics.record("transfer", "cancelled")
            return
        }
        val current = transferOwners.owner(transferId)?.let(registry::sessionFor)
        markTransferCancelled(transferId)
        scope.launch {
            repeat(3) { attempt ->
                if (current != null && registry.isActive(current)) {
                    current.send(Message(kind = "files.cancel", sessionId = current.id, transferId = transferId))
                }
                if (attempt < 2) delay(250)
            }
        }
        outgoingFileJobs.remove(transferId)?.cancel()
        incomingFiles.remove(transferId)?.cancel()
        pendingFileAccepts.remove(transferId)?.complete(false)
        pendingFileCompletions.remove(transferId)?.complete(false)
        val name = mutableFileTransfers.value[transferId]?.name ?: "File"
        removeFileTransfer(transferId, "Transfer cancelled")
        diagnostics.record("transfer", "cancelled")
    }

    fun cancelFileTransfer() {
        stopFileQueue("Transfer cancelled")
        mutableFileTransfers.value.values.filter { it.active }.forEach { cancelFileTransfer(it.id) }
    }

    /** Sends a failed file again to the device it was meant for. */
    fun retryFileTransfer(transferId: String) {
        val uri = outgoingFileSources[transferId] ?: return
        val deviceId = transferOwners.owner(transferId)
        if (deviceId == null || registry.sessionFor(deviceId) == null) {
            removeFileTransfer(transferId, "Reconnect before retrying")
            return
        }
        if (!isFeatureAvailable(BridgeyFeature.FILES, deviceId, registry.link(deviceId))) {
            removeFileTransfer(transferId, "File transfer is turned off on one of your devices")
            return
        }
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply { remove(transferId) }
        outgoingFileSources.remove(transferId)
        diagnostics.record("transfer", "retry_started")
        sendFiles(listOf(uri), deviceId)
    }

    fun diagnosticsReport(): String {
        val stateName = when (mutableState.value) {
            PairingState.Idle -> "idle"
            is PairingState.Connecting -> "connecting"
            is PairingState.Verification -> "verification"
            is PairingState.Connected -> "connected"
            is PairingState.Failed -> "failed"
        }
        val localFeatures = BridgeyFeature.entries.associateWith { settings.isEnabled(it, null) }
        return diagnostics.report(appContext, stateName, mutableFileTransfers.value.values, localFeatures, mutableRemoteFeatures.value)
    }

    fun clearTransferHistory() {
        val inactiveIds = mutableFileTransfers.value.values.filterNot(FileTransferState::active).map(FileTransferState::id)
        inactiveIds.forEach(outgoingFileSources::remove)
        mutableFileTransfers.value = mutableFileTransfers.value.filterValues(FileTransferState::active)
        refreshFileTransferSummary()
    }

    fun stop() {
        pause()
        scope.cancel()
    }

    fun pause() {
        registry.removeAll().forEach(Session::close)
        pairingSession = null
        pairingFlow = PairingState.Idle
        dialing.clear()
        ringingRequesters.clear()
        mediaStates.clear()
        mutableSelectedDeviceId.value = null
        stopPhoneRinging()
        publish()
        server?.close()
        server = null
        acceptJob?.cancel()
        acceptJob = null
        pendingClipboardSends.values.forEach { it(ClipboardSendResult.NOT_CONNECTED) }
        pendingClipboardSends.clear()
        pendingFileAccepts.values.forEach { it.complete(false) }
        pendingFileAccepts.clear()
        pendingFileCompletions.values.forEach { it.complete(false) }
        pendingFileCompletions.clear()
        incomingFiles.values.forEach(IncomingFileTransfer::cancel)
        incomingFiles.clear()
        folderAssembly = null
        incomingFolder = null
        folderFileOwners.clear()
        interruptFileQueue()
        outgoingFileJobs.values.forEach(Job::cancel)
        outgoingFileJobs.clear()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
    }

    /** Runs one session. [reconnectId] marks a background reconnect, which never owns the pairing prompt. */
    private fun handle(socket: Socket, initiatedLocally: Boolean, peerHint: String?, reconnectId: String? = null) {
        if (registry.pendingSessions.size >= MAX_PENDING_SESSIONS) {
            runCatching { socket.close() }
            reconnectId?.let(dialing::remove)
            if (initiatedLocally && reconnectId == null) failPairing("Could not connect to ${peerHint ?: "Mac"}")
            return
        }
        socket.keepAlive = true
        socket.tcpNoDelay = true
        socket.soTimeout = HEARTBEAT_INTERVAL_MILLIS.toInt()
        val current = Session(socket)
        current.initiatedLocally = initiatedLocally
        current.reconnectId = reconnectId
        registry.addPending(current, initiatedLocally)
        if (initiatedLocally) {
            if (reconnectId == null) {
                // A connection the user started replaces an earlier one the user started.
                val previous = pairingSession
                pairingSession = current
                if (previous != null && registry.isPending(previous)) {
                    previous.send(Message(kind = "pairing.cancel", sessionId = previous.id))
                    previous.close()
                }
            }
            current.peerName = peerHint ?: "Mac"
            current.keyPair = Crypto.generateKeyPair()
            current.localEphemeralKey = Crypto.encodePublicKey(current.keyPair!!)
            current.id = UUID.randomUUID().toString()
            current.send(
                Message(
                    kind = "pairing.offer",
                    sessionId = current.id,
                    deviceId = localDeviceId,
                    deviceName = localDeviceName,
                    publicKey = current.localEphemeralKey,
                ),
            )
        }
        val result = runCatching {
            while (true) {
                try {
                    val line = current.input.readProtocolLine() ?: break
                    current.lastReceivedAtMillis = SystemClock.elapsedRealtime()
                    receive(current, Message.decode(line))
                } catch (_: SocketTimeoutException) {
                    if (!registry.contains(current)) break
                    if (registry.isActive(current)) {
                        if (heartbeatExpired(
                                supported = current.heartbeatSupported,
                                lastReceivedAtMillis = current.lastReceivedAtMillis,
                                nowMillis = SystemClock.elapsedRealtime(),
                            )
                        ) {
                            android.util.Log.w("Bridgey", "TRANSPORT heartbeat timed out")
                            break
                        }
                        if (!current.send(Message(
                                kind = "heartbeat.ping",
                                sessionId = current.id,
                                messageId = UUID.randomUUID().toString(),
                            ))
                        ) break
                    } else if (current.code == null &&
                        SystemClock.elapsedRealtime() - current.startedAtMillis > HANDSHAKE_TIMEOUT_MILLIS
                    ) {
                        // A handshake that never reached a pairing code does not hold a slot forever.
                        android.util.Log.w("Bridgey", "TRANSPORT handshake timed out")
                        break
                    }
                }
            }
        }
        current.close()
        current.reconnectId?.let(dialing::remove)
        val failure = result.exceptionOrNull() as? SessionFailure
        val deviceId = registry.remove(current)
        if (pairingSession === current) {
            android.util.Log.w("Bridgey", "PAIRING disconnected before confirmation")
            failPairing(failure?.message ?: "Pairing connection lost")
        } else if (failure != null && registry.connectedIds.isEmpty()) {
            failPairing(failure.message ?: "Invalid message")
        }
        if (deviceId != null) deviceDisconnected(deviceId)
    }

    private fun receive(current: Session, message: Message) {
        when (message.kind) {
            "quick.request", "quick.result", "media.state" -> receiveQuickPayload(current, message)
            "heartbeat.ping" -> {
                if (message.sessionId != current.id || !registry.isActive(current)) return
                current.heartbeatSupported = true
                current.send(Message(kind = "heartbeat.pong", sessionId = current.id, messageId = message.messageId))
            }
            "heartbeat.pong" -> {
                if (message.sessionId == current.id && registry.isActive(current)) {
                    current.heartbeatSupported = true
                }
            }
            "pairing.offer" -> {
                // The handshake runs once; an authenticated session cannot restart it.
                if (!registry.isPending(current) || current.keyPair != null) return
                current.id = message.sessionId
                current.peerName = message.deviceName ?: "Android device"
                current.remoteDeviceId = message.deviceId ?: return
                current.remoteEphemeralKey = message.publicKey ?: return
                current.keyPair = Crypto.generateKeyPair()
                current.localEphemeralKey = Crypto.encodePublicKey(current.keyPair!!)
                val material = Crypto.pairingMaterial(current.keyPair!!, current.remoteEphemeralKey, current.id)
                current.code = material.code
                current.pairingKey = material.key
                current.send(
                    Message(
                        kind = "pairing.answer",
                        sessionId = current.id,
                        deviceId = localDeviceId,
                        deviceName = localDeviceName,
                        publicKey = current.localEphemeralKey,
                    ),
                )
                authenticateOrPrompt(current)
            }
            "pairing.answer" -> {
                if (!registry.isPending(current) || !current.initiatedLocally || message.sessionId != current.id || current.pairingKey != null) return
                current.peerName = message.deviceName ?: current.peerName
                current.remoteDeviceId = message.deviceId ?: return
                current.remoteEphemeralKey = message.publicKey ?: return
                val material = Crypto.pairingMaterial(current.keyPair!!, current.remoteEphemeralKey, current.id)
                current.code = material.code
                current.pairingKey = material.key
                authenticateOrPrompt(current)
            }
            "pairing.confirm" -> {
                if (!registry.isPending(current) || message.sessionId != current.id || current.pairingKey == null) return
                val remoteId = message.deviceId ?: return fail("Invalid pairing confirmation")
                val identityKey = message.identityKey ?: return fail("Invalid pairing confirmation")
                val proof = message.proof ?: return fail("Invalid pairing confirmation")
                val signature = message.signature ?: return fail("Invalid pairing confirmation")
                val pinnedKey = trust.identityKey(remoteId)
                if (pinnedKey != null && pinnedKey != identityKey) return fail("Pinned identity changed")
                if (
                    remoteId != current.remoteDeviceId || !Crypto.verifyConfirmationProof(
                        current.pairingKey!!,
                        current.id,
                        remoteId,
                        identityKey,
                        proof,
                    ) || !Crypto.verifySignature(identityKey, authTranscript(current), signature)
                ) return fail("Pairing authentication failed")
                current.remoteIdentityKey = identityKey
                current.remoteConfirmed = true
                completeIfConfirmed(current)
            }
            "pairing.cancel" -> {
                if (pairingSession === current) {
                    pairingSession = null
                    pairingFlow = PairingState.Idle
                    publish()
                }
                current.close()
            }
            "features.update" -> receiveFeatureState(current, message)
            "clipboard.update" -> receiveClipboard(current, message, rich = false)
            "clipboard.rich" -> receiveClipboard(current, message, rich = true)
            "clipboard.ack" -> {
                val messageId = message.messageId ?: return
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "Delivered"
                    callback(ClipboardSendResult.DELIVERED)
                    android.util.Log.i("Bridgey", "PLUGIN clipboard acknowledged")
                }
            }
            "clipboard.rejected" -> {
                val messageId = message.messageId ?: return
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "Clipboard is turned off on Mac"
                    callback(ClipboardSendResult.DISABLED)
                }
            }
            "notifications.dismiss" -> receiveNotificationDismiss(current, message)
            "notifications.action" -> receiveNotificationAction(current, message)
            "calls.request" -> receiveCallRequest(current, message)
            "find.start" -> receiveFindCommand(current, message, start = true)
            "find.stop" -> receiveFindCommand(current, message, start = false)
            "find.started" -> receiveFindAcknowledgement(current, message, started = true)
            "find.stopped" -> receiveFindAcknowledgement(current, message, started = false)
            "ping.request" -> receivePing(current, message)
            "ping.ack" -> {
                if (message.sessionId != current.id || !registry.isActive(current)) return
                val messageId = message.messageId ?: return
                if (pendingPingId == messageId) {
                    pendingPingId = null
                    mutablePingStatus.value = "Ping delivered"
                }
            }
            "battery.update" -> receiveBattery(current, message)
            "files.accept" -> message.transferId?.takeIf { owns(current, it) }?.let { pendingFileAccepts.remove(it)?.complete(true) }
            "files.complete.ack" -> message.transferId?.takeIf { owns(current, it) }?.let { pendingFileCompletions.remove(it)?.complete(true) }
            "files.offer" -> {
                if (settings.isEnabled(BridgeyFeature.FILES, current.remoteDeviceId)) {
                    receiveFileOffer(current, message)
                } else {
                    current.send(Message(kind = "files.rejected", sessionId = current.id, transferId = message.transferId))
                    sendFeatureState()
                }
            }
            "files.rejected" -> message.transferId?.takeIf { owns(current, it) }?.let { transferId ->
                stopFileQueue("File transfer is turned off on Mac", current.remoteDeviceId)
                pendingFileAccepts.remove(transferId)?.complete(false)
                outgoingFileJobs.remove(transferId)?.cancel()
                removeFileTransfer(transferId, "File transfer is turned off on Mac")
            }
            // Once an offer has been accepted, let that transfer finish even if the
            // setting changes. Disabling Files blocks the next offer instead.
            "files.chunk" -> receiveFileChunk(current, message)
            "files.complete" -> receiveFileComplete(current, message)
            "files.cancel" -> receiveFileCancel(current, message.transferId)
            "files.cancel.ack" -> message.transferId?.takeIf { owns(current, it) }?.let { removeFileTransfer(it, "Transfer cancelled") }
            "folders.offer" -> receiveFolderOffer(current, message)
            "folders.manifest" -> receiveFolderManifest(current, message)
            "folders.complete" -> receiveFolderComplete(current, message)
            "folders.cancel" -> receiveFolderCancel(current, message)
            "browse.roots", "browse.list", "browse.download" -> receiveBrowseRequest(current, message)
        }
    }

    private fun receiveNotificationDismiss(current: Session, message: Message) {
        if (!settings.isEnabled(BridgeyFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted notification command"),
            message.ciphertext ?: return fail("Invalid encrypted notification command"),
        ) ?: return fail("Invalid encrypted notification command")
        val notificationId = runCatching {
            JSONObject(plaintext.toString(Charsets.UTF_8)).getString("notificationId")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: return fail("Invalid notification command")
        BridgeyNotificationListenerService.dismiss(notificationId)
        diagnostics.record("notification", "dismiss_requested")
    }

    private fun receiveCallRequest(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (!registry.isActive(current) || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        if (!settings.isEnabled(BridgeyFeature.CALLS, current.remoteDeviceId)) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            sendFeatureState()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastRemoteCallRequestAt != 0L && now - lastRemoteCallRequestAt < 3_000) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            return
        }
        val nonce = message.nonce ?: return fail("Invalid encrypted call request")
        val ciphertext = message.ciphertext ?: return fail("Invalid encrypted call request")
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            nonce,
            ciphertext,
        ) ?: return fail("Invalid encrypted call request")
        val number = runCatching {
            JSONObject(plaintext.toString(Charsets.UTF_8)).getString("number")
        }.getOrNull()?.let(::normalizedPhoneNumber)
        if (number == null) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            return
        }
        lastRemoteCallRequestAt = now
        val result = remoteCallRequest.execute(number, settings.state.value.directCallsEnabled)
        current.send(Message(kind = result.wireKind, sessionId = current.id, messageId = messageId))
        diagnostics.record("calls", "request", outcome = result.name.lowercase())
    }

    private fun receiveNotificationAction(current: Session, message: Message) {
        if (!settings.isEnabled(BridgeyFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted notification action"),
            message.ciphertext ?: return fail("Invalid encrypted notification action"),
        ) ?: return fail("Invalid encrypted notification action")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid notification action")
        val notificationId = payload.optString("notificationId")
        val actionToken = payload.optString("actionToken")
        val replyText = payload.optString("replyText").takeIf { payload.has("replyText") }
        if (notificationId.isBlank() || notificationId.length > 512 ||
            !actionToken.matches(Regex("[0-9a-f]{64}")) ||
            (replyText?.length ?: 0) > 4_096) return fail("Invalid notification action")
        BridgeyNotificationListenerService.perform(notificationId, actionToken, replyText)
        diagnostics.record("notification", if (replyText == null) "action_requested" else "reply_requested")
    }

    private fun receiveClipboard(current: Session, message: Message, rich: Boolean) {
        if (!settings.isEnabled(BridgeyFeature.CLIPBOARD, current.remoteDeviceId)) {
            current.send(Message(kind = "clipboard.rejected", sessionId = current.id, messageId = message.messageId))
            sendFeatureState()
            return
        }
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return,
            message.ciphertext ?: return,
        ) ?: return fail("Invalid encrypted clipboard message")
        val clip = if (rich) {
            val content = RichClipboardContent.decode(plaintext)
                ?: return fail("Invalid rich clipboard message")
            ClipData.newHtmlText("Bridgey", content.text, content.html)
        } else {
            val text = plaintext.toString(Charsets.UTF_8)
            if (!clipboardTextFits(text)) return fail("Invalid clipboard message")
            ClipData.newPlainText("Bridgey", text)
        }
        appContext.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        diagnostics.record("clipboard", if (rich) "rich_received" else "text_received")
        android.util.Log.i("Bridgey", "PLUGIN clipboard received")
        current.send(Message(kind = "clipboard.ack", sessionId = current.id, messageId = messageId))
    }

    private fun receiveFindAcknowledgement(current: Session, message: Message, started: Boolean) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted find-device acknowledgement"),
            message.ciphertext ?: return fail("Invalid encrypted find-device acknowledgement"),
        ) ?: return fail("Invalid encrypted find-device acknowledgement")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getString("alertId") }.getOrNull() != "active") {
            return fail("Invalid find-device acknowledgement")
        }
        registry.update(current.remoteDeviceId) { it.copy(ringing = started) }
        publish()
    }

    private fun receiveFileOffer(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted file offer"),
            message.ciphertext ?: return fail("Invalid encrypted file offer"),
        ) ?: return fail("Invalid encrypted file offer")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid file offer")
        val transferId = payload.optString("transferId")
        val name = payload.optString("name")
        val mimeType = payload.optString("mimeType", "application/octet-stream")
        val size = payload.optLong("size", -1)
        val hash = payload.optString("sha256")
        if (runCatching { UUID.fromString(transferId) }.isFailure || name.isBlank() || size !in 0..MAX_FILE_SIZE ||
            runCatching { Base64.decode(hash, Base64.DEFAULT).size == 32 }.getOrDefault(false).not() ||
            incomingFiles.containsKey(transferId)
        ) return fail("Invalid file offer")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return fail("Receiving files requires Android 10 or newer")
        }
        if (transferOwners.owner(transferId)?.let { it != current.remoteDeviceId } == true) return fail("Invalid file offer")
        transferOwners.assign(transferId, current.remoteDeviceId)
        val folderId = payload.optString("folderId").takeIf(String::isNotEmpty)
        val folder = folderId?.let { id -> incomingFolder?.takeIf { it.id == id && owns(current, id) } }
        if (folderId != null && folder == null && folderId in endedFolderIds) {
            // The folder was cancelled here while this offer was on its way: decline it, keep the session.
            markTransferCancelled(transferId)
            current.send(Message(kind = "files.cancel", sessionId = current.id, transferId = transferId))
            return
        }
        val entryIndex = payload.strictLong("entry")
        val entry = entryIndex?.takeIf { folder != null && it > folder.lastEntry && it < folder.entries.size }
            ?.let { folder?.entries?.get(it.toInt()) }
        if (folderId != null && (folder == null || entry == null || entry.isDirectory || entry.name != name || entry.size != size)) {
            return fail("Invalid file offer")
        }
        folder?.currentTransferId?.let { stale ->
            // The sender gave up on the previous file of this folder and moved on.
            markTransferCancelled(stale)
            folderFileOwners.remove(stale)
            incomingFiles.remove(stale)?.cancel()
            folder.currentTransferId = null
        }
        if (folder == null && payload.has("browseRootId")) {
            return receiveUploadOffer(current, payload, transferId, name, mimeType, size, hash)
        }
        val localPath = if (folder != null && entryIndex != null) folder.plan.localPath(entryIndex.toInt()) else null
        val transfer = runCatching {
            if (folder == null || localPath == null) {
                IncomingFileTransfer(appContext, transferId, name, mimeType, size, hash)
            } else {
                IncomingFileTransfer(
                    appContext, transferId, localPath.substringAfterLast('/'), mimeType, size, hash,
                    relativeDirectory = listOf(folder.relativeRoot, localPath.substringBeforeLast('/', ""))
                        .filter(String::isNotEmpty).joinToString("/"),
                )
            }
        }.getOrElse { return fail("Could not create file in Downloads") }
        if (folder != null && entryIndex != null) {
            folder.lastEntry = entryIndex
            folder.currentTransferId = transferId
            folderFileOwners[transferId] = folder.id
        }
        incomingFiles[transferId] = transfer
        updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: ${transfer.progress.status(0, force = true)}", true)
        current.send(Message(kind = "files.accept", sessionId = current.id, transferId = transferId))
        android.util.Log.i(
            "Bridgey",
            if (folder != null) "PLUGIN folder file accepted size=$size" else "PLUGIN file accepted name=${transfer.displayName} size=$size",
        )
    }

    private fun receiveFileChunk(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val transferId = message.transferId ?: return
        val transfer = incomingFiles[transferId]?.takeIf { owns(current, transferId) } ?: run {
            if (transferId in cancelledTransferIds) return
            return fail("Unknown file transfer")
        }
        val chunk = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted file chunk"),
            message.ciphertext ?: return fail("Invalid encrypted file chunk"),
        ) ?: return fail("Invalid encrypted file chunk")
        if (!runCatching { transfer.append(chunk, message.sequence ?: -1) }.isSuccess) {
            // Cancelled from the UI while this chunk was being written.
            if (transferId in cancelledTransferIds) return
            incomingFiles.remove(transfer.transferId)?.cancel()
            return fail("Invalid file data")
        }
        transfer.progress.status(transfer.receivedSize)?.let {
            updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: $it", true)
        }
        current.send(
            Message(
                kind = "files.chunk.ack",
                sessionId = current.id,
                transferId = transferId,
                sequence = message.sequence,
            ),
        )
    }

    private fun receiveFileComplete(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted file completion"),
            message.ciphertext ?: return fail("Invalid encrypted file completion"),
        ) ?: return fail("Invalid encrypted file completion")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid file completion")
        val transferId = payload.optString("transferId")
        val transfer = (if (owns(current, transferId)) incomingFiles.remove(transferId) else null) ?: run {
            if (transferId in cancelledTransferIds) return
            return fail("Unknown file transfer")
        }
        val savedUri = runCatching { transfer.finish(payload.optString("sha256")) }.getOrNull()
        if (savedUri == null) {
            transfer.cancel()
            if (transferId in cancelledTransferIds) return
            mutableFileTransferStatus.value = "File verification failed"
            return fail("File verification failed")
        }
        val folder = folderFileOwners.remove(transferId)?.let { id -> incomingFolder?.takeIf { it.id == id } }
        if (folder != null) {
            folder.currentTransferId = null
            folder.savedFiles++
            folder.lastSaved = savedUri to transfer.mimeType
            updateFolderRow(folder, appContext.getString(R.string.folder_receiving, folder.localName, folder.savedFiles, folder.offer.fileCount))
        } else if (transfer.isUpload) {
            updateFileTransfer(transferId, transfer.displayName, "${transfer.displayName} saved to ${transfer.locationLabel}", false)
        } else {
            updateFileTransfer(transferId, transfer.displayName, "${transfer.displayName} saved to Download/Bridgey", false)
            ReceivedFileNotifier.show(appContext, transfer.displayName, transfer.mimeType, savedUri)
        }
        current.send(Message(kind = "files.complete.ack", sessionId = current.id, transferId = transferId))
        android.util.Log.i("Bridgey", if (folder != null) "PLUGIN folder file received" else "PLUGIN file received name=${transfer.displayName}")
    }

    private fun receiveFileCancel(current: Session, transferId: String?) {
        if (transferId == null || !owns(current, transferId)) return
        folderFileOwners.remove(transferId)?.let { id ->
            incomingFolder?.takeIf { it.id == id && it.currentTransferId == transferId }?.currentTransferId = null
        }
        markTransferCancelled(transferId)
        incomingFiles.remove(transferId)?.cancel()
        outgoingFileJobs.remove(transferId)?.cancel()
        pendingFileAccepts.remove(transferId)?.complete(false)
        pendingFileCompletions.remove(transferId)?.complete(false)
        removeFileTransfer(transferId, "Transfer cancelled by Mac")
        current.send(Message(kind = "files.cancel.ack", sessionId = current.id, transferId = transferId))
        android.util.Log.i("Bridgey", "PLUGIN file cancellation received transfer=${transferId.take(8)}")
    }

    private fun receiveFolderOffer(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        if (!featureEnabled(BridgeyFeature.FILES, current) || !featureEnabled(BridgeyFeature.FOLDERS, current)) {
            current.send(Message(kind = "folders.rejected", sessionId = current.id, transferId = message.transferId))
            sendFeatureState()
            return
        }
        val payload = decryptFolderPayload(current, message) ?: return
        val folderId = payload.optString("folderId")
        if (!isUuid(folderId) || folderId != message.transferId) return
        val offer = FolderOffer(
            folderId = folderId,
            name = payload.optString("name"),
            fileCount = payload.strictLong("fileCount")?.coerceIn(-1, Int.MAX_VALUE.toLong())?.toInt() ?: -1,
            folderCount = payload.strictLong("folderCount")?.coerceIn(-1, Int.MAX_VALUE.toLong())?.toInt() ?: -1,
            totalSize = payload.strictLong("totalSize") ?: -1,
            pageCount = payload.strictLong("pageCount")?.coerceIn(-1, Int.MAX_VALUE.toLong())?.toInt() ?: -1,
        )
        val now = SystemClock.elapsedRealtime()
        val rejection = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> FolderRejection.UNSUPPORTED
            incomingFolder != null -> FolderRejection.BUSY
            folderAssembly?.let { now - it.startedAtMillis < FOLDER_MANIFEST_TIMEOUT_MILLIS } == true -> FolderRejection.BUSY
            else -> validateFolderOffer(offer)
        }
        if (rejection != null) return rejectFolder(current, folderId, rejection)
        folderAssembly = FolderAssembly(offer, now, current.remoteDeviceId)
    }

    private fun receiveFolderManifest(current: Session, message: Message) {
        val payload = decryptFolderPayload(current, message) ?: return
        val assembly = folderAssembly?.takeIf {
            it.offer.folderId == payload.optString("folderId") && it.deviceId == current.remoteDeviceId
        } ?: return
        val entries = payload.optJSONArray("entries")?.let(::parseFolderEntries)
        if (payload.strictLong("page") != assembly.nextPage.toLong() ||
            entries == null || entries.isEmpty() || entries.size > FolderRules.MAX_ENTRIES_PER_PAGE
        ) {
            folderAssembly = null
            return rejectFolder(current, assembly.offer.folderId, FolderRejection.INVALID_MANIFEST)
        }
        assembly.entries += entries
        assembly.nextPage++
        if (assembly.nextPage < assembly.offer.pageCount) return
        folderAssembly = null
        acceptFolder(current, assembly.offer, assembly.entries)
    }

    private fun acceptFolder(current: Session, offer: FolderOffer, entries: List<FolderEntry>) {
        if (!featureEnabled(BridgeyFeature.FILES, current) || !featureEnabled(BridgeyFeature.FOLDERS, current)) {
            return rejectFolder(current, offer.folderId, FolderRejection.POLICY)
        }
        validateFolderManifest(offer, entries)?.let { return rejectFolder(current, offer.folderId, it) }
        val available = runCatching { appContext.getExternalFilesDir(null)?.let { StatFs(it.path).availableBytes } }.getOrNull()
        if (available != null && offer.totalSize > available - FOLDER_SPACE_MARGIN) {
            return rejectFolder(current, offer.folderId, FolderRejection.INSUFFICIENT_SPACE)
        }
        val localName = uniqueLocalName(safeLocalName(offer.name), isDirectory = true) {
            IncomingFileTransfer.folderExists(appContext, it)
        } ?: return rejectFolder(current, offer.folderId, FolderRejection.IO_ERROR)
        val folder = IncomingFolder(offer, entries, FolderPlan.build(entries), localName)
        transferOwners.assign(folder.id, current.remoteDeviceId)
        incomingFolder = folder
        mutableFileTransfers.value = mutableFileTransfers.value + (folder.id to FileTransferState(
            id = folder.id,
            name = localName,
            status = appContext.getString(R.string.folder_receiving, localName, 0, offer.fileCount),
            active = true,
            progressPercent = null,
        ))
        pruneTransferHistory()
        refreshFileTransferSummary()
        sendFolderPayload(current, "folders.accept", JSONObject().put("folderId", folder.id).put("name", localName))
        diagnostics.record("transfer", "folder_accepted")
        android.util.Log.i("Bridgey", "PLUGIN folder accepted files=${offer.fileCount}")
    }

    private fun receiveFolderComplete(current: Session, message: Message) {
        val payload = decryptFolderPayload(current, message) ?: return
        val folder = incomingFolder?.takeIf { it.id == payload.optString("folderId") && owns(current, it.id) } ?: return
        if (folder.currentTransferId != null) return fail("Invalid folder completion")
        incomingFolder = null
        val status = if (folder.savedFiles == folder.offer.fileCount) {
            appContext.getString(R.string.folder_saved, folder.localName)
        } else {
            appContext.getString(R.string.folder_saved_partly, folder.savedFiles, folder.offer.fileCount, folder.localName)
        }
        removeFileTransfer(folder.id, status)
        folder.lastSaved?.let { (uri, mimeType) ->
            ReceivedFileNotifier.show(appContext, folder.localName, mimeType, uri, appContext.getString(R.string.folder_received))
        }
        sendFolderPayload(current, "folders.result", JSONObject().put("folderId", folder.id).put("savedFiles", folder.savedFiles))
        diagnostics.record("transfer", "folder_received")
        android.util.Log.i("Bridgey", "PLUGIN folder received files=${folder.savedFiles}")
    }

    private fun receiveFolderCancel(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val folderId = message.transferId ?: return
        if (folderAssembly?.takeIf { it.deviceId == current.remoteDeviceId }?.offer?.folderId == folderId) folderAssembly = null
        incomingFolder?.takeIf { it.id == folderId && owns(current, folderId) }?.let { endIncomingFolder(it, "Transfer cancelled by Mac") }
        current.send(Message(kind = "folders.cancel.ack", sessionId = current.id, transferId = folderId))
    }

    /** Stops an incoming folder: saved files stay, the file in progress is deleted. */
    private fun endIncomingFolder(folder: IncomingFolder, status: String) {
        if (incomingFolder === folder) incomingFolder = null
        endedFolderIds += folder.id
        while (endedFolderIds.size > 16) endedFolderIds.firstOrNull()?.let(endedFolderIds::remove)
        folder.currentTransferId?.let { transferId ->
            markTransferCancelled(transferId)
            folderFileOwners.remove(transferId)
            incomingFiles.remove(transferId)?.cancel()
        }
        removeFileTransfer(folder.id, status)
    }

    private fun updateFolderRow(folder: IncomingFolder, status: String) {
        val previous = mutableFileTransfers.value[folder.id] ?: return
        mutableFileTransfers.value = mutableFileTransfers.value + (folder.id to previous.copy(
            status = status,
            progressPercent = (folder.savedFiles * 100 / folder.offer.fileCount).coerceIn(0, 100),
        ))
        refreshFileTransferSummary()
    }

    private fun rejectFolder(current: Session, folderId: String, rejection: FolderRejection) {
        sendFolderPayload(current, "folders.rejected", JSONObject().put("folderId", folderId).put("code", rejection.code), folderId)
        diagnostics.record("transfer", "folder_rejected", rejection.code)
    }

    private fun decryptFolderPayload(current: Session, message: Message): JSONObject? {
        if (!registry.isActive(current) || message.sessionId != current.id) return null
        val messageId = message.messageId ?: return null
        if (!current.acceptMessageId(messageId)) return null
        val plaintext = Crypto.decrypt(current.pairingKey!!, message.nonce ?: "", message.ciphertext ?: "")
        if (plaintext == null || plaintext.size > FolderRules.MAX_PAYLOAD_BYTES) fail("Invalid encrypted folder message")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: fail("Invalid folder message")
        return payload.takeIf { it.opt("version") == 1 }
    }

    private fun sendFolderPayload(current: Session, kind: String, payload: JSONObject, transferId: String? = null) {
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload.put("version", 1).toString().toByteArray(Charsets.UTF_8))
        current.send(Message(
            kind = kind,
            sessionId = current.id,
            messageId = UUID.randomUUID().toString(),
            transferId = transferId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
        ))
    }

    private fun receiveBrowseRequest(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(current.pairingKey!!, message.nonce ?: "", message.ciphertext ?: "")
            ?: return fail("Invalid encrypted browse request")
        if (plaintext.size > BrowseRules.MAX_REQUEST_BYTES) return fail("Invalid browse request")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid browse request")
        val requestId = payload.optString("requestId")
        if (payload.opt("version") != 1 || !isUuid(requestId)) return
        fun error(code: String) = sendBrowsePayload(current, "browse.error", JSONObject().put("requestId", requestId).put("code", code))
        if (!featureEnabled(BridgeyFeature.FILES, current) || !featureEnabled(BridgeyFeature.FILES_BROWSE, current) ||
            !remoteEnabled(current, BridgeyFeature.FILES_BROWSE)
        ) {
            error("policy")
            sendFeatureState()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return error("policy")
        if (!browseBudget.tryStart(SystemClock.elapsedRealtime())) return error("busy")
        scope.launch {
            try {
                val answer = runCatching { answerBrowseRequest(current, message.kind, payload) }
                    .getOrElse { failure -> if (failure is SharedFolderUnavailableException) BrowseAnswer.Error("unavailable") else BrowseAnswer.Error("io_error") }
                if (!registry.isActive(current)) return@launch
                when (answer) {
                    is BrowseAnswer.Error -> error(answer.code)
                    is BrowseAnswer.Result -> sendBrowsePayload(current, answer.kind, answer.payload.put("requestId", requestId))
                }
            } finally {
                browseBudget.finish()
            }
        }
    }

    private fun answerBrowseRequest(current: Session, kind: String, payload: JSONObject): BrowseAnswer {
        val shared = settings.state.value.sharedFolders
        if (kind == "browse.roots") {
            val roots = JSONArray()
            shared.forEach { roots.put(JSONObject().put("rootId", it.id).put("name", it.name)) }
            return BrowseAnswer.Result(
                "browse.roots.result",
                JSONObject().put("roots", roots).put("uploads", settings.state.value.sharedFolderUploadsEnabled),
            )
        }
        val rootId = payload.opt("rootId") as? String ?: return BrowseAnswer.Error("invalid_request")
        val path = payload.opt("path") as? String ?: return BrowseAnswer.Error("invalid_request")
        if (!BrowseRules.isValidPath(path)) return BrowseAnswer.Error("invalid_request")
        val folder = shared.firstOrNull { it.id == rootId } ?: return BrowseAnswer.Error("not_found")
        val tree = Uri.parse(folder.uri)
        if (!sharedFolderStorage.hasGrant(tree)) return BrowseAnswer.Error("unavailable")
        val node = sharedFolderStorage.resolve(tree, path) ?: return BrowseAnswer.Error("not_found")
        return when (kind) {
            "browse.list" -> {
                val offset = payload.strictLong("offset")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
                    ?: return BrowseAnswer.Error("invalid_request")
                if (!node.isDirectory) return BrowseAnswer.Error("not_found")
                val entries = sharedFolderStorage.children(tree, node.documentId)
                    .map { BrowseEntry(it.name, it.isDirectory, it.size.takeUnless { _ -> it.isDirectory }, it.modified) }
                val page = browsePage(sortBrowseEntries(entries), offset)
                val items = JSONArray()
                page.entries.forEach { entry ->
                    items.put(JSONObject().put("name", entry.name).put("dir", entry.isDirectory).apply {
                        entry.size?.let { put("size", it) }
                        entry.modified?.let { put("modified", it) }
                    })
                }
                BrowseAnswer.Result("browse.list.result", JSONObject()
                    .put("rootId", rootId)
                    .put("path", path)
                    .put("offset", offset)
                    .put("total", page.total)
                    .put("entries", items)
                    .apply { page.nextOffset?.let { put("nextOffset", it) } })
            }
            "browse.download" -> {
                if (node.isDirectory || path.isEmpty()) return BrowseAnswer.Error("not_found")
                if (!registry.isActive(current)) return BrowseAnswer.Error("io_error")
                val transferId = sendFiles(listOf(sharedFolderStorage.documentUri(tree, node.documentId)), current.remoteDeviceId).firstOrNull()
                    ?: return BrowseAnswer.Error("policy")
                BrowseAnswer.Result("browse.download.result", JSONObject().put("transferId", transferId))
            }
            else -> BrowseAnswer.Error("invalid_request")
        }
    }

    /**
     * An upload into a shared folder. Resolving the folder and creating the document
     * can be slow on some providers, so it runs off the reader thread and counts
     * toward the browse request limits. The offer is accepted only afterwards.
     */
    private fun receiveUploadOffer(
        current: Session,
        payload: JSONObject,
        transferId: String,
        name: String,
        mimeType: String,
        size: Long,
        hash: String,
    ) {
        fun reject(code: String) = sendBrowsePayload(current, "browse.error", JSONObject().put("requestId", transferId).put("code", code))
        if (!browseBudget.tryStart(SystemClock.elapsedRealtime())) return reject("busy")
        scope.launch {
            try {
                val target = when (val resolved = resolveUploadTarget(current, payload, name)) {
                    is UploadTarget.Rejected -> return@launch reject(resolved.code)
                    is UploadTarget.Accepted -> resolved
                }
                // The constructor deletes the new document if it cannot be opened.
                val transfer = runCatching {
                    IncomingFileTransfer(
                        appContext, transferId, target.name, mimeType, size, hash,
                        documentUri = target.documentUri, locationLabel = target.label,
                    )
                }.getOrNull() ?: return@launch reject("io_error")
                if (!registry.isActive(current) || transferId in cancelledTransferIds || incomingFiles.putIfAbsent(transferId, transfer) != null) {
                    transfer.cancel()
                    return@launch
                }
                if (!registry.isActive(current)) {
                    incomingFiles.remove(transferId)?.cancel()
                    return@launch
                }
                updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: ${transfer.progress.status(0, force = true)}", true)
                current.send(Message(kind = "files.accept", sessionId = current.id, transferId = transferId))
                android.util.Log.i("Bridgey", "PLUGIN upload accepted size=$size")
            } finally {
                browseBudget.finish()
            }
        }
    }

    /** Where an upload offer may be saved, after the policy, folder and name checks. */
    private fun resolveUploadTarget(current: Session, payload: JSONObject, offeredName: String): UploadTarget {
        if (!featureEnabled(BridgeyFeature.FILES_BROWSE, current) || !remoteEnabled(current, BridgeyFeature.FILES_BROWSE)) {
            return UploadTarget.Rejected("policy")
        }
        if (!settings.state.value.sharedFolderUploadsEnabled) return UploadTarget.Rejected("read_only")
        val rootId = payload.opt("browseRootId") as? String ?: return UploadTarget.Rejected("invalid_request")
        val path = payload.opt("browsePath") as? String ?: return UploadTarget.Rejected("invalid_request")
        if (!BrowseRules.isValidPath(path)) return UploadTarget.Rejected("invalid_request")
        val folder = settings.state.value.sharedFolders.firstOrNull { it.id == rootId } ?: return UploadTarget.Rejected("not_found")
        val tree = Uri.parse(folder.uri)
        return try {
            if (!sharedFolderStorage.hasGrant(tree)) return UploadTarget.Rejected("unavailable")
            if (!sharedFolderStorage.canWrite(tree)) return UploadTarget.Rejected("read_only")
            val directory = sharedFolderStorage.resolve(tree, path)?.takeIf { it.isDirectory }
                ?: return UploadTarget.Rejected("not_found")
            if (!directory.canCreate) return UploadTarget.Rejected("read_only")
            val taken = sharedFolderStorage.children(tree, directory.documentId)
                .mapTo(HashSet()) { it.name.lowercase(java.util.Locale.ROOT) }
            // Hidden names cannot be listed or resolved, so an upload never creates one.
            val visible = safeLocalName(offeredName).replaceFirst(Regex("^\\.+"), "_")
            val name = uniqueLocalName(visible, isDirectory = false) {
                it.lowercase(java.util.Locale.ROOT) in taken
            } ?: return UploadTarget.Rejected("io_error")
            val label = listOf(folder.name, path).filter(String::isNotEmpty).joinToString("/")
            UploadTarget.Accepted(name, sharedFolderStorage.createFile(tree, directory.documentId, name), label)
        } catch (_: SharedFolderUnavailableException) {
            UploadTarget.Rejected("unavailable")
        } catch (_: Exception) {
            UploadTarget.Rejected("io_error")
        }
    }

    private fun sendBrowsePayload(current: Session, kind: String, payload: JSONObject) {
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload.put("version", 1).toString().toByteArray(Charsets.UTF_8))
        current.send(Message(
            kind = kind,
            sessionId = current.id,
            messageId = UUID.randomUUID().toString(),
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
        ))
    }

    /** Ends the current and waiting outgoing files after the session is lost. They remain retryable. */
    private fun interruptFileQueue() {
        fileSendQueue.removeAll().forEach { removeFileTransfer(it, "Transfer interrupted — reconnect to retry") }
    }

    private fun markTransferCancelled(transferId: String) {
        cancelledTransferIds += transferId
        while (cancelledTransferIds.size > 64) cancelledTransferIds.firstOrNull()?.let(cancelledTransferIds::remove)
    }

    private fun updateFileTransfer(id: String, name: String, status: String, active: Boolean) {
        folderFileOwners[id]?.let { folderId ->
            incomingFolder?.takeIf { it.id == folderId }?.let { folder ->
                updateFolderRow(folder, appContext.getString(R.string.folder_file_progress, status, folder.savedFiles + 1, folder.offer.fileCount))
            }
            return
        }
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply {
            val percent = Regex("(\\d{1,3})%").find(status)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val previous = get(id)
            put(id, FileTransferState(
                id = id,
                name = name,
                status = status,
                active = active,
                progressPercent = percent,
                startedAtMillis = previous?.startedAtMillis ?: System.currentTimeMillis(),
                retryable = !active && outgoingFileSources.containsKey(id),
            ))
        }
        pruneTransferHistory()
        refreshFileTransferSummary(status)
    }

    private fun removeFileTransfer(id: String, status: String) {
        if (folderFileOwners.containsKey(id)) return
        mutableFileTransfers.value[id]?.let { previous ->
            mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply {
                put(id, previous.copy(
                    status = status,
                    active = false,
                    progressPercent = null,
                    retryable = outgoingFileSources.containsKey(id),
                    queued = false,
                ))
            }
        }
        pruneTransferHistory()
        refreshFileTransferSummary(status)
    }

    private fun pruneTransferHistory() {
        val active = mutableFileTransfers.value.values.filter(FileTransferState::active)
        val history = mutableFileTransfers.value.values.filterNot(FileTransferState::active)
            .sortedByDescending(FileTransferState::startedAtMillis)
            .take(MAX_TRANSFER_HISTORY)
        mutableFileTransfers.value = (active + history).associateBy(FileTransferState::id)
    }

    private fun refreshFileTransferSummary(fallback: String? = null) {
        val activeTransfers = mutableFileTransfers.value.values.filter { it.active }
        mutableFileTransferActive.value = activeTransfers.isNotEmpty()
        mutableFileTransferStatus.value = (activeTransfers.firstOrNull { !it.queued } ?: activeTransfers.firstOrNull())?.status ?: fallback
    }

    private fun authenticateOrPrompt(current: Session) {
        if (trust.identityKey(current.remoteDeviceId) != null) {
            confirm(current)
            return
        }
        val waiting = pairingSession
        if (waiting != null && waiting !== current && waiting.code != null && registry.isPending(waiting)) {
            // Only one pairing code is shown at a time.
            current.send(Message(kind = "pairing.cancel", sessionId = current.id))
            current.close()
            return
        }
        pairingSession = current
        pairingFlow = PairingState.Verification(current.peerName, current.code!!)
        publish()
    }

    private fun confirm(current: Session) {
        current.localConfirmed = true
        val identityKey = identity.publicKey()
        current.send(
            Message(
                kind = "pairing.confirm",
                sessionId = current.id,
                deviceId = localDeviceId,
                identityKey = identityKey,
                proof = Crypto.confirmationProof(current.pairingKey!!, current.id, localDeviceId, identityKey),
                signature = identity.sign(authTranscript(current)),
            ),
        )
        completeIfConfirmed(current)
    }

    private fun authTranscript(current: Session): ByteArray {
        val fields = if (current.initiatedLocally) {
            listOf(localDeviceId, current.remoteDeviceId, current.localEphemeralKey, current.remoteEphemeralKey)
        } else {
            listOf(current.remoteDeviceId, localDeviceId, current.remoteEphemeralKey, current.localEphemeralKey)
        }
        return (listOf("bridgey-auth-v1", current.id) + fields).joinToString("\u0000").toByteArray()
    }

    private fun completeIfConfirmed(current: Session) {
        if (!current.localConfirmed || !current.remoteConfirmed || registry.isActive(current)) return
        val deviceId = current.remoteDeviceId
        if (!held.admits(deviceId, userInitiated = current.initiatedLocally && current.reconnectId == null)) {
            // Disconnected by the user on this phone: its reconnect is closed until the user connects again.
            current.reconnectId?.let(dialing::remove)
            current.close()
            return
        }
        trust.save(deviceId, current.peerName, current.remoteIdentityKey!!)
        val ownsPrompt = pairingSession === current
        if (ownsPrompt || pairingFlow is PairingState.Failed) {
            if (ownsPrompt) pairingSession = null
            pairingFlow = PairingState.Idle
        }
        when (val admission = registry.authenticate(current, deviceId, current.peerName)) {
            Admission.Rejected -> {
                // The device is already connected over the session the duplicate rule keeps.
                android.util.Log.i("Bridgey", "TRANSPORT duplicate session closed peerId=${deviceId.take(8)}")
                current.close()
                publish()
                return
            }
            is Admission.Accepted -> admission.replaced?.let { replaced ->
                // Transfers ran on the replaced socket and cannot continue on the new one.
                replaced.close()
                interruptTransfers(deviceId)
            }
        }
        current.reconnectId?.let(dialing::remove)
        if (registry.connectedIds.size == 1) browseBudget.reset()
        publish()
        diagnostics.record("pairing", "connected")
        sendFeatureState(current)
        localBattery?.takeIf { featureEnabled(BridgeyFeature.BATTERY, current) }?.let { (level, charging) ->
            sendBattery(current, level, charging)
        }
        android.util.Log.i("Bridgey", "PAIRING verified peer=${current.peerName}")
    }

    /** Sends each connected device the local feature state with its own per-device settings. */
    private fun sendFeatureState() {
        registry.broadcastTargets { _, _ -> true }.forEach { (_, current) -> sendFeatureState(current) }
    }

    private fun sendFeatureState(current: Session) {
        if (current.pairingKey == null) return
        val featureValues = JSONObject()
        BridgeyFeature.entries.forEach { feature ->
            featureValues.put(feature.key, settings.isEnabled(feature, current.remoteDeviceId))
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("features", featureValues)
            .toString()
            .toByteArray()
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        current.send(
            Message(
                kind = "features.update",
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    private fun receiveFeatureState(current: Session, message: Message) {
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return,
            message.ciphertext ?: return,
        ) ?: return fail("Invalid encrypted feature state")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid feature state")
        val values = payload.optJSONObject("features") ?: return fail("Invalid feature state")
        if (payload.optInt("version") != 1) return
        val received = BridgeyFeature.entries.associateWith { feature ->
            if (!values.has(feature.key)) {
                if (!featureEnabledByLegacyPeer(feature)) return@associateWith false
                return fail("Invalid feature state")
            }
            if (values.opt(feature.key) !is Boolean) return fail("Invalid feature state")
            values.getBoolean(feature.key)
        }
        registry.update(current.remoteDeviceId) { link ->
            link.copy(
                remoteFeatures = received,
                featureStateReceived = true,
                battery = link.battery.takeIf { received[BridgeyFeature.BATTERY] != false },
                ringing = link.ringing && received[BridgeyFeature.FIND_DEVICE] != false,
            )
        }
        publish()
        val deviceId = current.remoteDeviceId
        if (isFeatureAvailable(BridgeyFeature.BATTERY, deviceId, registry.link(deviceId))) {
            localBattery?.let { (level, charging) -> sendBattery(current, level, charging) }
        }
        quickActions.policyChanged()
        if (targetId == deviceId) {
            if (received[BridgeyFeature.CLIPBOARD] == false) mutableClipboardStatus.value = null
            if (received[BridgeyFeature.PING] == false) clearPingStatus()
        }
        if (received[BridgeyFeature.FILES] == false) stopFileQueue("File transfer is turned off on one of your devices", current.remoteDeviceId)
        if (received[BridgeyFeature.FIND_DEVICE] == false && ringingRequesters.remove(deviceId) && ringingRequesters.isEmpty()) {
            stopPhoneRinging()
        }
    }

    private fun receiveBattery(current: Session, message: Message) {
        if (!featureEnabled(BridgeyFeature.BATTERY, current)) return
        if (!registry.isActive(current) || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted battery status"),
            message.ciphertext ?: return fail("Invalid encrypted battery status"),
        ) ?: return fail("Invalid encrypted battery status")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid battery status")
        val level = payload.optInt("level", -1)
        if (level !in 0..100 || payload.opt("isCharging") !is Boolean) return fail("Invalid battery status")
        val battery = RemoteBatteryStatus(level, payload.getBoolean("isCharging"))
        registry.update(current.remoteDeviceId) { it.copy(battery = battery) }
        publish()
        android.util.Log.i("Bridgey", "PLUGIN battery received level=$level")
    }

    /**
     * Ends the session that sent an invalid message. Called only on that session's reader;
     * the read loop closes the session and shows [message] when it was the pairing.
     */
    private fun fail(message: String): Nothing {
        diagnostics.record("protocol", "session_failed", "rejected")
        throw SessionFailure(message)
    }

    private class SessionFailure(message: String) : Exception(message)

    /** Interrupts the transfers of one device after its session ended. They stay retryable. */
    private fun interruptTransfers(deviceId: String) {
        val owned = transferOwners.transfersOf(deviceId)
        val hadActiveTransfers = mutableFileTransfers.value.values.any { it.active && it.id in owned }
        owned.forEach { transferId ->
            incomingFiles.remove(transferId)?.cancel()
            outgoingFileJobs.remove(transferId)?.cancel()
            pendingFileAccepts.remove(transferId)?.complete(false)
            pendingFileCompletions.remove(transferId)?.complete(false)
            folderFileOwners.remove(transferId)
        }
        if (folderAssembly?.deviceId == deviceId) folderAssembly = null
        if (incomingFolder?.id in owned) incomingFolder = null
        fileSendQueue.pendingIds().filter { it in owned }.forEach(fileSendQueue::removePending)
        fileSendQueue.currentId()?.takeIf { it in owned }?.let(::finishQueuedFile)
        mutableFileTransfers.value = mutableFileTransfers.value +
            recoverInterruptedTransfers(mutableFileTransfers.value.filterKeys { it in owned })
        refreshFileTransferSummary("File transfer interrupted")
        if (hadActiveTransfers) diagnostics.record("transfer", "interrupted", "retry_available")
        if (mutableFileTransferStatus.value?.startsWith("Receiving ") == true) {
            mutableFileTransferStatus.value = "File transfer interrupted"
        }
    }

    private fun owns(current: Session, transferId: String): Boolean = transferOwners.owns(transferId, current.remoteDeviceId)

    private companion object {
        const val FILE_CHUNK_SIZE = 24 * 1024
        const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024
        const val MAX_NOTIFICATION_ICON_BASE64_LENGTH = 28 * 1024
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
        const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
        const val MAX_PENDING_SESSIONS = 8
        const val FOLDER_MANIFEST_TIMEOUT_MILLIS = 30_000L
        const val FOLDER_SPACE_MARGIN = 64L * 1024 * 1024
    }

    private class Session(private val socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        var id = ""
        var peerName = "Device"
        var remoteDeviceId = ""
        var remoteIdentityKey: String? = null
        var initiatedLocally = false
        /** Set for a background reconnect to this trusted device. */
        var reconnectId: String? = null
        var localEphemeralKey = ""
        var remoteEphemeralKey = ""
        var keyPair: KeyPair? = null
        var code: String? = null
        var pairingKey: ByteArray? = null
        var localConfirmed = false
        var remoteConfirmed = false
        val startedAtMillis = SystemClock.elapsedRealtime()
        @Volatile var lastReceivedAtMillis = SystemClock.elapsedRealtime()
        @Volatile var heartbeatSupported = false
        private val seenMessageIds = LinkedHashSet<String>()

        @Synchronized fun send(message: Message): Boolean {
            val result = runCatching {
                writer.write(message.encode())
                writer.newLine()
                writer.flush()
            }
            result.exceptionOrNull()?.let {
                android.util.Log.e("Bridgey", "TRANSPORT send failed kind=${message.kind}", it)
            }
            return result.isSuccess
        }

        fun close() = runCatching { socket.close() }.let { Unit }

        val quickSequence = QuickRequestSequence()

        @Synchronized fun acceptMessageId(id: String): Boolean {
            if (!seenMessageIds.add(id)) return false
            while (seenMessageIds.size > 256) seenMessageIds.remove(seenMessageIds.first())
            return true
        }
    }
}

private class TransferProgress(private val totalBytes: Long) {
    private var lastUpdateAt = SystemClock.elapsedRealtime()
    private var lastBytes = 0L
    private var smoothedBytesPerSecond = 0.0

    fun status(transferred: Long, force: Boolean = false): String? {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastUpdateAt
        if (!force && elapsedMs < 250 && transferred < totalBytes) return null
        if (elapsedMs > 0) {
            val sample = (transferred - lastBytes).coerceAtLeast(0) * 1000.0 / elapsedMs
            smoothedBytesPerSecond = if (smoothedBytesPerSecond == 0.0) sample else smoothedBytesPerSecond * 0.7 + sample * 0.3
        }
        lastUpdateAt = now
        lastBytes = transferred
        val percent = if (totalBytes == 0L) 100 else ((transferred * 100) / totalBytes).coerceIn(0, 100)
        val remaining = (totalBytes - transferred).coerceAtLeast(0)
        val eta = if (smoothedBytesPerSecond >= 1 && remaining > 0) formatDuration((remaining / smoothedBytesPerSecond).toLong()) else "calculating…"
        return "$percent% · ${formatBytes(transferred)} / ${formatBytes(totalBytes)} · ${formatBytes(smoothedBytesPerSecond.toLong())}/s · $eta left"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 10) "%.0f %s".format(java.util.Locale.US, value, units[unit])
        else "%.1f %s".format(java.util.Locale.US, value, units[unit])
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 1 -> "<1 sec"
        seconds < 60 -> "$seconds sec"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} sec"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

@android.annotation.TargetApi(Build.VERSION_CODES.Q)
private class IncomingFileTransfer(
    private val context: Context,
    val transferId: String,
    offeredName: String,
    val mimeType: String,
    val expectedSize: Long,
    private val expectedHash: String,
    relativeDirectory: String = RECEIVE_DIRECTORY,
    private val documentUri: Uri? = null,
    val locationLabel: String = "Download/Bridgey",
) {
    val displayName = sanitize(offeredName)
    /** Written into a shared folder through its document provider instead of MediaStore. */
    val isUpload: Boolean get() = documentUri != null
    val progress = TransferProgress(expectedSize)
    private val resolver = context.contentResolver
    private val uri: Uri
    private val output: OutputStream
    private val digest = MessageDigest.getInstance("SHA-256")
    var receivedSize = 0L
        private set
    private var nextSequence = 0L

    init {
        if (documentUri != null) {
            uri = documentUri
            output = runCatching { resolver.openOutputStream(uri, "w") }.getOrNull() ?: run {
                discard()
                error("Could not open document")
            }
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.take(255))
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDirectory)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not allocate download")
            output = resolver.openOutputStream(uri, "w") ?: run {
                resolver.delete(uri, null, null)
                error("Could not open download")
            }
        }
    }

    @Synchronized fun append(bytes: ByteArray, sequence: Long) {
        check(sequence == nextSequence)
        check(bytes.size <= 24 * 1024)
        check(receivedSize + bytes.size <= expectedSize)
        output.write(bytes)
        digest.update(bytes)
        receivedSize += bytes.size
        nextSequence++
    }

    @Synchronized fun finish(completionHash: String): Uri? {
        output.flush()
        output.close()
        val actual = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        if (receivedSize != expectedSize || completionHash != expectedHash || actual != expectedHash) {
            discard()
            return null
        }
        if (documentUri == null) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    @Synchronized fun cancel() {
        runCatching { output.close() }
        discard()
    }

    private fun discard() {
        if (documentUri != null) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
        } else {
            resolver.delete(uri, null, null)
        }
    }

    private fun sanitize(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:]"), "_")
            .take(255)
        return leaf.ifBlank { "file" }
    }

    companion object {
        val RECEIVE_DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/Bridgey"

        /**
         * Whether a folder named [name] already exists in the receive directory.
         * MediaStore only lists Bridgey's own downloads, so the file system is
         * checked as well; MediaStore still never overwrites a file it did not see.
         */
        fun folderExists(context: Context, name: String): Boolean {
            val relative = "$RECEIVE_DIRECTORY/$name/"
            val pattern = relative.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val listed = runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
                    arrayOf(pattern),
                    null,
                )?.use { it.count > 0 }
            }.getOrNull() == true
            @Suppress("DEPRECATION")
            val onDisk = runCatching {
                java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bridgey/$name").exists()
            }.getOrDefault(false)
            return listed || onDisk
        }
    }
}

private sealed interface BrowseAnswer {
    data class Result(val kind: String, val payload: JSONObject) : BrowseAnswer
    data class Error(val code: String) : BrowseAnswer
}

private sealed interface UploadTarget {
    data class Accepted(val name: String, val documentUri: Uri, val label: String) : UploadTarget
    data class Rejected(val code: String) : UploadTarget
}

private class FolderAssembly(val offer: FolderOffer, val startedAtMillis: Long, val deviceId: String) {
    val entries = ArrayList<FolderEntry>()
    var nextPage = 0
}

private class IncomingFolder(
    val offer: FolderOffer,
    val entries: List<FolderEntry>,
    val plan: FolderPlan,
    val localName: String,
) {
    val id: String get() = offer.folderId
    val relativeRoot = "${IncomingFileTransfer.RECEIVE_DIRECTORY}/$localName"
    @Volatile var lastEntry = -1L
    @Volatile var currentTransferId: String? = null
    @Volatile var savedFiles = 0
    @Volatile var lastSaved: Pair<Uri, String>? = null
}

/** An integer JSON value, or null for anything else (strings and fractions included). */
private fun JSONObject.strictLong(key: String): Long? {
    val value = opt(key)
    return if (value is Int) value.toLong() else value as? Long
}

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess && value.length == 36

/** Folder manifest entries, or null when any entry is malformed. Paths are checked later as a whole. */
private fun parseFolderEntries(array: JSONArray): List<FolderEntry>? {
    val entries = ArrayList<FolderEntry>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: return null
        val path = item.opt("path") as? String ?: return null
        val type = item.opt("type") as? String ?: return null
        entries += when (type) {
            "dir" -> if (item.has("size")) return null else FolderEntry(path, isDirectory = true)
            "file" -> FolderEntry(path, isDirectory = false, size = item.strictLong("size") ?: return null)
            else -> return null
        }
    }
    return entries
}

private data class Message(
    val kind: String,
    val sessionId: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val publicKey: String? = null,
    val identityKey: String? = null,
    val proof: String? = null,
    val signature: String? = null,
    val messageId: String? = null,
    val nonce: String? = null,
    val ciphertext: String? = null,
    val transferId: String? = null,
    val sequence: Long? = null,
) {
    fun encode(): String = JSONObject().apply {
        put("kind", kind)
        put("sessionId", sessionId)
        deviceId?.let { put("deviceId", it) }
        deviceName?.let { put("deviceName", it) }
        publicKey?.let { put("publicKey", it) }
        identityKey?.let { put("identityKey", it) }
        proof?.let { put("proof", it) }
        signature?.let { put("signature", it) }
        messageId?.let { put("messageId", it) }
        nonce?.let { put("nonce", it) }
        ciphertext?.let { put("ciphertext", it) }
        transferId?.let { put("transferId", it) }
        sequence?.let { put("sequence", it) }
    }.toString()

    companion object {
        fun decode(value: String): Message = JSONObject(value).let {
            Message(
                kind = it.getString("kind"),
                sessionId = it.getString("sessionId"),
                deviceId = it.optString("deviceId").takeIf(String::isNotEmpty),
                deviceName = it.optString("deviceName").takeIf(String::isNotEmpty),
                publicKey = it.optString("publicKey").takeIf(String::isNotEmpty),
                identityKey = it.optString("identityKey").takeIf(String::isNotEmpty),
                proof = it.optString("proof").takeIf(String::isNotEmpty),
                signature = it.optString("signature").takeIf(String::isNotEmpty),
                messageId = it.optString("messageId").takeIf(String::isNotEmpty),
                nonce = it.optString("nonce").takeIf(String::isNotEmpty),
                ciphertext = it.optString("ciphertext").takeIf(String::isNotEmpty),
                transferId = it.optString("transferId").takeIf(String::isNotEmpty),
                sequence = if (it.has("sequence")) it.getLong("sequence") else null,
            )
        }
    }
}

internal object Crypto {
    data class PairingMaterial(val code: String, val key: ByteArray)
    data class EncryptedPayload(val nonce: String, val ciphertext: String)

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun encodePublicKey(pair: KeyPair): String {
        val public = pair.public as java.security.interfaces.ECPublicKey
        val raw = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
        return java.util.Base64.getEncoder().encodeToString(raw)
    }

    fun pairingMaterial(pair: KeyPair, remoteBase64: String, sessionId: String): PairingMaterial {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(decodePublicKey(java.util.Base64.getDecoder().decode(remoteBase64)), true)
        val sharedSecret = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(sharedSecret)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val output = expand.doFinal("bridgey-pairing-v1".toByteArray() + byteArrayOf(1))
        val number = ((output[0].toLong() and 0xff) shl 24) or
            ((output[1].toLong() and 0xff) shl 16) or
            ((output[2].toLong() and 0xff) shl 8) or (output[3].toLong() and 0xff)
        return PairingMaterial((number % 1_000_000).toString().padStart(6, '0'), output)
    }

    fun confirmationProof(key: ByteArray, sessionId: String, deviceId: String, identityKey: String): String {
        val data = "bridgey-confirm-v1\u0000$sessionId\u0000$deviceId\u0000$identityKey".toByteArray()
        val proof = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data)
        return java.util.Base64.getEncoder().encodeToString(proof)
    }

    fun verifyConfirmationProof(
        key: ByteArray,
        sessionId: String,
        deviceId: String,
        identityKey: String,
        proof: String,
    ): Boolean = runCatching {
        MessageDigest.isEqual(
            java.util.Base64.getDecoder().decode(proof),
            java.util.Base64.getDecoder().decode(confirmationProof(key, sessionId, deviceId, identityKey)),
        )
    }.getOrDefault(false)

    fun encrypt(key: ByteArray, plaintext: ByteArray): EncryptedPayload {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        return EncryptedPayload(
            java.util.Base64.getEncoder().encodeToString(nonce),
            java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
        )
    }

    fun decrypt(key: ByteArray, nonce: String, ciphertext: String): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, java.util.Base64.getDecoder().decode(nonce)),
            )
        }.doFinal(java.util.Base64.getDecoder().decode(ciphertext))
    }.getOrNull()

    fun verifySignature(identityKey: String, data: ByteArray, signature: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(decodePublicKey(java.util.Base64.getDecoder().decode(identityKey)))
            update(data)
        }.verify(java.util.Base64.getDecoder().decode(signature))
    }.getOrDefault(false)

    private fun decodePublicKey(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte())
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

private class AndroidIdentity(context: Context) {
    private val alias = "bridgey.identity.p256.v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
    }

    fun publicKey(): String {
        val public = store.getCertificate(alias).publicKey as java.security.interfaces.ECPublicKey
        val raw = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun sign(data: ByteArray): String {
        val privateKey = store.getKey(alias, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

private class AndroidTrustRegistry(context: Context) {
    private val preferences = context.getSharedPreferences("bridgey.trust", Context.MODE_PRIVATE)
    private val mutableIds = MutableStateFlow(loadIds())
    val trustedDeviceIds: StateFlow<Set<String>> = mutableIds.asStateFlow()
    private val mutableDevices = MutableStateFlow(loadDevices())
    val trustedDevices: StateFlow<List<TrustedDevice>> = mutableDevices.asStateFlow()

    fun save(deviceId: String, name: String, identityKey: String) {
        preferences.edit()
            .putString("peer.$deviceId.name", name)
            .putString("peer.$deviceId.identityKey", identityKey)
            .apply()
        mutableIds.value = loadIds()
        mutableDevices.value = loadDevices()
    }

    fun remove(deviceId: String) {
        preferences.edit()
            .remove("peer.$deviceId.name")
            .remove("peer.$deviceId.identityKey")
            .apply()
        mutableIds.value = loadIds()
        mutableDevices.value = loadDevices()
    }

    fun identityKey(deviceId: String): String? = preferences.getString("peer.$deviceId.identityKey", null)

    private fun loadIds(): Set<String> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith("peer.") && it.endsWith(".identityKey") }
        .map { it.removePrefix("peer.").removeSuffix(".identityKey") }
        .toSet()

    private fun loadDevices(): List<TrustedDevice> = loadIds().map { id ->
        TrustedDevice(id, preferences.getString("peer.$id.name", null) ?: "Unknown device")
    }.sortedBy { it.name.lowercase() }
}
