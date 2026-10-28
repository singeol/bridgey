package dev.bridgey.android.screen

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import dev.bridgey.android.PairingCoordinator
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where screen sharing is, for the connected device card. */
data class ScreenShareState(val phase: Phase = Phase.IDLE, val macName: String? = null) {
    enum class Phase { IDLE, STARTING, SHARING }

    val active: Boolean get() = phase != Phase.IDLE
}

/**
 * Screen sharing on this phone (`screen`, docs/protocol.md): at most one stream, to the connected Mac.
 * A share starts only from a tap in Bridgey followed by Android's capture consent, every time; the consent
 * result starts [ScreenShareService], which takes the MediaProjection and runs the encoder and the stream
 * made here. Stop comes from the notification, the app, the Mac, the screen turning off, the session
 * ending or the feature being turned off.
 */
class ScreenShare(
    private val context: Context,
    private val pairing: PairingCoordinator,
) : PairingCoordinator.ScreenListener {
    private val mutableState = MutableStateFlow(ScreenShareState())
    val state: StateFlow<ScreenShareState> = mutableState.asStateFlow()

    @Volatile private var stream: ScreenStream? = null

    /** Writes screen.stop off the main thread. */
    private val stopWriter = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bridgey-screen-stop") }

    /** Set by the service: tear down once the stream has ended. */
    @Volatile var streamEnded: () -> Unit = {}

    /** Android's capture consent dialog; a new Intent for every share, as Android 14 and later require. */
    fun consentIntent(): Intent = context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

    /** The person allowed capture: the service goes to the foreground and takes the projection. */
    fun consentGranted(from: Context, resultCode: Int, data: Intent) {
        val macName = pairing.screenTargetName() ?: return
        if (state.value.active) return
        mutableState.value = ScreenShareState(ScreenShareState.Phase.STARTING, macName)
        runCatching { from.startForegroundService(ScreenShareService.startIntent(from, macName, resultCode, data)) }
            .onFailure {
                Log.w("Bridgey", "PLUGIN screen service did not start", it)
                mutableState.value = ScreenShareState()
            }
    }

    /** The service holds a projection: a new stream to the connected Mac, or null when it is no longer reachable. */
    fun begin(macName: String): ScreenStream? {
        val link = pairing.screenLink() ?: run {
            mutableState.value = ScreenShareState()
            return null
        }
        stream?.stop(ScreenStopReason.FAILED, tell = false)
        val created = ScreenStream(
            macName, link, clock = { System.nanoTime() / 1_000_000 }, log = { Log.i("Bridgey", it) },
            stopWriter = { runCatching { stopWriter.execute(it) } },
        )
        created.onStarted = {
            if (stream === created) mutableState.value = ScreenShareState(ScreenShareState.Phase.SHARING, macName)
        }
        created.onEnded = {
            if (stream === created) {
                stream = null
                mutableState.value = ScreenShareState()
                streamEnded()
            }
        }
        stream = created
        mutableState.value = ScreenShareState(ScreenShareState.Phase.STARTING, macName)
        return created
    }

    /** Stop from this phone: the notification, the app, the screen turning off, or Android ending the capture. */
    fun stop(reason: ScreenStopReason) {
        val current = stream
        if (current != null) {
            current.stop(reason, tell = true)
        } else if (state.value.phase == ScreenShareState.Phase.STARTING) {
            mutableState.value = ScreenShareState()
            streamEnded()
        }
    }

    override fun message(message: ScreenMacMessage) {
        stream?.receive(message)
    }

    override fun unavailable(reason: ScreenStopReason, tellMac: Boolean) {
        stream?.stop(reason, tell = tellMac)
    }
}
