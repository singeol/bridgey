package dev.bridgey.android

import android.os.PowerManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.hypot
import kotlinx.coroutines.delay

/**
 * Remote control of the connected Mac: a touchpad with an optional keyboard, or a presentation
 * remote with a laser pointer. The session runs only while this screen is shown. It starts once when the screen opens, keeps the display on, and stops
 * when the screen closes, the app leaves the foreground or the display turns off. Coming back shows
 * Start again rather than restarting by itself.
 */
@Composable
internal fun RemoteControlRoute(
    deviceId: String,
    deviceName: String,
    controller: RemoteInputController,
    pointerSpeed: Int,
    volumeKeys: Boolean,
    modifier: Modifier = Modifier,
) {
    val session by controller.state.collectAsStateWithLifecycle()
    val shown = session.takeIf { it.deviceId == deviceId } ?: RemoteSessionState.Idle
    var autoStarted by rememberSaveable(deviceId) { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LifecycleStartEffect(deviceId) {
        if (!autoStarted) {
            autoStarted = true
            controller.start(deviceId, RemoteMode.TOUCHPAD)
        }
        onStopOrDispose {
            val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive != false
            controller.stop(if (interactive) RemoteStopReason.LEFT_SCREEN else RemoteStopReason.SCREEN_OFF)
        }
    }
    DisposableEffect(deviceId) { onDispose { controller.clear() } }
    RemoteControlScreen(
        deviceName = deviceName,
        session = shown,
        pointerSpeed = pointerSpeed,
        volumeKeys = volumeKeys,
        onStart = { controller.start(deviceId, it) },
        onStop = { controller.stop(RemoteStopReason.USER) },
        input = controller::send,
        modifier = modifier,
    )
}

/** The device a session belongs to, while it starts, runs or after it ended. */
internal val RemoteSessionState.deviceId: String?
    get() = when (this) {
        is RemoteSessionState.Starting -> deviceId
        is RemoteSessionState.Active -> deviceId
        is RemoteSessionState.Ended -> deviceId
        RemoteSessionState.Idle -> null
    }

@Composable
private fun RemoteControlScreen(
    deviceName: String,
    session: RemoteSessionState,
    pointerSpeed: Int,
    volumeKeys: Boolean,
    onStart: (RemoteMode) -> Unit,
    onStop: () -> Unit,
    input: (RemoteInput) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val active = session is RemoteSessionState.Active
    var mode by rememberSaveable { mutableStateOf(RemoteMode.TOUCHPAD) }
    var keyboard by rememberSaveable { mutableStateOf(false) }
    val batcher = remember { MotionBatcher() }
    val send by rememberUpdatedState(input)
    // Motion goes out at most 60 times a second, whatever the display's refresh rate.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(16)
            batcher.flush().forEach { send(it) }
        }
    }
    Column(
        modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteStatusCard(deviceName, session, onStop)
        ModeSwitch(mode, onMode = { mode = it })
        if (!active) {
            SessionCard(deviceName, session, { onStart(mode) }, Modifier.weight(1f).fillMaxWidth())
        } else if (mode == RemoteMode.TOUCHPAD) {
            TouchSurface(pointerSpeed, batcher, send, Modifier.weight(1f).fillMaxWidth())
            if (keyboard) {
                SpecialKeysRow(send)
                TextComposer(send)
            }
            OutlinedButton(onClick = { keyboard = !keyboard }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(if (keyboard) R.string.remote_keyboard_hide else R.string.remote_keyboard))
            }
        } else {
            PresentationPane(volumeKeys, batcher, send, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Touchpad or Slides. Either mode can send any input; the Mac only shows which one is in use. */
@Composable
private fun ModeSwitch(mode: RemoteMode, onMode: (RemoteMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteMode.entries.forEach { entry ->
            val label = stringResource(if (entry == RemoteMode.TOUCHPAD) R.string.remote_mode_touchpad else R.string.remote_mode_slides)
            if (entry == mode) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label) }
            } else {
                OutlinedButton(onClick = { onMode(entry) }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
    }
}

@Composable
private fun RemoteStatusCard(deviceName: String, session: RemoteSessionState, onStop: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val (label, color) = when (session) {
                    is RemoteSessionState.Active -> stringResource(R.string.remote_status_active) to Color(0xFF2EAD69)
                    is RemoteSessionState.Starting -> stringResource(R.string.remote_status_starting) to MaterialTheme.colorScheme.primary
                    else -> stringResource(R.string.remote_status_stopped) to MaterialTheme.colorScheme.outline
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (session is RemoteSessionState.Active || session is RemoteSessionState.Starting) {
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.remote_stop)) }
            }
        }
    }
}

