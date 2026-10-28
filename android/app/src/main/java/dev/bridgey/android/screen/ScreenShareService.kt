package dev.bridgey.android.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.bridgey.android.BridgeyApplication
import dev.bridgey.android.MainActivity
import dev.bridgey.android.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The foreground service a share runs in (type mediaProjection). Android 14 order: the consent result
 * arrives, this service goes to the foreground with the mediaProjection type, and only then takes the
 * projection; its callback is registered before the VirtualDisplay is made. The ongoing notification
 * with Stop stays for as long as the service runs. The screen turning off ends the share.
 */
class ScreenShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var ticker: ScheduledExecutorService? = null
    private var stream: ScreenStream? = null
    private var screenOffRegistered = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) stream?.stop(ScreenStopReason.SCREEN_OFF)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system Stop, or another app capturing.
            stream?.stop(ScreenStopReason.ENDED)
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            capture?.capturedContentResized(width, height)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BridgeyApplication
        if (!app.isPrimaryUser) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            app.screenShare.stop(ScreenStopReason.USER)
            if (stream == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || stream != null) {
            if (stream == null) stopSelf()
            return START_NOT_STICKY
        }
        val macName = intent.getStringExtra(EXTRA_MAC_NAME).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        // Foreground with the mediaProjection type before the projection is taken (Android 14 rule).
        val foreground = runCatching {
            val notification = buildNotification(macName, sharing = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w("Bridgey", "PLUGIN screen foreground refused", it) }.isSuccess
        val taken = if (foreground && resultCode == Activity.RESULT_OK && data != null) {
            runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data) }
                .onFailure { Log.w("Bridgey", "PLUGIN screen projection refused", it) }.getOrNull()
        } else {
            null
        }
        val created = taken?.let { app.screenShare.begin(macName) }
        if (taken == null || created == null) {
            runCatching { taken?.stop() }
            app.screenShare.streamEnded = {}
            app.screenShare.stop(ScreenStopReason.FAILED)
            finish()
            return START_NOT_STICKY
        }
        val captureThread = HandlerThread("bridgey-screen-capture").also { it.start() }
        val handler = Handler(captureThread.looper)
        thread = captureThread
        projection = taken
        stream = created
        // Registered before the VirtualDisplay exists, as Android 14 requires.
        taken.registerCallback(projectionCallback, handler)
        app.screenShare.streamEnded = { main.post(::finish) }
        created.onStarted.let { hubStarted ->
            created.onStarted = {
                hubStarted()
                main.post { if (stream === created) updateNotification(macName, sharing = true) }
            }
        }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOffRegistered = true
        val newCapture = ScreenCapture(this, taken, created, handler) { reason -> created.stop(reason) }
        capture = newCapture
        handler.post { newCapture.start() }
        ticker = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "bridgey-screen-ticker") }.also {
            it.scheduleWithFixedDelay({ runCatching { created.tick() } }, 1, 1, TimeUnit.SECONDS)
        }
        return START_NOT_STICKY
    }

    /** Tears everything down once; the stream has already ended, or never started. */
    private fun finish() {
        ticker?.shutdownNow()
        ticker = null
        capture?.release()
        capture = null
        projection?.let { current ->
            runCatching { current.unregisterCallback(projectionCallback) }
            runCatching { current.stop() }
        }
        projection = null
        if (screenOffRegistered) {
            runCatching { unregisterReceiver(screenOff) }
            screenOffRegistered = false
        }
        thread?.quitSafely()
        thread = null
        stream = null
        (application as BridgeyApplication).screenShare.streamEnded = {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Stopped from outside: the Mac hears it, then everything goes.
        stream?.let { if (it.phase != ScreenStreamPhase.ENDED) it.stop(ScreenStopReason.FAILED) }
        if (projection != null || capture != null) finish()
        super.onDestroy()
    }

    private fun buildNotification(macName: String, sharing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.screen_sharing_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.screen_sharing_channel_detail)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val stop = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            Intent().setComponent(ComponentName(this, ScreenShareService::class.java)).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent().setComponent(ComponentName(this, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridgey_notification)
            .setContentTitle(getString(R.string.screen_sharing_title, macName))
            .setContentText(getString(if (sharing) R.string.screen_sharing_text else R.string.screen_sharing_starting))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(sharing)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.screen_stop), stop).build())
            .build()
    }

    private fun updateNotification(macName: String, sharing: Boolean) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(macName, sharing)) }
    }

    companion object {
        private const val ACTION_START = "dev.bridgey.android.action.SCREEN_SHARE_START"
        private const val ACTION_STOP = "dev.bridgey.android.action.SCREEN_SHARE_STOP"
        private const val EXTRA_MAC_NAME = "dev.bridgey.android.extra.SCREEN_MAC_NAME"
        private const val EXTRA_RESULT_CODE = "dev.bridgey.android.extra.SCREEN_RESULT_CODE"
        private const val EXTRA_RESULT_DATA = "dev.bridgey.android.extra.SCREEN_RESULT_DATA"
        private const val CHANNEL_ID = "bridgey_screen_sharing_v1"
        private const val NOTIFICATION_ID = 69_500

        fun startIntent(context: Context, macName: String, resultCode: Int, data: Intent): Intent =
            Intent().setComponent(ComponentName(context, ScreenShareService::class.java)).setAction(ACTION_START)
                .putExtra(EXTRA_MAC_NAME, macName)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }
}
