package dev.bridgey.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleTemplate
import androidx.annotation.RequiresApi
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun ControlSpec.title(context: Context): String = customTitle ?: context.getString(
    when (kind) {
        ControlKind.PING -> R.string.control_ping
        ControlKind.FIND -> R.string.control_find
        else -> actionId?.let(::macActionTitleRes) ?: R.string.mac_actions_title
    },
)

/**
 * Device Controls (Android 11 and later): the paired Mac's actions, Ping and Find in the system
 * controls space. The mapping is [controlSpecs]; this service only builds Controls and dispatches
 * taps. Android requires unlocking the phone before a control runs unless the user allows controls
 * on the lock screen.
 */
@RequiresApi(30)
class MacControlsService : ControlsProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as BridgeyApplication

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun specs(): List<ControlSpec> = if (app.isPrimaryUser) controlSpecs(controlMacs(app)) else emptyList()

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> = ControlsPublisher(scope, live = false) {
        specs().map { statelessControl(it) }
    }

    override fun createPublisherFor(controlIds: MutableList<String>): Flow.Publisher<Control> {
        val wanted = controlIds.toSet()
        return ControlsPublisher(scope, live = app.isPrimaryUser) { specs().filter { it.controlId in wanted }.map { statefulControl(it) } }
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val command = specs().firstOrNull { it.controlId == controlId }?.let {
            controlCommand(it, (action as? FloatAction)?.newValue, (action as? BooleanAction)?.newState)
        }
        if (command == null) {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }
        runControlCommand(app.pairing, command)
        consumer.accept(ControlAction.RESPONSE_OK)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun deviceType(spec: ControlSpec): Int = when (spec.kind) {
        ControlKind.ACTION -> if (spec.actionId == MacActionIds.LOCK) DeviceTypes.TYPE_LOCK else DeviceTypes.TYPE_GENERIC_START_STOP
        ControlKind.VOLUME, ControlKind.MUTE -> DeviceTypes.TYPE_GENERIC_ON_OFF
        ControlKind.PING, ControlKind.FIND -> DeviceTypes.TYPE_REMOTE_CONTROL
    }

    private fun statelessControl(spec: ControlSpec): Control = Control.StatelessBuilder(spec.controlId, openApp())
        .setTitle(spec.title(this))
        .setSubtitle(spec.macName)
        .setZone(spec.macName)
        .setStructure(getString(R.string.app_name))
        .setDeviceType(deviceType(spec))
        .setCustomIcon(Icon.createWithResource(this, R.drawable.ic_bridgey_notification))
        .build()

    private fun statefulControl(spec: ControlSpec): Control {
        val title = spec.title(this)
        val template: ControlTemplate = when (spec.kind) {
            ControlKind.ACTION, ControlKind.PING -> StatelessTemplate(spec.controlId)
            ControlKind.VOLUME -> RangeTemplate(spec.controlId, 0f, 100f, (spec.volume?.level ?: 0).toFloat(), 1f, "%.0f%%")
            ControlKind.MUTE, ControlKind.FIND -> ToggleTemplate(spec.controlId, ControlButton(spec.checked, title))
        }
        val status = getString(when {
            spec.state == ControlState.OFFLINE -> R.string.control_status_offline
            spec.state == ControlState.UNAVAILABLE -> R.string.control_status_unavailable
            spec.kind == ControlKind.FIND && spec.checked -> R.string.control_status_ringing
            spec.kind == ControlKind.MUTE && spec.checked -> R.string.control_status_muted
            else -> R.string.control_status_ready
        })
        return Control.StatefulBuilder(spec.controlId, openApp())
            .setTitle(title)
            .setSubtitle(spec.macName)
            .setZone(spec.macName)
            .setStructure(getString(R.string.app_name))
            .setDeviceType(deviceType(spec))
            .setCustomIcon(Icon.createWithResource(this, R.drawable.ic_bridgey_notification))
            .setStatus(Control.STATUS_OK)
            .setStatusText(status)
            .setControlTemplate(template)
            .build()
    }

    /**
     * Emits the current controls while there is demand. A live publisher emits again whenever the
     * connection, the Mac's list or a setting changes and never completes (the system cancels it);
     * otherwise it completes after the first batch.
     */
    private inner class ControlsPublisher(
        private val scope: CoroutineScope,
        private val live: Boolean,
        private val current: () -> List<Control>,
    ) : Flow.Publisher<Control> {
        override fun subscribe(subscriber: Flow.Subscriber<in Control>) {
            val demand = AtomicLong(0)
            val cancelled = AtomicBoolean(false)
            val queue = ArrayDeque<Control>()
            var loaded = false
            var completed = false
            fun drain() = synchronized(queue) {
                while (!cancelled.get() && demand.get() > 0 && queue.isNotEmpty()) {
                    demand.decrementAndGet()
                    subscriber.onNext(queue.removeFirst())
                }
                if (!live && loaded && queue.isEmpty() && !completed && !cancelled.get()) {
                    completed = true
                    subscriber.onComplete()
                }
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                if (!live) {
                    synchronized(queue) {
                        queue.addAll(current())
                        loaded = true
                    }
                    drain()
                    return@launch
                }
                combine(app.pairing.state, app.pairing.quickActions.macActions, app.pairing.remoteFeatures,
                    app.pairing.macRinging, app.settings.state) { _, _, _, _, _ -> }.collect {
                    synchronized(queue) {
                        queue.clear()
                        queue.addAll(current())
                    }
                    drain()
                }
            }
            subscriber.onSubscribe(object : Flow.Subscription {
                override fun request(n: Long) {
                    if (n <= 0 || cancelled.get()) return
                    demand.updateAndGet { old -> if (Long.MAX_VALUE - old < n) Long.MAX_VALUE else old + n }
                    job.start()
                    drain()
                }

                override fun cancel() {
                    cancelled.set(true)
                    job.cancel()
                }
            })
        }
    }
}
