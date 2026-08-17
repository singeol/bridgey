package dev.bridgey.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager

internal enum class RemoteCallResult(val wireKind: String) {
    STARTED("calls.started"),
    CONFIRMATION_REQUIRED("calls.confirmation_required"),
    REJECTED("calls.rejected"),
}

internal fun normalizedPhoneNumber(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.any { it !in '0'..'9' && it !in "+-(). " }) return null
    if (trimmed.count { it == '+' } > 1 || ('+' in trimmed && !trimmed.startsWith('+'))) return null
    val normalized = buildString {
        if (trimmed.startsWith('+')) append('+')
        trimmed.forEach { if (it in '0'..'9') append(it) }
    }
    val digits = normalized.count { it in '0'..'9' }
    return normalized.takeIf { digits in 3..15 }
}

internal class RemoteCallRequest(private val context: Context) {
    fun execute(number: String, directEnabled: Boolean): RemoteCallResult {
        val normalized = normalizedPhoneNumber(number) ?: return RemoteCallResult.REJECTED
        val uri = Uri.fromParts("tel", normalized, null)
        val canCallDirectly = directEnabled &&
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            !isEmergencyNumber(normalized)
        if (canCallDirectly) {
            return runCatching {
                context.getSystemService(TelecomManager::class.java).placeCall(uri, android.os.Bundle.EMPTY)
                RemoteCallResult.STARTED
            }.getOrDefault(RemoteCallResult.REJECTED)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return RemoteCallResult.REJECTED
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Call requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Calls requested from a trusted Bridgey device"
            },
        )
        val dialIntent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            normalized.hashCode(),
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.bridgey.android.R.drawable.ic_bridgey_notification)
            .setContentTitle("Call requested from Mac")
            .setContentText(normalized)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.app.Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.sym_action_call),
                    "Open dialer",
                    pendingIntent,
                ).build(),
            )
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return RemoteCallResult.CONFIRMATION_REQUIRED
    }

    @Suppress("DEPRECATION")
    private fun isEmergencyNumber(number: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(TelephonyManager::class.java).isEmergencyNumber(number)
        } else {
            PhoneNumberUtils.isEmergencyNumber(number)
        }
    }.getOrDefault(true)

    companion object {
        private const val CHANNEL_ID = "bridgey_call_requests"
        private const val NOTIFICATION_ID = 41_770
    }
}
