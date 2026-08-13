package dev.bridgey.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bridgey.core.discovery.DiscoveredPeer
import dev.bridgey.core.discovery.NsdDiscoveryService

private val BridgeyPurple = Color(0xFF6046B6)
private val BridgeyPurpleDark = Color(0xFFD0BCFF)
private val BridgeyLightScheme = lightColorScheme(
    primary = BridgeyPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF24105A),
    secondaryContainer = Color(0xFFE8E2F1),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF2EDF5),
    background = Color(0xFFFAF8FF),
)
private val BridgeyDarkScheme = darkColorScheme(
    primary = BridgeyPurpleDark,
    primaryContainer = Color(0xFF493092),
    secondaryContainer = Color(0xFF49454F),
)

class MainActivity : ComponentActivity() {
    private lateinit var discovery: NsdDiscoveryService
    private lateinit var pairing: PairingCoordinator
    private var notificationAccessEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser) {
            setContent { BridgeyTheme { UnsupportedProfile() } }
            return
        }
        bridgey.enableBridgey()
        pairing = bridgey.pairing
        discovery = bridgey.discovery
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        startForegroundService(Intent(this, BridgeyConnectionService::class.java))
        setContent {
            BridgeyTheme {
                BridgeyApp(
                    discovery = discovery,
                    pairing = pairing,
                    notificationAccessEnabled = notificationAccessEnabled,
                    onTurnOff = {
                        startService(Intent(this, BridgeyConnectionService::class.java).setAction(BridgeyConnectionService.ACTION_TURN_OFF))
                        finishAndRemoveTask()
                    },
                    onOpenNotificationSettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessEnabled = NotificationAccess.isEnabled(this)
    }
}

@Composable
private fun BridgeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) BridgeyDarkScheme else BridgeyLightScheme,
        content = content,
    )
}

