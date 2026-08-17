package dev.bridgey.android

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
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
)

data class TrustedDevice(val id: String, val name: String)

class PairingCoordinator(
    context: Context,
    private val localDeviceId: String,
    localDeviceName: String,
    private val port: Int = 42_458,
    private val settings: BridgeySettings,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private val mutableRemoteFeatures = MutableStateFlow(defaultFeatureState())
    val remoteFeatures: StateFlow<Map<BridgeyFeature, Boolean>> = mutableRemoteFeatures.asStateFlow()
    private val incomingFiles = ConcurrentHashMap<String, IncomingFileTransfer>()
    private val cancelledTransferIds = ConcurrentHashMap.newKeySet<String>()
    private val outgoingFileJobs = ConcurrentHashMap<String, Job>()
    private val outgoingFileSources = ConcurrentHashMap<String, Uri>()
    val deviceId: String get() = localDeviceId
    private var server: ServerSocket? = null
    private var session: Session? = null
    private var acceptJob: Job? = null
    private var findRingtone: Ringtone? = null
    private val diagnostics = BridgeyDiagnostics()

    init {
        scope.launch {
            settings.state.collect {
                if (!featureEnabled(BridgeyFeature.CLIPBOARD)) mutableClipboardStatus.value = null
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
                    while (!listener.isClosed) handle(listener.accept(), initiatedLocally = false, peerHint = null)
                }
            }.onFailure { if (server?.isClosed == false) fail("Pairing listener failed") }
        }
    }

    fun pair(host: String, port: Int, peerName: String) {
        diagnostics.record("pairing", "connection_started")
        android.util.Log.i("Bridgey", "PAIRING started peer=$peerName")
        mutableState.value = PairingState.Connecting(peerName)
        scope.launch {
            runCatching { Socket(host, port) }
                .onSuccess { handle(it, initiatedLocally = true, peerHint = peerName) }
                .onFailure { fail("Could not connect to $peerName") }
        }
    }

    fun confirm() {
        scope.launch {
            val current = session ?: return@launch
            confirm(current)
        }
    }

    fun cancel() {
        val current = session
        session = null
        current?.send(Message(kind = "pairing.cancel", sessionId = current.id))
        current?.close()
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteFeatures.value = defaultFeatureState()
        mutableState.value = PairingState.Idle
    }

    fun dismiss() {
        val current = session
        session = null
        current?.close()
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteFeatures.value = defaultFeatureState()
        mutableState.value = PairingState.Idle
    }

    fun forget(deviceId: String) {
        trust.remove(deviceId)
        settings.removeDevice(deviceId)
        if ((mutableState.value as? PairingState.Connected)?.deviceId == deviceId) dismiss()
        android.util.Log.i("Bridgey", "PAIRING revoked peerId=${deviceId.take(8)}")
    }

    fun updateDeviceName(value: String) {
        localDeviceName = value.trim().take(64).ifBlank { "Android device" }
    }

    private fun featureEnabled(feature: BridgeyFeature, current: Session? = session): Boolean =
        settings.isEnabled(feature, current?.remoteDeviceId?.takeIf(String::isNotEmpty))

    fun isFeatureAvailable(feature: BridgeyFeature): Boolean =
        effectiveFeatureAvailable(featureEnabled(feature), mutableRemoteFeatures.value[feature] != false)

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
        if (connectedSession == null || mutableState.value !is PairingState.Connected) {
            mutableClipboardStatus.value = "Not connected — clipboard was not sent"
            onResult(ClipboardSendResult.NOT_CONNECTED)
            return
        }
        scope.launch {
            val current = connectedSession
            if (session !== current || mutableState.value !is PairingState.Connected) {
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
            if (pendingClipboardSends.containsKey(messageId) && session === current) {
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

    fun sendBattery(level: Int, isCharging: Boolean) {
        if (!isFeatureAvailable(BridgeyFeature.BATTERY)) return
        val connectedSession = session ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (session !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
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
        if (!isFeatureAvailable(BridgeyFeature.NOTIFICATIONS)) return
        val connectedSession = session ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (session !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
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

    fun sendNotificationRemoved(notificationId: String) {
        sendNotificationReference("notifications.remove", notificationId)
    }

    private fun sendNotificationReference(kind: String, notificationId: String) {
        if (!isFeatureAvailable(BridgeyFeature.NOTIFICATIONS) || notificationId.isBlank()) return
        val connectedSession = session ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (session !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val payload = JSONObject()
                .put("notificationId", notificationId.take(512))
                .toString()
                .toByteArray()
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

    fun findMac() {
        if (!isFeatureAvailable(BridgeyFeature.FIND_DEVICE)) return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch { sendFindCommand("find.start") }
    }

    fun stopFinding() {
        stopPhoneRinging()
        scope.launch { sendFindCommand("find.stop") }
    }

    private fun sendFindCommand(kind: String): Boolean {
        val current = session ?: return false
        if (kind == "find.start" && !isFeatureAvailable(BridgeyFeature.FIND_DEVICE)) return false
        if (mutableState.value !is PairingState.Connected) return false
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
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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
            startPhoneRinging()
            sendFindCommand(if (mutablePhoneRinging.value) "find.started" else "find.stopped")
        } else {
            stopPhoneRinging()
            mutableMacRinging.value = false
            sendFindCommand("find.stopped")
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

    fun sendFile(uri: Uri) {
        if (!isFeatureAvailable(BridgeyFeature.FILES)) {
            mutableFileTransferStatus.value = "File transfer is turned off on one of your devices"
            return
        }
        val connectedSession = session
        if (connectedSession == null || mutableState.value !is PairingState.Connected) {
            mutableFileTransferStatus.value = "Not connected — file was not sent"
            return
        }
        val transferId = UUID.randomUUID().toString()
        outgoingFileSources[transferId] = uri
        diagnostics.record("transfer", "send_started")
        updateFileTransfer(transferId, "Selected file", "Preparing…", true)
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
        job.invokeOnCompletion { outgoingFileJobs.remove(transferId, job) }
    }

    fun cancelFileTransfer(transferId: String) {
        val current = session
        markTransferCancelled(transferId)
        scope.launch {
            repeat(3) { attempt ->
                if (session === current) current?.send(Message(kind = "files.cancel", sessionId = current.id, transferId = transferId))
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

    fun cancelFileTransfer() = mutableFileTransfers.value.values.filter { it.active }.forEach { cancelFileTransfer(it.id) }

    fun retryFileTransfer(transferId: String) {
        val uri = outgoingFileSources[transferId] ?: return
        if (session == null || mutableState.value !is PairingState.Connected) {
            removeFileTransfer(transferId, "Reconnect before retrying")
            return
        }
        if (!isFeatureAvailable(BridgeyFeature.FILES)) {
            removeFileTransfer(transferId, "File transfer is turned off on one of your devices")
            return
        }
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply { remove(transferId) }
        outgoingFileSources.remove(transferId)
        diagnostics.record("transfer", "retry_started")
        sendFile(uri)
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
        val current = session
        session = null
        current?.close()
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteFeatures.value = defaultFeatureState()
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
        outgoingFileJobs.values.forEach(Job::cancel)
        outgoingFileJobs.clear()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
        mutableState.value = PairingState.Idle
    }

    private fun handle(socket: Socket, initiatedLocally: Boolean, peerHint: String?) {
        session?.close()
        socket.keepAlive = true
        socket.tcpNoDelay = true
        socket.soTimeout = HEARTBEAT_INTERVAL_MILLIS.toInt()
        val current = Session(socket)
        current.initiatedLocally = initiatedLocally
        session = current
        mutableRemoteFeatures.value = defaultFeatureState()
        if (initiatedLocally) {
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
                    if (session !== current) break
                    if (mutableState.value is PairingState.Connected) {
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
                    }
                }
            }
        }
        if (session === current && mutableState.value is PairingState.Connected) {
            session = null
            cancelIncomingFiles()
            stopPhoneRinging()
            mutableMacRinging.value = false
            mutableRemoteFeatures.value = defaultFeatureState()
            mutableState.value = PairingState.Idle
            diagnostics.record("transport", "disconnected", "reconnecting")
            android.util.Log.i("Bridgey", "TRANSPORT disconnected")
        } else if (session === current) {
            result.exceptionOrNull()?.let {
                android.util.Log.w("Bridgey", "PAIRING disconnected before confirmation")
            }
            fail("Pairing connection lost")
        }
    }

    private fun receive(current: Session, message: Message) {
        when (message.kind) {
            "heartbeat.ping" -> {
                if (message.sessionId != current.id || mutableState.value !is PairingState.Connected) return
                current.heartbeatSupported = true
                current.send(Message(kind = "heartbeat.pong", sessionId = current.id, messageId = message.messageId))
            }
            "heartbeat.pong" -> {
                if (message.sessionId == current.id && mutableState.value is PairingState.Connected) {
                    current.heartbeatSupported = true
                }
            }
            "pairing.offer" -> {
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
                if (message.sessionId != current.id) return
                current.peerName = message.deviceName ?: current.peerName
                current.remoteDeviceId = message.deviceId ?: return
                current.remoteEphemeralKey = message.publicKey ?: return
                val material = Crypto.pairingMaterial(current.keyPair!!, current.remoteEphemeralKey, current.id)
                current.code = material.code
                current.pairingKey = material.key
                authenticateOrPrompt(current)
            }
            "pairing.confirm" -> {
                if (message.sessionId != current.id) return
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
            "pairing.cancel" -> cancel()
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
            "find.start" -> receiveFindCommand(current, message, start = true)
            "find.stop" -> receiveFindCommand(current, message, start = false)
            "find.started" -> receiveFindAcknowledgement(current, message, started = true)
            "find.stopped" -> receiveFindAcknowledgement(current, message, started = false)
            "files.accept" -> message.transferId?.let { pendingFileAccepts.remove(it)?.complete(true) }
            "files.complete.ack" -> message.transferId?.let { pendingFileCompletions.remove(it)?.complete(true) }
            "files.offer" -> {
                if (settings.isEnabled(BridgeyFeature.FILES, current.remoteDeviceId)) {
                    receiveFileOffer(current, message)
                } else {
                    current.send(Message(kind = "files.rejected", sessionId = current.id, transferId = message.transferId))
                    sendFeatureState()
                }
            }
            "files.rejected" -> message.transferId?.let { transferId ->
                pendingFileAccepts.remove(transferId)?.complete(false)
                outgoingFileJobs.remove(transferId)?.cancel()
                removeFileTransfer(transferId, "File transfer is turned off on Mac")
            }
            // Once an offer has been accepted, let that transfer finish even if the
            // setting changes. Disabling Files blocks the next offer instead.
            "files.chunk" -> receiveFileChunk(current, message)
            "files.complete" -> receiveFileComplete(current, message)
            "files.cancel" -> receiveFileCancel(message.transferId)
            "files.cancel.ack" -> message.transferId?.let { removeFileTransfer(it, "Transfer cancelled") }
        }
    }

    private fun receiveNotificationDismiss(current: Session, message: Message) {
        if (!settings.isEnabled(BridgeyFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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

    private fun receiveNotificationAction(current: Session, message: Message) {
        if (!settings.isEnabled(BridgeyFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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
        mutableMacRinging.value = started
    }

    private fun receiveFileOffer(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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
        val transfer = runCatching {
            IncomingFileTransfer(appContext, transferId, name, mimeType, size, hash)
        }.getOrElse { return fail("Could not create file in Downloads") }
        incomingFiles[transferId] = transfer
        updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: ${transfer.progress.status(0, force = true)}", true)
        current.send(Message(kind = "files.accept", sessionId = current.id, transferId = transferId))
        android.util.Log.i("Bridgey", "PLUGIN file accepted name=${transfer.displayName} size=$size")
    }

    private fun receiveFileChunk(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val transferId = message.transferId ?: return
        val transfer = incomingFiles[transferId] ?: run {
            if (transferId in cancelledTransferIds) return
            return fail("Unknown file transfer")
        }
        val chunk = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted file chunk"),
            message.ciphertext ?: return fail("Invalid encrypted file chunk"),
        ) ?: return fail("Invalid encrypted file chunk")
        if (!runCatching { transfer.append(chunk, message.sequence ?: -1) }.isSuccess) {
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
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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
        val transfer = incomingFiles.remove(transferId) ?: run {
            if (transferId in cancelledTransferIds) return
            return fail("Unknown file transfer")
        }
        val savedUri = runCatching { transfer.finish(payload.optString("sha256")) }.getOrNull()
        if (savedUri == null) {
            transfer.cancel()
            mutableFileTransferStatus.value = "File verification failed"
            return fail("File verification failed")
        }
        updateFileTransfer(transferId, transfer.displayName, "${transfer.displayName} saved to Download/Bridgey", false)
        ReceivedFileNotifier.show(appContext, transfer.displayName, transfer.mimeType, savedUri)
        current.send(Message(kind = "files.complete.ack", sessionId = current.id, transferId = transferId))
        android.util.Log.i("Bridgey", "PLUGIN file received name=${transfer.displayName}")
    }

    private fun receiveFileCancel(transferId: String?) {
        if (transferId == null) return
        markTransferCancelled(transferId)
        incomingFiles.remove(transferId)?.cancel()
        outgoingFileJobs.remove(transferId)?.cancel()
        pendingFileAccepts.remove(transferId)?.complete(false)
        pendingFileCompletions.remove(transferId)?.complete(false)
        removeFileTransfer(transferId, "Transfer cancelled by Mac")
        session?.send(Message(kind = "files.cancel.ack", sessionId = session?.id ?: return, transferId = transferId))
        android.util.Log.i("Bridgey", "PLUGIN file cancellation received transfer=${transferId.take(8)}")
    }

    private fun markTransferCancelled(transferId: String) {
        cancelledTransferIds += transferId
        while (cancelledTransferIds.size > 64) cancelledTransferIds.firstOrNull()?.let(cancelledTransferIds::remove)
    }

    private fun updateFileTransfer(id: String, name: String, status: String, active: Boolean) {
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
        mutableFileTransfers.value[id]?.let { previous ->
            mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply {
                put(id, previous.copy(
                    status = status,
                    active = false,
                    progressPercent = null,
                    retryable = outgoingFileSources.containsKey(id),
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
        mutableFileTransferStatus.value = activeTransfers.firstOrNull()?.status ?: fallback
    }

    private fun authenticateOrPrompt(current: Session) {
        if (trust.identityKey(current.remoteDeviceId) != null) {
            confirm(current)
        } else {
            mutableState.value = PairingState.Verification(current.peerName, current.code!!)
        }
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
        if (current.localConfirmed && current.remoteConfirmed) {
            trust.save(current.remoteDeviceId, current.peerName, current.remoteIdentityKey!!)
            mutableState.value = PairingState.Connected(current.remoteDeviceId, current.peerName)
            diagnostics.record("pairing", "connected")
            sendFeatureState()
            android.util.Log.i("Bridgey", "PAIRING verified peer=${current.peerName}")
        }
    }

    private fun sendFeatureState() {
        val current = session ?: return
        if (mutableState.value !is PairingState.Connected || current.pairingKey == null) return
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
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
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
            if (!values.has(feature.key) || values.opt(feature.key) !is Boolean) return fail("Invalid feature state")
            values.getBoolean(feature.key)
        }
        mutableRemoteFeatures.value = received
        if (received[BridgeyFeature.CLIPBOARD] == false) mutableClipboardStatus.value = null
        if (received[BridgeyFeature.FIND_DEVICE] == false) {
            stopPhoneRinging()
            mutableMacRinging.value = false
        }
    }

    private fun fail(message: String) {
        session?.close()
        session = null
        cancelIncomingFiles()
        mutableRemoteFeatures.value = defaultFeatureState()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
        mutableState.value = PairingState.Failed(message)
        diagnostics.record("protocol", "session_failed", "rejected")
    }

    private fun cancelIncomingFiles() {
        val hadActiveTransfers = mutableFileTransfers.value.values.any(FileTransferState::active)
        incomingFiles.values.forEach(IncomingFileTransfer::cancel)
        incomingFiles.clear()
        outgoingFileJobs.values.forEach(Job::cancel)
        outgoingFileJobs.clear()
        pendingFileAccepts.values.forEach { it.complete(false) }
        pendingFileAccepts.clear()
        pendingFileCompletions.values.forEach { it.complete(false) }
        pendingFileCompletions.clear()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
        if (hadActiveTransfers) diagnostics.record("transfer", "interrupted", "retry_available")
        if (mutableFileTransferStatus.value?.startsWith("Receiving ") == true) {
            mutableFileTransferStatus.value = "File transfer interrupted"
        }
    }

    private companion object {
        fun defaultFeatureState(): Map<BridgeyFeature, Boolean> = BridgeyFeature.entries.associateWith { true }
        const val FILE_CHUNK_SIZE = 24 * 1024
        const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024
        const val MAX_NOTIFICATION_ICON_BASE64_LENGTH = 28 * 1024
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
    }

    private class Session(private val socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        var id = ""
        var peerName = "Device"
        var remoteDeviceId = ""
        var remoteIdentityKey: String? = null
        var initiatedLocally = false
        var localEphemeralKey = ""
        var remoteEphemeralKey = ""
        var keyPair: KeyPair? = null
        var code: String? = null
        var pairingKey: ByteArray? = null
        var localConfirmed = false
        var remoteConfirmed = false
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
) {
    val displayName = sanitize(offeredName)
    val progress = TransferProgress(expectedSize)
    private val resolver = context.contentResolver
    private val uri: Uri
    private val output: OutputStream
    private val digest = MessageDigest.getInstance("SHA-256")
    var receivedSize = 0L
        private set
    private var nextSequence = 0L

    init {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.take(255))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Bridgey")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not allocate download")
        output = resolver.openOutputStream(uri, "w") ?: run {
            resolver.delete(uri, null, null)
            error("Could not open download")
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
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Synchronized fun cancel() {
        runCatching { output.close() }
        resolver.delete(uri, null, null)
    }

    private fun sanitize(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:]"), "_")
            .take(255)
        return leaf.ifBlank { "file" }
    }
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
