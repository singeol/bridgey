package dev.bridgey.android

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BridgeyConnectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private var supportedProfile = true
    private var lastStatus = "Not connected · waiting for paired device"

    override fun onCreate() {
        super.onCreate()
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled) {
            supportedProfile = false
            stopSelf()
            return
        }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bridgey clipboard", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Keeps paired Bridgey devices connected"
                setSound(null, null)
                enableVibration(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(TRANSFER_CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows active Bridgey file transfers and cancellation"
                setSound(null, null)
                enableVibration(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(FIND_CHANNEL_ID, "Find device", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows when another Bridgey device is finding this phone"
                setSound(null, null)
                enableVibration(false)
            },
        )
        startForeground(NOTIFICATION_ID, buildNotification(lastStatus))
        serviceScope.launch {
            (application as BridgeyApplication).pairing.state.collect { state ->
                lastStatus = when (state) {
                    is PairingState.Connected -> "Connected to ${state.peerName}"
                    is PairingState.Connecting -> "Connecting to ${state.peerName}…"
                    is PairingState.Verification -> "Pairing confirmation required"
                    is PairingState.Failed -> "Not connected"
                    PairingState.Idle -> "Not connected · waiting for paired device"
                }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(lastStatus))
            }
        }
        serviceScope.launch {
            bridgey.pairing.fileTransferActive.collect {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(lastStatus))
            }
        }
        serviceScope.launch {
            bridgey.settings.state.collect {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(lastStatus))
            }
        }
        serviceScope.launch {
            bridgey.pairing.remoteFeatures.collect {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(lastStatus))
            }
        }
        serviceScope.launch { bridgey.pairing.fileTransfers.collect(::updateTransferNotifications) }
        serviceScope.launch {
            bridgey.pairing.phoneRinging.collect { ringing ->
                if (ringing) notificationManager.notify(FIND_NOTIFICATION_ID, buildFindNotification())
                else notificationManager.cancel(FIND_NOTIFICATION_ID)
            }
        }
    }

    private val shownTransferNotifications = mutableSetOf<Int>()
    private val transferNotificationUpdatedAt = mutableMapOf<String, Long>()

    private fun updateTransferNotifications(transfers: Map<String, FileTransferState>) {
        val active = transfers.values.filter { it.active }
        val activeIds = active.map { transferNotificationId(it.id) }.toSet()
        (shownTransferNotifications - activeIds).forEach(notificationManager::cancel)
        transferNotificationUpdatedAt.keys.retainAll(active.map { it.id }.toSet())
        shownTransferNotifications.clear()
        shownTransferNotifications.addAll(activeIds)
        active.forEach { transfer ->
            val now = SystemClock.elapsedRealtime()
            val previous = transferNotificationUpdatedAt[transfer.id] ?: 0L
            if (now - previous < 1_000) return@forEach
            transferNotificationUpdatedAt[transfer.id] = now
            val notificationId = transferNotificationId(transfer.id)
            val cancelTransfer = PendingIntent.getService(
                this,
                notificationId,
                Intent()
                    .setComponent(ComponentName(this, BridgeyConnectionService::class.java))
                    .setAction(ACTION_CANCEL_TRANSFER)
                    .putExtra(EXTRA_TRANSFER_ID, transfer.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = android.app.Notification.Builder(this, TRANSFER_CHANNEL_ID)
                .setSmallIcon(dev.bridgey.android.R.drawable.ic_bridgey_notification)
                .setContentTitle(transfer.name)
                .setContentText(compactTransferStatus(transfer))
                .setSubText(transfer.progressPercent?.let { "$it%" })
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(android.app.Notification.CATEGORY_PROGRESS)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel this file", cancelTransfer)
            transfer.progressPercent?.let { builder.setProgress(100, it, false) }
                ?: builder.setProgress(0, 0, true)
            notificationManager.notify(
                notificationId,
                builder.build(),
            )
        }
    }

    private fun compactTransferStatus(transfer: FileTransferState): String {
        val detail = transfer.status.substringAfter(": ", transfer.status)
        return detail
            .replace(Regex("^(Sending|Receiving)\\s+${Regex.escape(transfer.name)}:?\\s*"), "")
            .ifBlank { if (transfer.progressPercent == null) "Preparing…" else "Transferring…" }
    }

    private fun transferNotificationId(transferId: String) = 50_000 + (transferId.hashCode() and 0x0fff)

    private fun buildNotification(status: String): android.app.Notification {
        val openAppIntent = Intent()
            .setComponent(ComponentName(this, MainActivity::class.java)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val captureIntent = Intent()
            .setComponent(ComponentName(this, ClipboardCaptureActivity::class.java)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        val pendingCapture = PendingIntent.getActivity(
            this,
            1,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restoreNotification = PendingIntent.getService(
            this,
            3,
            Intent()
                .setComponent(ComponentName(this, BridgeyConnectionService::class.java))
                .setAction(ACTION_RESTORE_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val turnOff = PendingIntent.getService(
            this,
            4,
            Intent()
                .setComponent(ComponentName(this, BridgeyConnectionService::class.java))
                .setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(dev.bridgey.android.R.drawable.ic_bridgey_notification)
            .setContentTitle("Bridgey")
            .setContentText(status)
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpenApp)
            .setDeleteIntent(restoreNotification)
            .setAutoCancel(false)
            .setOngoing(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val pairing = (application as BridgeyApplication).pairing
        val connectedDeviceId = (pairing.state.value as? PairingState.Connected)?.deviceId
        if (connectedDeviceId != null && pairing.isFeatureAvailable(BridgeyFeature.CLIPBOARD)) {
            notification.addAction(android.R.drawable.ic_menu_send, "Send clipboard", pendingCapture)
        }
        return notification
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", turnOff)
            .build()
    }

    private fun buildFindNotification(): android.app.Notification {
        val stop = PendingIntent.getService(
            this,
            5,
            Intent()
                .setComponent(ComponentName(this, BridgeyConnectionService::class.java))
                .setAction(ACTION_STOP_FINDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return android.app.Notification.Builder(this, FIND_CHANNEL_ID)
            .setSmallIcon(dev.bridgey.android.R.drawable.ic_bridgey_notification)
            .setContentTitle("Bridgey is finding this phone")
            .setContentText("Your Mac requested a sound")
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!supportedProfile) return START_NOT_STICKY
        if (intent?.action == ACTION_TURN_OFF) {
            (application as BridgeyApplication).disableBridgey()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 250)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_TRANSFER) {
            val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
            if (transferId != null) (application as BridgeyApplication).pairing.cancelFileTransfer(transferId)
            else (application as BridgeyApplication).pairing.cancelFileTransfer()
            updateTransferNotifications((application as BridgeyApplication).pairing.fileTransfers.value)
            startForeground(NOTIFICATION_ID, buildNotification(lastStatus))
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_FINDING) {
            (application as BridgeyApplication).pairing.stopFinding()
            notificationManager.cancel(FIND_NOTIFICATION_ID)
            return START_STICKY
        }
        // Re-post even when the service is already alive: Android 13+ and some
        // Samsung builds allow users to dismiss foreground-service cards.
        startForeground(NOTIFICATION_ID, buildNotification(lastStatus))
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "bridgey_clipboard_v2"
        private const val NOTIFICATION_ID = 42_458
        private const val TRANSFER_CHANNEL_ID = "bridgey_file_transfers_v1"
        private const val FIND_CHANNEL_ID = "bridgey_find_device_v1"
        private const val FIND_NOTIFICATION_ID = 42_459
        private const val ACTION_RESTORE_NOTIFICATION = "dev.bridgey.android.RESTORE_NOTIFICATION"
        const val ACTION_TURN_OFF = "dev.bridgey.android.TURN_OFF"
        private const val ACTION_CANCEL_TRANSFER = "dev.bridgey.android.CANCEL_TRANSFER"
        private const val ACTION_STOP_FINDING = "dev.bridgey.android.STOP_FINDING"
        private const val EXTRA_TRANSFER_ID = "dev.bridgey.android.extra.TRANSFER_ID"
    }
}

class ClipboardCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDimAmount(0f)
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            // Clipboard access is allowed only once this user-initiated activity is foreground.
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@ClipboardCaptureActivity)?.toString()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this@ClipboardCaptureActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                val appContext = applicationContext
                (application as BridgeyApplication).pairing.sendText(text) { result ->
                    Handler(Looper.getMainLooper()).post {
                        val message = when (result) {
                            ClipboardSendResult.DELIVERED -> "Clipboard sent to Mac"
                            ClipboardSendResult.EMPTY -> "Clipboard is empty"
                            ClipboardSendResult.DISABLED -> "Clipboard is turned off on one of your devices"
                            ClipboardSendResult.NOT_CONNECTED -> "Not connected — clipboard not sent"
                            ClipboardSendResult.CONNECTION_LOST -> "Send failed — connection lost"
                            ClipboardSendResult.NO_ACKNOWLEDGEMENT -> "Mac did not confirm delivery"
                            ClipboardSendResult.TOO_LARGE -> "Clipboard exceeds 32 KiB. Send it as a file."
                        }
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
        }, 150)
    }
}
