package dev.bridgey.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class BridgeyNotificationListenerService : NotificationListenerService() {
    private val forwardedNotifications = ForwardedNotificationRegistry()
    private val storedActions = linkedMapOf<String, StoredNotificationAction>()
    private val actionTokensByNotificationId = mutableMapOf<String, List<String>>()
    private val applicationIcons = linkedMapOf<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallPosts = mutableMapOf<String, Runnable>()
    private val forwardedNotificationIds = mutableSetOf<String>()

    override fun onDestroy() {
        pendingCallPosts.values.forEach(mainHandler::removeCallbacks)
        pendingCallPosts.clear()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeService = java.lang.ref.WeakReference(this)
        activeNotifications.orEmpty()
            .filterNot { it.packageName == packageName }
            .forEach { forwardedNotifications.record(notificationToken(it.key), it.key, it.packageName) }
        android.util.Log.i("Bridgey", "PLUGIN notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (activeService?.get() === this) activeService = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        android.util.Log.d("Bridgey", "PLUGIN notification observed package=${sbn.packageName}")
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled || sbn.packageName == packageName) return

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val isCall = notification.category == Notification.CATEGORY_CALL
        if (shouldIgnoreOngoingNotification(notification.flags, notification.category)) return
        if (notification.visibility == Notification.VISIBILITY_SECRET) return

        val ranking = Ranking()
        if (rankingMap.getRanking(sbn.key, ranking) && ranking.importance <= NotificationManager.IMPORTANCE_MIN) return

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val applicationName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        bridgey.settings.observeNotificationApplication(sbn.packageName, applicationName)
        if (!bridgey.settings.isNotificationApplicationEnabled(sbn.packageName)) return
        val applicationIcon = applicationIcon(sbn.packageName)

        val notificationId = notificationToken(sbn.key)
        forwardedNotifications.record(notificationId, sbn.key, sbn.packageName)
        pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
        val callType = if (isCall) resolvedNotificationCallType(notification) else null
        val actions = storeActions(notificationId, notificationActionCandidates(notification, callType))
        val forward = Runnable {
            pendingCallPosts.remove(notificationId)
            if (forwardedNotifications.systemKey(notificationId) != sbn.key) return@Runnable
            bridgey.pairing.sendNotification(
                packageName = sbn.packageName,
                applicationName = applicationName,
                notificationId = notificationId,
                title = title,
                text = text,
                timestamp = sbn.postTime,
                actions = actions,
                applicationIcon = applicationIcon,
                callType = callType,
            )
            forwardedNotificationIds += notificationId
            while (forwardedNotificationIds.size > MAX_TRACKED_FORWARDED_NOTIFICATIONS) {
                forwardedNotificationIds.remove(forwardedNotificationIds.first())
            }
        }
        if (shouldDelayCallPost(callType)) {
            pendingCallPosts[notificationId] = forward
            mainHandler.postDelayed(forward, CALL_POST_SETTLE_DELAY_MS)
        } else {
            forward.run()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        val notificationId = forwardedNotifications.removeSystemKey(sbn.key) ?: return
        pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
        removeActions(notificationId)
        if (!forwardedNotificationIds.remove(notificationId)) return
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled) return
        bridgey.pairing.sendNotificationRemoved(notificationId)
    }

    private fun dismissForwardedNotification(notificationId: String): Boolean {
        val systemKey = forwardedNotifications.systemKey(notificationId) ?: return false
        android.os.Handler(android.os.Looper.getMainLooper()).post { cancelNotification(systemKey) }
        return true
    }

    @Synchronized
    private fun applyApplicationFilter(packageName: String, enabled: Boolean) {
        if (enabled) return
        val bridgey = application as BridgeyApplication
        forwardedNotifications.removePackage(packageName).forEach { notificationId ->
            pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
            removeActions(notificationId)
            if (forwardedNotificationIds.remove(notificationId) && bridgey.isPrimaryUser && bridgey.isBridgeyEnabled) {
                bridgey.pairing.sendNotificationRemoved(notificationId)
            }
        }
    }

    @Synchronized
    private fun storeActions(
        notificationId: String,
        actions: List<NotificationActionCandidate>,
    ): List<ForwardedNotificationAction> {
        removeActions(notificationId)
        val forwarded = actions.take(MAX_FORWARDED_ACTIONS).mapIndexedNotNull { index, action ->
            val title = action.title.trim().take(64)
            val pendingIntent = action.pendingIntent
            if (title.isEmpty()) return@mapIndexedNotNull null
            val remoteInputs = action.remoteInputs.filter(RemoteInput::getAllowFreeFormInput).toTypedArray()
            val token = notificationActionToken(notificationId, index)
            storedActions[token] = StoredNotificationAction(
                notificationId = notificationId,
                pendingIntent = pendingIntent,
                remoteInputs = remoteInputs,
            )
            ForwardedNotificationAction(token = token, title = title, allowsReply = remoteInputs.isNotEmpty())
        }
        actionTokensByNotificationId[notificationId] = forwarded.map(ForwardedNotificationAction::token)
        while (storedActions.size > MAX_STORED_ACTIONS) storedActions.remove(storedActions.keys.first())
        return forwarded
    }

    @Synchronized
    private fun removeActions(notificationId: String) {
        actionTokensByNotificationId.remove(notificationId).orEmpty().forEach(storedActions::remove)
    }

    @Synchronized
    private fun applicationIcon(packageName: String): String? {
        applicationIcons[packageName]?.let { return it }
        val drawable = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return null
        val encoded = ICON_SIZES.firstNotNullOfOrNull { size ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            bytes.takeIf { it.size <= MAX_ICON_BYTES }
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        } ?: return null
        applicationIcons[packageName] = encoded
        while (applicationIcons.size > MAX_CACHED_ICONS) applicationIcons.remove(applicationIcons.keys.first())
        return encoded
    }

    @Synchronized
    private fun performAction(notificationId: String, actionToken: String, replyText: String?): Boolean {
        val action = storedActions[actionToken]?.takeIf { it.notificationId == notificationId } ?: return false
        if (replyText != null && action.remoteInputs.isEmpty()) return false
        storedActions.remove(actionToken)
        return runCatching {
            if (replyText != null && action.remoteInputs.isNotEmpty()) {
                val results = Bundle().apply {
                    action.remoteInputs.forEach { putCharSequence(it.resultKey, replyText.take(MAX_REPLY_LENGTH)) }
                }
                val fillInIntent = Intent()
                RemoteInput.addResultsToIntent(action.remoteInputs, fillInIntent, results)
                action.pendingIntent.send(this, 0, fillInIntent)
            } else {
                action.pendingIntent.send()
            }
            true
        }.getOrElse {
            android.util.Log.w("Bridgey", "PLUGIN notification action failed", it)
            false
        }
    }

    companion object {
        @Volatile
        private var activeService: java.lang.ref.WeakReference<BridgeyNotificationListenerService>? = null

        fun dismiss(notificationId: String): Boolean {
            val service = activeService?.get() ?: return false
            return service.dismissForwardedNotification(notificationId)
        }

        fun perform(notificationId: String, actionToken: String, replyText: String?): Boolean {
            val service = activeService?.get() ?: return false
            return service.performAction(notificationId, actionToken, replyText)
        }

        fun filterChanged(packageName: String, enabled: Boolean) {
            activeService?.get()?.applyApplicationFilter(packageName, enabled)
        }

        private const val MAX_FORWARDED_ACTIONS = 4
        private const val MAX_STORED_ACTIONS = 2_048
        private const val MAX_REPLY_LENGTH = 4_096
        private const val MAX_ICON_BYTES = 20 * 1024
        private const val MAX_CACHED_ICONS = 128
        private const val MAX_TRACKED_FORWARDED_NOTIFICATIONS = 512
        private const val CALL_POST_SETTLE_DELAY_MS = 450L
        private val ICON_SIZES = listOf(64, 48, 32)
    }
}

