package dev.bridgey.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ClipboardTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listening: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        val app = application as BridgeyApplication
        if (!app.isPrimaryUser) { qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }; return }
        listening?.cancel()
        listening = scope.launch {
            combine(app.pairing.state, app.pairing.remoteFeatures, app.settings.state) { _, _, _ -> Unit }.collect {
                qsTile?.apply {
                    val ready = app.isBridgeyEnabled && app.pairing.state.value is PairingState.Connected &&
                        app.pairing.isFeatureAvailable(BridgeyFeature.CLIPBOARD)
                    state = if (ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    label = "Bridgey clipboard"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = if (ready) "Send to Mac" else "Open Bridgey"
                    updateTile()
                }
            }
        }
    }
    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    @Suppress("DEPRECATION")
    // PendingIntent overload exists only on API 34+. The legacy call is unreachable there.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val app = application as BridgeyApplication
            if (!app.isPrimaryUser) return@unlockAndRun
            val ready = app.isBridgeyEnabled && app.pairing.state.value is PairingState.Connected &&
                app.pairing.isFeatureAvailable(BridgeyFeature.CLIPBOARD)
            val activity = if (ready) ClipboardCaptureActivity::class.java else MainActivity::class.java
            val intent = Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 771, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else startActivityAndCollapse(intent)
        }
    }
}
