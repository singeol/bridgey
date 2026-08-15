package dev.bridgey.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.UserManager
import android.provider.Settings
import dev.bridgey.core.discovery.LocalDiscoveryIdentity
import dev.bridgey.core.discovery.NsdDiscoveryService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BridgeyApplication : Application() {
    var isPrimaryUser: Boolean = true
        private set
    lateinit var pairing: PairingCoordinator
        private set
    lateinit var discovery: NsdDiscoveryService
        private set
    lateinit var settings: BridgeySettings
        private set
    var isBridgeyEnabled: Boolean = false
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastBatteryIntent: Intent? = null
    private var batteryReceiverRegistered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            lastBatteryIntent = intent
            publishBattery(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isPrimaryUser = getSystemService(UserManager::class.java).isSystemUser
        if (!isPrimaryUser) return
        val preferences = getSharedPreferences("bridgey", MODE_PRIVATE)
        val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
        val systemDeviceName = Settings.Global.getString(contentResolver, "device_name") ?: "Android device"
        settings = BridgeySettings(this, systemDeviceName)
        val deviceName = settings.state.value.deviceName
        pairing = PairingCoordinator(this, deviceId, deviceName, settings = settings)
        discovery = NsdDiscoveryService(this, LocalDiscoveryIdentity(deviceId, deviceName))
        applicationScope.launch {
            combine(discovery.peers, pairing.trustedDeviceIds, pairing.state) { peers, trustedIds, state ->
                Triple(peers, trustedIds, state)
            }.collect { (peers, trustedIds, state) ->
                if (isBridgeyEnabled && state is PairingState.Idle) {
                    peers.firstOrNull {
                        val peerId = it.deviceIdHint
                        peerId != null && peerId in trustedIds && deviceId < peerId
                    }?.let { peer ->
                        peer.host?.let { pairing.pair(it, peer.port ?: 42_458, peer.deviceNameHint) }
                    }
                } else if (state is PairingState.Connected) {
                    lastBatteryIntent?.let(::publishBattery)
                }
            }
        }
        applicationScope.launch {
            settings.state.collect { lastBatteryIntent?.let(::publishBattery) }
        }
        applicationScope.launch {
            pairing.remoteFeatures.collect { features ->
                if (features[BridgeyFeature.BATTERY] != false) lastBatteryIntent?.let(::publishBattery)
            }
        }
        if (preferences.getBoolean(PREFERENCE_ENABLED, true)) enableBridgey()
    }

    fun enableBridgey() {
        if (!isPrimaryUser) return
        getSharedPreferences("bridgey", MODE_PRIVATE).edit().putBoolean(PREFERENCE_ENABLED, true).apply()
        if (isBridgeyEnabled) return
        isBridgeyEnabled = true
        pairing.start()
        discovery.start()
        if (!batteryReceiverRegistered) {
            lastBatteryIntent = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
        }
        lastBatteryIntent?.let(::publishBattery)
    }

    fun disableBridgey() {
        if (!isPrimaryUser) return
        getSharedPreferences("bridgey", MODE_PRIVATE).edit().putBoolean(PREFERENCE_ENABLED, false).commit()
        if (!isBridgeyEnabled) return
        isBridgeyEnabled = false
        if (batteryReceiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        discovery.stop()
        pairing.pause()
    }

    fun updateDeviceName(value: String) {
        settings.setDeviceName(value)
        val name = settings.state.value.deviceName
        pairing.updateDeviceName(name)
        discovery.updateDeviceName(name)
    }

    private fun publishBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        pairing.sendBattery(percent, charging)
    }

    companion object {
        private const val PREFERENCE_ENABLED = "bridgey_enabled"
    }
}