private data class NotificationActionCandidate(
    val title: String,
    val pendingIntent: PendingIntent,
    val remoteInputs: List<RemoteInput> = emptyList(),
)

private fun notificationActionCandidates(
    notification: Notification,
    callType: String?,
): List<NotificationActionCandidate> {
    val candidates = notification.actions.orEmpty().mapNotNull { action ->
        val pendingIntent = action.actionIntent ?: return@mapNotNull null
        NotificationActionCandidate(
            title = action.title?.toString().orEmpty(),
            pendingIntent = pendingIntent,
            remoteInputs = action.remoteInputs.orEmpty().toList(),
        )
    }.toMutableList()
    if (callType == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return candidates

    callStyleFallbackActions(callType).forEach { action ->
        val pendingIntent = notification.extras.pendingIntent(action.extraKey) ?: return@forEach
        if (candidates.none { it.pendingIntent == pendingIntent }) {
            candidates += NotificationActionCandidate(action.title, pendingIntent)
        }
    }
    return candidates
}

private fun resolvedNotificationCallType(notification: Notification): String {
    val reportedType = notificationCallType(notification.extras.getInt("android.callType", 0))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return reportedType
    return resolvedNotificationCallType(
        reportedType = reportedType,
        hasAnswer = notification.extras.pendingIntent(Notification.EXTRA_ANSWER_INTENT) != null,
        hasDecline = notification.extras.pendingIntent(Notification.EXTRA_DECLINE_INTENT) != null,
        hasHangUp = notification.extras.pendingIntent(Notification.EXTRA_HANG_UP_INTENT) != null,
    )
}

internal fun resolvedNotificationCallType(
    reportedType: String,
    hasAnswer: Boolean,
    hasDecline: Boolean,
    hasHangUp: Boolean,
): String = when {
    hasAnswer && hasDecline && !hasHangUp -> "incoming"
    hasAnswer && hasHangUp && !hasDecline -> "screening"
    hasHangUp && !hasAnswer && !hasDecline -> "ongoing"
    else -> reportedType
}

internal fun shouldDelayCallPost(callType: String?): Boolean = callType == "ongoing"

internal data class CallStyleFallbackAction(val title: String, val extraKey: String)

internal fun callStyleFallbackActions(callType: String): List<CallStyleFallbackAction> = when (callType) {
    "incoming" -> listOf(
        CallStyleFallbackAction("Decline", Notification.EXTRA_DECLINE_INTENT),
        CallStyleFallbackAction("Answer", Notification.EXTRA_ANSWER_INTENT),
    )
    "ongoing" -> listOf(CallStyleFallbackAction("Hang Up", Notification.EXTRA_HANG_UP_INTENT))
    "screening" -> listOf(
        CallStyleFallbackAction("Hang Up", Notification.EXTRA_HANG_UP_INTENT),
        CallStyleFallbackAction("Answer", Notification.EXTRA_ANSWER_INTENT),
    )
    else -> emptyList()
}

@Suppress("DEPRECATION")
private fun Bundle.pendingIntent(key: String): PendingIntent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, PendingIntent::class.java)
    } else {
        getParcelable(key) as? PendingIntent
    }

