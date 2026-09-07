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
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun QuickActionsCard(actions: QuickActions, linksEnabled: Boolean, mediaEnabled: Boolean) {
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
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
