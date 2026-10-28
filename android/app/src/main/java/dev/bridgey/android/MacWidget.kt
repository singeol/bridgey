package dev.bridgey.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** The widget's buttons and the Mac action each one runs. */
internal val MAC_WIDGET_BUTTONS = listOf(
    R.id.widget_lock to MacActionIds.LOCK,
    R.id.widget_display_sleep to MacActionIds.DISPLAY_SLEEP,
    R.id.widget_sleep to MacActionIds.SLEEP,
    R.id.widget_volume_down to MacActionIds.VOLUME_DOWN,
    R.id.widget_volume_mute to MacActionIds.VOLUME_MUTE,
    R.id.widget_volume_up to MacActionIds.VOLUME_UP,
)

/** The Mac the widget shows: the connected one, otherwise the first paired Mac with a saved list. */
internal fun widgetMac(macs: List<ControlMac>): ControlMac? =
    macs.firstOrNull { it.connected } ?: macs.firstOrNull { it.actions != null } ?: macs.firstOrNull()

/**
 * Home-screen widget for Mac actions, drawn with plain RemoteViews. The system's update broadcast
 * only redraws it; taps go to [MacWidgetActionReceiver], which is not exported, through explicit
 * immutable PendingIntents.
 */
class MacWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateMacWidgets(context, manager, appWidgetIds)
    }
}

class MacWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? BridgeyApplication ?: return
        if (!app.isPrimaryUser || intent.action != ACTION_RUN) return
        val actionId = intent.getStringExtra(EXTRA_ACTION)?.takeIf { it in MAC_WIDGET_BUTTONS.map { button -> button.second } } ?: return
        app.pairing.quickActions.runMacAction(actionId)
    }

    companion object {
        const val ACTION_RUN = "dev.bridgey.android.action.RUN_MAC_ACTION"
        const val EXTRA_ACTION = "action"
    }
}

internal fun updateMacWidgets(
    context: Context,
    manager: AppWidgetManager = AppWidgetManager.getInstance(context),
    ids: IntArray = manager.getAppWidgetIds(ComponentName(context, MacWidgetProvider::class.java)),
) {
    if (ids.isEmpty()) return
    val app = context.applicationContext as? BridgeyApplication ?: return
    val views = RemoteViews(context.packageName, R.layout.mac_widget)
    val mac = if (app.isPrimaryUser) widgetMac(controlMacs(app)) else null
    views.setTextViewText(R.id.widget_title, mac?.name ?: context.getString(R.string.app_name))
    val actions = mac?.actions
    views.setTextViewText(R.id.widget_status, context.getString(when {
        mac == null -> R.string.widget_status_unpaired
        !mac.connected -> R.string.control_status_offline
        !mac.actionsLive -> R.string.control_status_unavailable
        actions == null || actions.entries.none { entry -> MAC_WIDGET_BUTTONS.any { it.second == entry.id } } -> R.string.widget_status_empty
        else -> R.string.control_status_ready
    }))
    MAC_WIDGET_BUTTONS.forEachIndexed { index, (viewId, actionId) ->
        val listed = actions?.has(actionId) == true
        views.setViewVisibility(viewId, if (listed) View.VISIBLE else View.GONE)
        val label = if (actionId == MacActionIds.VOLUME_MUTE && actions?.volume?.muted == true) R.string.mac_action_unmute
        else macActionTitleRes(actionId) ?: R.string.mac_actions_title
        views.setTextViewText(viewId, context.getString(label))
        views.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(
            context, WIDGET_REQUEST_BASE + index,
            Intent(context, MacWidgetActionReceiver::class.java)
                .setAction(MacWidgetActionReceiver.ACTION_RUN)
                .putExtra(MacWidgetActionReceiver.EXTRA_ACTION, actionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
    }
    views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(
        context, WIDGET_REQUEST_BASE + MAC_WIDGET_BUTTONS.size,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ))
    manager.updateAppWidget(ids, views)
}

private const val WIDGET_REQUEST_BASE = 7_700
