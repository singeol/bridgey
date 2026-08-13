package dev.bridgey.android

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BridgeyNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        android.util.Log.i("Bridgey", "PLUGIN notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        android.util.Log.d("Bridgey", "PLUGIN notification observed package=${sbn.packageName}")
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled || sbn.packageName == packageName) return

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
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

        bridgey.pairing.sendNotification(
            packageName = sbn.packageName,
            applicationName = applicationName,
            notificationId = listOf(sbn.packageName, sbn.id.toString(), sbn.tag.orEmpty()).joinToString(":"),
            title = title,
            text = text,
            timestamp = sbn.postTime,
        )
    }
}

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, BridgeyNotificationListenerService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
}