data class ForwardedNotificationAction(
    val token: String,
    val title: String,
    val allowsReply: Boolean,
)

private data class StoredNotificationAction(
    val notificationId: String,
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>,
)

internal class ForwardedNotificationRegistry(private val limit: Int = 512) {
    private data class Entry(val systemKey: String, val packageName: String)
    private val entriesByNotificationId = linkedMapOf<String, Entry>()

    @Synchronized
    fun record(notificationId: String, systemKey: String, packageName: String) {
        entriesByNotificationId.remove(notificationId)
        entriesByNotificationId[notificationId] = Entry(systemKey, packageName)
        while (entriesByNotificationId.size > limit) {
            entriesByNotificationId.remove(entriesByNotificationId.keys.first())
        }
    }

    @Synchronized
    fun systemKey(notificationId: String): String? = entriesByNotificationId[notificationId]?.systemKey

    @Synchronized
    fun removeSystemKey(systemKey: String): String? {
        val notificationId = entriesByNotificationId.entries.firstOrNull { it.value.systemKey == systemKey }?.key ?: return null
        entriesByNotificationId.remove(notificationId)
        return notificationId
    }

    @Synchronized
    fun removePackage(packageName: String): List<String> {
        val notificationIds = entriesByNotificationId.filterValues { it.packageName == packageName }.keys.toList()
        notificationIds.forEach(entriesByNotificationId::remove)
        return notificationIds
    }
}

internal fun notificationToken(systemKey: String): String = MessageDigest.getInstance("SHA-256")
    .digest(systemKey.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun notificationActionToken(notificationId: String, index: Int): String = notificationToken("$notificationId\u0000$index")

internal fun shouldIgnoreOngoingNotification(flags: Int, category: String?): Boolean =
    flags and Notification.FLAG_ONGOING_EVENT != 0 && category != Notification.CATEGORY_CALL

internal fun notificationCallType(value: Int): String = when (value) {
    1 -> "incoming"
    2 -> "ongoing"
    3 -> "screening"
    else -> "unknown"
}

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, BridgeyNotificationListenerService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
}
