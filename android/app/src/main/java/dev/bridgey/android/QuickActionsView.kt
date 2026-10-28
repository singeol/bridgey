package dev.bridgey.android

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun QuickActionsCard(actions: QuickActions, linksEnabled: Boolean, mediaEnabled: Boolean, macActionsEnabled: Boolean = false) {
    val link by actions.receivedLink.collectAsState()
    val status by actions.status.collectAsState()
    val media by actions.media.collectAsState()
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (linksEnabled) {
                Text("Web links", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    actions.sendLink(text)
                }) { Text("Send copied link to Mac") }
                link?.let { url ->
                    Text(url, maxLines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions::openLink) { Text("Open link") }
                        TextButton(onClick = actions::dismissLink) { Text("Dismiss") }
                    }
                }
            }
            if (mediaEnabled) {
                Text("Mac media · ${media.player}", style = MaterialTheme.typography.titleMedium)
                if (media.detail.isNotEmpty()) Text(media.detail)
                if (media.title.isNotEmpty()) Text(media.title, maxLines = 2)
                if (media.artist.isNotEmpty()) Text(media.artist, maxLines = 2)
                val artwork = remember(media.artwork) {
                    runCatching {
                        val bytes = Base64.decode(media.artwork ?: return@runCatching null, Base64.DEFAULT)
                        if (bytes.size > 16384) return@runCatching null
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth !in 1..128 || bounds.outHeight !in 1..128) return@runCatching null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
                artwork?.let { Image(it, contentDescription = "Album artwork", modifier = Modifier.size(96.dp)) }
                val ready = media.player in setOf("Music", "Spotify") && media.detail.isEmpty()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = ready, onClick = { actions.request(BridgeyFeature.MEDIA, "previous") }) { Text("Previous") }
                    Button(enabled = ready, onClick = { actions.request(BridgeyFeature.MEDIA, "toggle") }) { Text(if (media.playing) "Pause" else "Play") }
                    TextButton(enabled = ready, onClick = { actions.request(BridgeyFeature.MEDIA, "next") }) { Text("Next") }
                }
                if (media.duration > 0) {
                    var position by remember(media.position, media.duration) { mutableFloatStateOf(media.position.coerceAtMost(media.duration).toFloat()) }
                    Text("${position.toInt() / 60}:${(position.toInt() % 60).toString().padStart(2, '0')} / ${media.duration / 60}:${(media.duration % 60).toString().padStart(2, '0')}")
                    Slider(value = position, enabled = ready, onValueChange = { position = it },
                        valueRange = 0f..media.duration.toFloat(),
                        onValueChangeFinished = { actions.request(BridgeyFeature.MEDIA, "seek", position.toInt().toString()) })
                }
                var volume by remember(media.volume) { mutableFloatStateOf(media.volume.toFloat()) }
                Text("Player volume · ${volume.toInt()}%")
                Slider(value = volume, enabled = ready, onValueChange = { volume = it }, valueRange = 0f..100f,
                    onValueChangeFinished = { actions.request(BridgeyFeature.MEDIA, "volume", volume.toInt().toString()) })
            }
            if (macActionsEnabled) MacActionsSection(actions)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MacActionsSection(actions: QuickActions) {
    val state by actions.macActions.collectAsState()
    Text(stringResource(R.string.mac_actions_title), style = MaterialTheme.typography.titleMedium)
    val current = state
    if (current == null || current.entries.isEmpty()) {
        Text(stringResource(if (current == null) R.string.mac_actions_waiting else R.string.mac_actions_empty),
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val title: @Composable (MacActionEntry) -> String = { entry ->
        macActionTitleRes(entry.id)?.let { stringResource(it) } ?: entry.title
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        current.system.forEach { entry -> Button(onClick = { actions.runMacAction(entry.id) }) { Text(title(entry)) } }
    }
    val volume = current.volume
    if (current.entries.any { it.kind == MacActionKind.VOLUME }) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(MacActionIds.VOLUME_DOWN, MacActionIds.VOLUME_MUTE, MacActionIds.VOLUME_UP).filter(current::has).forEach { id ->
                val label = if (id == MacActionIds.VOLUME_MUTE && volume?.muted == true) R.string.mac_action_unmute
                else macActionTitleRes(id) ?: R.string.mac_action_volume
                TextButton(onClick = { actions.runMacAction(id) }) { Text(stringResource(label)) }
            }
        }
        if (volume != null && current.has(MacActionIds.VOLUME_SET)) {
            var level by remember(volume.level) { mutableFloatStateOf(volume.level.toFloat()) }
            Text(stringResource(R.string.mac_actions_volume_level, level.toInt()))
            Slider(value = level, onValueChange = { level = it }, valueRange = 0f..100f,
                onValueChangeFinished = { actions.runMacAction(MacActionIds.VOLUME_SET, level.toInt()) })
        }
    }
    if (current.apps.isNotEmpty() || current.shortcuts.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (current.apps + current.shortcuts).forEach { entry ->
                OutlinedButton(onClick = { actions.runMacAction(entry.id) }) { Text(entry.title, maxLines = 1) }
            }
        }
    }
}
