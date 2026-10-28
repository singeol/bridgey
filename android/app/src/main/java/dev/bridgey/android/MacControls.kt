package dev.bridgey.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Device Controls and the home-screen widget as plain data, so the mapping is testable without the
// framework. A control runs only through QuickActions.runMacAction, sendPing or findMac, so the
// Mac still checks every request against the list it published.

/** One paired Mac as controls see it. [actions] is the last list it sent, kept while offline. */
internal data class ControlMac(
    val deviceId: String,
    val name: String,
    val connected: Boolean,
    /** The list came from the Mac in the current session and Mac actions is available. */
    val actionsLive: Boolean,
    val actions: MacActionsState?,
    val pingAvailable: Boolean,
    val findAvailable: Boolean,
    val ringing: Boolean,
)

internal enum class ControlKind { ACTION, VOLUME, MUTE, PING, FIND }

internal enum class ControlState { READY, OFFLINE, UNAVAILABLE }

internal data class ControlSpec(
    val controlId: String,
    val deviceId: String,
    val kind: ControlKind,
    /** The action identifier for ACTION, VOLUME and MUTE. */
    val actionId: String?,
    /** The Mac's title for apps and Shortcuts; null for fixed controls, which the phone translates. */
    val customTitle: String?,
    val macName: String,
    val state: ControlState,
    val volume: MacVolume? = null,
    val checked: Boolean = false,
)

/** `<deviceId>|<element>`. Device IDs are UUIDs, so `|` never appears in them. */
internal fun controlId(deviceId: String, element: String) = "$deviceId|$element"

/** Every control for every paired Mac in a stable order: Mac actions first, then Ping and Find. */
internal fun controlSpecs(macs: List<ControlMac>): List<ControlSpec> = macs.flatMap { mac ->
    fun state(available: Boolean) = when {
        !mac.connected -> ControlState.OFFLINE
        !available -> ControlState.UNAVAILABLE
        else -> ControlState.READY
    }
    val actionsState = state(mac.actionsLive)
    val actions = mac.actions
    buildList {
        if (actions != null) {
            actions.system.forEach { entry ->
                add(ControlSpec(controlId(mac.deviceId, "action:${entry.id}"), mac.deviceId, ControlKind.ACTION, entry.id, null, mac.name, actionsState))
            }
            if (actions.has(MacActionIds.VOLUME_SET)) {
                add(ControlSpec(controlId(mac.deviceId, "volume"), mac.deviceId, ControlKind.VOLUME, MacActionIds.VOLUME_SET, null,
                    mac.name, actionsState, actions.volume))
            }
            if (actions.has(MacActionIds.VOLUME_MUTE)) {
                add(ControlSpec(controlId(mac.deviceId, "action:${MacActionIds.VOLUME_MUTE}"), mac.deviceId, ControlKind.MUTE,
                    MacActionIds.VOLUME_MUTE, null, mac.name, actionsState, actions.volume, actions.volume?.muted == true))
            }
            (actions.apps + actions.shortcuts).forEach { entry ->
                add(ControlSpec(controlId(mac.deviceId, "action:${entry.id}"), mac.deviceId, ControlKind.ACTION, entry.id, entry.title,
                    mac.name, actionsState))
            }
        }
        add(ControlSpec(controlId(mac.deviceId, "ping"), mac.deviceId, ControlKind.PING, null, null, mac.name, state(mac.pingAvailable)))
        add(ControlSpec(controlId(mac.deviceId, "find"), mac.deviceId, ControlKind.FIND, null, null, mac.name,
            state(mac.findAvailable), checked = mac.connected && mac.ringing))
    }
}

internal sealed interface ControlCommand {
    data class Run(val actionId: String, val value: Int? = null) : ControlCommand
    data object Ping : ControlCommand
    data class Find(val start: Boolean) : ControlCommand
}

/** The command for a tap, or null when the control cannot run now or the input does not fit. */
internal fun controlCommand(spec: ControlSpec, floatValue: Float? = null, booleanValue: Boolean? = null): ControlCommand? {
    if (spec.state != ControlState.READY) return null
    return when (spec.kind) {
        ControlKind.ACTION -> spec.actionId?.let { ControlCommand.Run(it) }
        ControlKind.VOLUME -> floatValue?.takeIf { !it.isNaN() }?.let { ControlCommand.Run(MacActionIds.VOLUME_SET, it.toInt().coerceIn(0, 100)) }
        ControlKind.MUTE -> ControlCommand.Run(MacActionIds.VOLUME_MUTE)
        ControlKind.PING -> ControlCommand.Ping
        ControlKind.FIND -> ControlCommand.Find(booleanValue ?: !spec.checked)
    }
}

internal fun runControlCommand(pairing: PairingCoordinator, command: ControlCommand) {
    when (command) {
        is ControlCommand.Run -> pairing.quickActions.runMacAction(command.actionId, command.value)
        ControlCommand.Ping -> pairing.sendPing()
        is ControlCommand.Find -> if (command.start) pairing.findMac() else pairing.stopFinding()
    }
}

/** The last list each Mac sent, so controls and the widget keep their entries while it is offline. */
internal class SavedMacActions(context: Context) {
    private val preferences = context.getSharedPreferences("bridgey.mac_actions", Context.MODE_PRIVATE)

    fun load(deviceId: String): MacActionsState? = preferences.getString(KEY_PREFIX + deviceId, null)?.let {
        runCatching { parseMacActionsState(JSONObject(it)) }.getOrNull()
    }

    fun save(deviceId: String, state: MacActionsState?) {
        val editor = preferences.edit()
        if (state == null) editor.remove(KEY_PREFIX + deviceId) else editor.putString(KEY_PREFIX + deviceId, state.toJson().toString())
        editor.apply()
    }

    fun retainOnly(deviceIds: Set<String>) {
        val stale = preferences.all.keys.filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in deviceIds }
        if (stale.isNotEmpty()) preferences.edit().apply { stale.forEach(::remove) }.apply()
    }

    private fun MacActionsState.toJson() = JSONObject().put("version", 1).put("actions", JSONArray().apply {
        entries.forEach { put(JSONObject().put("id", it.id).put("title", it.title)) }
    }).apply { volume?.let { put("volume", JSONObject().put("level", it.level).put("muted", it.muted)) } }

    private companion object {
        const val KEY_PREFIX = "state."
    }
}

/** Every trusted Mac as controls and the widget see it now. */
internal fun controlMacs(app: BridgeyApplication): List<ControlMac> {
    val pairing = app.pairing
    val connectedId = (pairing.state.value as? PairingState.Connected)?.deviceId?.takeIf { app.isBridgeyEnabled }
    val live = pairing.quickActions.macActions.value
    return pairing.trustedDevices.value.map { device ->
        val connected = device.id == connectedId
        val actionsLive = connected && live != null && pairing.isFeatureAvailable(BridgeyFeature.MAC_ACTIONS)
        ControlMac(
            deviceId = device.id,
            name = device.name,
            connected = connected,
            actionsLive = actionsLive,
            actions = if (actionsLive) live else app.savedMacActions.load(device.id),
            pingAvailable = connected && pairing.isFeatureAvailable(BridgeyFeature.PING),
            findAvailable = connected && pairing.isFeatureAvailable(BridgeyFeature.FIND_DEVICE),
            ringing = connected && pairing.macRinging.value,
        )
    }
}