@Composable
private fun UnsupportedProfile() = Surface(Modifier.fillMaxSize()) {
    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bridgey", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Already active in the main phone profile", style = MaterialTheme.typography.titleLarge)
        Text("Open Bridgey from the main profile to avoid showing this phone twice.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BridgeyApp(
    discovery: NsdDiscoveryService,
    pairing: PairingCoordinator,
    notificationAccessEnabled: Boolean,
    onTurnOff: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val peers by discovery.peers.collectAsStateWithLifecycle()
    val pairingState by pairing.state.collectAsStateWithLifecycle()
    val trustedIds by pairing.trustedDeviceIds.collectAsStateWithLifecycle()
    val clipboardStatus by pairing.clipboardStatus.collectAsStateWithLifecycle()
    val fileTransfers by pairing.fileTransfers.collectAsStateWithLifecycle()
    val phoneRinging by pairing.phoneRinging.collectAsStateWithLifecycle()
    val macRinging by pairing.macRinging.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bridgey", fontWeight = FontWeight.Bold)
                        Text("Your devices, together", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        DeviceScreen(
            peers = peers,
            trustedIds = trustedIds,
            pairingState = pairingState,
            clipboardStatus = clipboardStatus,
            fileTransfers = fileTransfers,
            phoneRinging = phoneRinging,
            macRinging = macRinging,
            pairing = pairing,
            notificationAccessEnabled = notificationAccessEnabled,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onTurnOff = onTurnOff,
            modifier = Modifier.padding(padding),
        )
    }

    when (val state = pairingState) {
        is PairingState.Verification -> AlertDialog(
            onDismissRequest = pairing::cancel,
            title = { Text("Verify this device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Check that this code also appears on ${state.peerName}.")
                    Text(state.code, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { Button(onClick = pairing::confirm) { Text("Codes match") } },
            dismissButton = { TextButton(onClick = pairing::cancel) { Text("Cancel") } },
        )
        is PairingState.Failed -> AlertDialog(
            onDismissRequest = pairing::cancel,
            title = { Text("Couldn’t connect") },
            text = { Text(state.message) },
            confirmButton = { Button(onClick = pairing::cancel) { Text("OK") } },
        )
        else -> Unit
    }
}

@Composable
private fun DeviceScreen(
    peers: List<DiscoveredPeer>,
    trustedIds: Set<String>,
    pairingState: PairingState,
    clipboardStatus: String?,
    fileTransfers: Map<String, FileTransferState>,
    phoneRinging: Boolean,
    macRinging: Boolean,
    pairing: PairingCoordinator,
    notificationAccessEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(pairing::sendFile) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val connected = pairingState as? PairingState.Connected
        if (connected != null) {
            item {
                ConnectedDeviceCard(
                    name = connected.peerName,
                    clipboardStatus = clipboardStatus,
                    phoneRinging = phoneRinging,
                    macRinging = macRinging,
                    onClipboard = pairing::sendClipboard,
                    onFile = { filePicker.launch(arrayOf("*/*")) },
                    onRing = pairing::findMac,
                    onStopRing = pairing::stopFinding,
                )
            }
        } else {
            item { DiscoveryHeader(pairingState, peers.isEmpty()) }
        }

        if (fileTransfers.isNotEmpty()) {
            item { SectionTitle("Transfers") }
            items(fileTransfers.values.toList(), key = { it.id }) { transfer ->
                TransferCard(transfer) { pairing.cancelFileTransfer(transfer.id) }
            }
        }

        val visiblePeers = peers.filter { it.deviceIdHint != connected?.deviceId }
        if (visiblePeers.isNotEmpty()) {
            item { SectionTitle("Nearby") }
            items(visiblePeers, key = { it.key }) { peer ->
                PeerCard(peer, peer.deviceIdHint in trustedIds, pairing)
            }
        }

        item { SectionTitle("Services") }
        item {
            ServiceCard(
                enabled = notificationAccessEnabled,
                title = "Notification forwarding",
                detail = if (notificationAccessEnabled) "Android notifications appear on your Mac" else "Permission is required to forward notifications",
                action = if (notificationAccessEnabled) "Manage" else "Enable",
                onClick = onOpenNotificationSettings,
            )
        }

        if (connected != null && connected.deviceId in trustedIds) {
            item {
                TextButton(onClick = { pairing.forget(connected.deviceId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Forget ${connected.peerName}")
                }
            }
        }
        item {
            OutlinedButton(onClick = onTurnOff, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Turn off Bridgey")
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ConnectedDeviceCard(
    name: String,
    clipboardStatus: String?,
    phoneRinging: Boolean,
    macRinging: Boolean,
    onClipboard: () -> Unit,
    onFile: () -> Unit,
    onRing: () -> Unit,
    onStopRing: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("⌘", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall) }
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).background(Color(0xFF2EAD69), CircleShape))
                        Text("Connected securely", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("Clipboard", "Copy", Modifier.weight(1f), onClipboard)
                QuickAction("File", "Send", Modifier.weight(1f), onFile)
                QuickAction(
                    if (phoneRinging || macRinging) "Stop" else "Ring",
                    if (phoneRinging) "Phone" else "Mac",
                    Modifier.weight(1f),
                    if (phoneRinging || macRinging) onStopRing else onRing,
                )
            }
            clipboardStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f)) }
        }
    }
}

@Composable
private fun QuickAction(title: String, subtitle: String, modifier: Modifier, action: () -> Unit) {
    Surface(onClick = action, modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .86f)) {
        Column(Modifier.padding(vertical = 13.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiscoveryHeader(state: PairingState, empty: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (state is PairingState.Connecting) "Connecting…" else "Looking for your Mac", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (empty) "Keep Bridgey open on both devices and use the same Wi‑Fi network." else "Choose a nearby device to connect securely.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeerCard(peer: DiscoveredPeer, trusted: Boolean, pairing: PairingCoordinator) {
    Card(onClick = { peer.host?.let { pairing.pair(it, peer.port ?: 42_458, peer.deviceNameHint) } }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.deviceNameHint, fontWeight = FontWeight.SemiBold)
                Text(if (trusted) "Paired · tap to reconnect" else "New device · tap to pair", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TransferCard(transfer: FileTransferState, cancel: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(transfer.name, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        compactTransferStatus(transfer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (transfer.active) TextButton(onClick = cancel) { Text("Cancel") }
            }
            val percent = transfer.progressPercent
            if (percent != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.weight(1f).height(6.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    Text("$percent%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            } else if (transfer.active) {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
            }
        }
    }
}

private fun compactTransferStatus(transfer: FileTransferState): String = transfer.status
    .substringAfter(": ", transfer.status)
    .replace(Regex("^(Sending|Receiving)\\s+${Regex.escape(transfer.name)}:?\\s*"), "")
    .ifBlank { if (transfer.active) "Preparing…" else transfer.status }

@Composable
private fun ServiceCard(enabled: Boolean, title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).background(if (enabled) Color(0xFF2EAD69) else MaterialTheme.colorScheme.error, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun SectionTitle(value: String) = Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
