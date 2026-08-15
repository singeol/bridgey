package dev.bridgey.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgeyFeature(val key: String, val title: String) {
    CLIPBOARD("clipboard", "Clipboard"),
    FILES("files", "File transfer"),
    NOTIFICATIONS("notifications", "Notification forwarding"),
    BATTERY("battery", "Battery status"),
    FIND_DEVICE("find_device", "Find Device"),
}

data class BridgeySettingsState(
    val deviceName: String,
    val globalFeatures: Map<BridgeyFeature, Boolean>,
    val deviceFeatures: Map<String, Map<BridgeyFeature, Boolean>>,
)

internal fun effectiveFeatureEnabled(
    globalEnabled: Boolean,
    deviceEnabled: Boolean?,
): Boolean = globalEnabled && deviceEnabled != false

internal fun effectiveFeatureAvailable(localEnabled: Boolean, remoteEnabled: Boolean): Boolean =
    localEnabled && remoteEnabled

class BridgeySettings(context: Context, defaultDeviceName: String) {
    private val preferences = context.getSharedPreferences("bridgey.settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(load(defaultDeviceName))
    val state: StateFlow<BridgeySettingsState> = mutableState.asStateFlow()

    fun setDeviceName(value: String) {
        val name = value.trim().take(64).ifBlank { "Android device" }
        preferences.edit().putString(KEY_DEVICE_NAME, name).apply()
        mutableState.value = mutableState.value.copy(deviceName = name)
    }

    fun setGlobal(feature: BridgeyFeature, enabled: Boolean) {
        preferences.edit().putBoolean("global.${feature.key}", enabled).apply()
        mutableState.value = mutableState.value.copy(
            globalFeatures = mutableState.value.globalFeatures + (feature to enabled),
        )
    }

    fun setForDevice(deviceId: String, feature: BridgeyFeature, enabled: Boolean) {
        preferences.edit().putBoolean("device.$deviceId.${feature.key}", enabled).apply()
        val current = mutableState.value.deviceFeatures[deviceId].orEmpty() + (feature to enabled)
        mutableState.value = mutableState.value.copy(
            deviceFeatures = mutableState.value.deviceFeatures + (deviceId to current),
        )
    }

    fun isEnabled(feature: BridgeyFeature, deviceId: String?): Boolean {
        val snapshot = mutableState.value
        return effectiveFeatureEnabled(
            globalEnabled = snapshot.globalFeatures[feature] != false,
            deviceEnabled = deviceId?.let { snapshot.deviceFeatures[it]?.get(feature) },
        )
    }

    fun removeDevice(deviceId: String) {
        val editor = preferences.edit()
        BridgeyFeature.entries.forEach { editor.remove("device.$deviceId.${it.key}") }
        editor.apply()
        mutableState.value = mutableState.value.copy(deviceFeatures = mutableState.value.deviceFeatures - deviceId)
    }

    private fun load(defaultDeviceName: String): BridgeySettingsState {
        val globals = BridgeyFeature.entries.associateWith {
            preferences.getBoolean("global.${it.key}", true)
        }
        val perDevice = mutableMapOf<String, MutableMap<BridgeyFeature, Boolean>>()
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith("device.") || value !is Boolean) return@forEach
            BridgeyFeature.entries.firstOrNull { key.endsWith(".${it.key}") }?.let { feature ->
                val deviceId = key.removePrefix("device.").removeSuffix(".${feature.key}")
                if (deviceId.isNotBlank()) perDevice.getOrPut(deviceId, ::mutableMapOf)[feature] = value
            }
        }
        return BridgeySettingsState(
            deviceName = preferences.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName,
            globalFeatures = globals,
            deviceFeatures = perDevice,
        )
    }

    companion object {
        private const val KEY_DEVICE_NAME = "device_name"
    }
}