/**
 * The touch surface: the gesture recogniser turns touches into moves, clicks, scrolls and drags.
 * Pending motion goes out before every click, so a click lands where the pointer was moved.
 */
@Composable
private fun TouchSurface(pointerSpeed: Int, batcher: MotionBatcher, input: (RemoteInput) -> Boolean, modifier: Modifier) {
    val density = LocalDensity.current.density
    val view = LocalView.current
    val speed by rememberUpdatedState(pointerSpeed)
    val send by rememberUpdatedState(input)
    val description = stringResource(R.string.remote_touchpad_label)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val recognizer = TouchpadGestureRecognizer(slopPx = 8.dp.toPx())
                var lastMoveAt = 0L
                fun flush() = batcher.flush().forEach { send(it) }
                fun handle(gestures: List<TouchpadGesture>, eventTime: Long) {
                    for (gesture in gestures) when (gesture) {
                        is TouchpadGesture.Move -> {
                            val dpX = gesture.dx / density
                            val dpY = gesture.dy / density
                            val elapsed = (eventTime - lastMoveAt).coerceIn(1, 100)
                            lastMoveAt = eventTime
                            val gain = pointerGain(speed, hypot(dpX, dpY) / elapsed)
                            batcher.addPointer(dpX * gain, dpY * gain)
                        }
                        is TouchpadGesture.Scroll -> batcher.addScroll(gesture.dx / density * SCROLL_GAIN, gesture.dy / density * SCROLL_GAIN)
                        is TouchpadGesture.DragMove -> {
                            val gain = pointerGain(speed, 0f)
                            batcher.addDrag(gesture.dx / density * gain, gesture.dy / density * gain)
                        }
                        is TouchpadGesture.Click -> {
                            flush()
                            send(RemoteInput.Button(gesture.button, down = true))
                            send(RemoteInput.Button(gesture.button, down = false))
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                        TouchpadGesture.DragStart -> {
                            flush()
                            send(RemoteInput.DragStart)
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        TouchpadGesture.DragEnd -> {
                            flush()
                            send(RemoteInput.DragEnd)
                        }
                    }
                }
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val deadline = recognizer.holdDeadline
                            val event = if (deadline != null) {
                                withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                }
                            } else {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                            if (event == null) {
                                handle(recognizer.onTimer(SystemClock.uptimeMillis()), SystemClock.uptimeMillis())
                                continue
                            }
                            val time = event.changes.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis()
                            val moves = HashMap<Long, Pair<Float, Float>>()
                            event.changes.forEach { change ->
                                val id = change.id.value
                                when {
                                    change.pressed && !change.previousPressed ->
                                        handle(recognizer.onDown(id, change.position.x, change.position.y, change.uptimeMillis), time)
                                    change.pressed -> moves[id] = change.position.x to change.position.y
                                }
                            }
                            if (moves.isNotEmpty()) handle(recognizer.onMove(moves), time)
                            event.changes.forEach { change ->
                                if (!change.pressed && change.previousPressed) handle(recognizer.onUp(change.id.value, change.uptimeMillis), time)
                                change.consume()
                            }
                        }
                    }
                } finally {
                    // Cancelled (the session ended or the screen closed): no button stays down.
                    handle(recognizer.onCancel(), SystemClock.uptimeMillis())
                }
            },
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            listOf(R.string.remote_hint_move, R.string.remote_hint_click, R.string.remote_hint_scroll).forEach {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class KeyChip(val key: RemoteKey, val label: Int, val text: String? = null, val shift: Boolean = false)

private val KeyChips = listOf(
    KeyChip(RemoteKey.ESCAPE, R.string.remote_key_escape),
    KeyChip(RemoteKey.TAB, R.string.remote_key_tab),
    KeyChip(RemoteKey.LEFT, R.string.remote_key_left, "←"),
    KeyChip(RemoteKey.UP, R.string.remote_key_up, "↑"),
    KeyChip(RemoteKey.DOWN, R.string.remote_key_down, "↓"),
    KeyChip(RemoteKey.RIGHT, R.string.remote_key_right, "→"),
    KeyChip(RemoteKey.BACKSPACE, R.string.remote_key_backspace, "⌫"),
    KeyChip(RemoteKey.RETURN, R.string.remote_key_return, "⏎"),
    KeyChip(RemoteKey.SPACE, R.string.remote_key_space),
    KeyChip(RemoteKey.TAB, R.string.remote_key_shift_tab, shift = true),
    KeyChip(RemoteKey.PAGE_UP, R.string.remote_key_page_up),
    KeyChip(RemoteKey.PAGE_DOWN, R.string.remote_key_page_down),
    KeyChip(RemoteKey.HOME, R.string.remote_key_home),
    KeyChip(RemoteKey.END, R.string.remote_key_end),
    KeyChip(RemoteKey.F5, R.string.remote_key_f5),
    KeyChip(RemoteKey.PREVIOUS_APP, R.string.remote_key_previous_app),
)

/** The allowlisted special keys: everything this phone can press besides typed text. */
@Composable
private fun SpecialKeysRow(send: (RemoteInput) -> Boolean) {
    val view = LocalView.current
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(KeyChips, key = { "${it.key.wire}:${it.shift}" }) { chip ->
            val label = stringResource(chip.label)
            OutlinedButton(
                onClick = { if (send(RemoteInput.Key(chip.key, chip.shift))) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.semantics { contentDescription = label },
            ) { Text(chip.text ?: label, maxLines = 1) }
        }
    }
}

/** Bounded text: up to 256 characters a message, typed on the Mac when you tap Type. */
@Composable
private fun TextComposer(send: (RemoteInput) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (isValidRemoteText(text) && send(RemoteInput.Text(text))) text = "" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = boundedRemoteText(it) },
            label = { Text(stringResource(R.string.remote_text_label)) },
            supportingText = { Text("${text.codePointCount(0, text.length)}/${RemoteInputLimits.MAX_TEXT_SCALARS}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = submit, enabled = text.isNotEmpty()) { Text(stringResource(R.string.remote_text_send)) }
    }
}

/**
 * The presentation remote: big Previous and Next, Start, Black screen and End, and a laser pad.
 * The Mac maps each action to the keys of the presentation app in front, so this phone never
 * chooses key codes.
 */
@Composable
private fun PresentationPane(volumeKeys: Boolean, batcher: MotionBatcher, send: (RemoteInput) -> Boolean, modifier: Modifier) {
    val view = LocalView.current
    val press: (PresentAction) -> Unit = { action ->
        if (send(RemoteInput.Present(action))) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
    // Volume keys change slides while this pane shows, if Settings allows it.
    DisposableEffect(volumeKeys) {
        VolumeKeyRouter.handler = if (volumeKeys) {
            { up -> press(if (up) PresentAction.PREVIOUS else PresentAction.NEXT) }
        } else {
            null
        }
        onDispose { VolumeKeyRouter.handler = null }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LaserPad(batcher, send, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { press(PresentAction.START) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.remote_present_start), maxLines = 1)
            }
            OutlinedButton(onClick = { press(PresentAction.BLACK) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.remote_present_black), maxLines = 1)
            }
            OutlinedButton(onClick = { press(PresentAction.END) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.remote_present_end), maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        if (volumeKeys) {
            Text(
                stringResource(R.string.remote_volume_keys_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { press(PresentAction.PREVIOUS) },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { Text(stringResource(R.string.remote_present_previous), style = MaterialTheme.typography.titleMedium) }
            Button(
                onClick = { press(PresentAction.NEXT) },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1.5f).fillMaxHeight(),
            ) { Text(stringResource(R.string.remote_present_next), style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/**
 * The laser: touch and hold to show a dot on the Mac where your finger is, move to move it, lift
 * to hide it. The pad stands for the whole display (16:9).
 */
@Composable
private fun LaserPad(batcher: MotionBatcher, input: (RemoteInput) -> Boolean, modifier: Modifier) {
    val send by rememberUpdatedState(input)
    val view = LocalView.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun position(at: Offset) = laserPosition(at.x, at.y, size.width.toFloat(), size.height.toFloat())
                    val (x, y) = position(down.position)
                    send(RemoteInput.Present(PresentAction.LASER_ON, x, y))
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    finger = down.position
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val (mx, my) = position(change.position)
                            batcher.moveLaser(mx, my)
                            finger = change.position
                            change.consume()
                        }
                    } finally {
                        batcher.clearLaser()
                        send(RemoteInput.Present(PresentAction.LASER_OFF))
                        finger = null
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (finger == null) {
                Text(
                    stringResource(R.string.remote_laser_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
            // The dot under the finger: moved by a transform only.
            Box(
                Modifier
                    .graphicsLayer {
                        val at = finger
                        alpha = if (at == null) 0f else 1f
                        translationX = (at?.x ?: 0f) - 10.dp.toPx()
                        translationY = (at?.y ?: 0f) - 10.dp.toPx()
                    }
                    .size(20.dp)
                    .background(Color(0xFFFF453A), CircleShape),
            )
        }
    }
}

/**
 * The presentation remote claims the volume keys while it is shown and Settings allows it.
 * MainActivity asks it before the system changes the volume.
 */
internal object VolumeKeyRouter {
    @Volatile var handler: ((volumeUp: Boolean) -> Unit)? = null
}

/** Shown instead of the controls whenever no session runs: connecting, why it stopped, and Start. */
@Composable
private fun SessionCard(deviceName: String, session: RemoteSessionState, onStart: () -> Unit, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Text(
                sessionTitle(deviceName, session),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            sessionDetail(deviceName, session)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (session !is RemoteSessionState.Starting) {
                Button(onClick = onStart) {
                    Text(stringResource(if (session is RemoteSessionState.Idle) R.string.remote_start else R.string.remote_start_again))
                }
            }
        }
    }
}

@Composable
private fun sessionTitle(deviceName: String, session: RemoteSessionState): String = when (session) {
    is RemoteSessionState.Starting -> stringResource(R.string.remote_overlay_connecting, deviceName)
    is RemoteSessionState.Ended -> when (session.reason) {
        RemoteStopReason.LOCAL_STOP -> stringResource(R.string.remote_overlay_stopped_on_mac, deviceName)
        RemoteStopReason.DISABLED -> stringResource(R.string.remote_overlay_not_allowed)
        RemoteStopReason.PERMISSION_REQUIRED -> stringResource(R.string.remote_overlay_accessibility)
        RemoteStopReason.BUSY -> stringResource(R.string.remote_overlay_busy)
        else -> stringResource(R.string.remote_overlay_stopped)
    }
    else -> stringResource(R.string.remote_overlay_ready, deviceName)
}

@Composable
private fun sessionDetail(deviceName: String, session: RemoteSessionState): String? = when (session) {
    is RemoteSessionState.Starting -> null
    is RemoteSessionState.Ended -> stringResource(
        when (session.reason) {
            RemoteStopReason.USER -> R.string.remote_reason_user
            RemoteStopReason.LEFT_SCREEN, RemoteStopReason.SCREEN_OFF -> R.string.remote_reason_left
            RemoteStopReason.LOCAL_STOP -> R.string.remote_reason_local_stop
            RemoteStopReason.TIMEOUT -> R.string.remote_reason_timeout
            RemoteStopReason.DISABLED -> R.string.remote_reason_disabled
            RemoteStopReason.PERMISSION_REQUIRED -> R.string.remote_reason_permission
            RemoteStopReason.BUSY -> R.string.remote_reason_busy
            RemoteStopReason.INVALID, RemoteStopReason.UNSUPPORTED -> R.string.remote_reason_unsupported
            RemoteStopReason.DISCONNECTED -> R.string.remote_reason_disconnected
            RemoteStopReason.NO_ANSWER -> R.string.remote_reason_no_answer
        },
        deviceName,
    )
    else -> stringResource(R.string.remote_overlay_ready_detail, deviceName)
}
