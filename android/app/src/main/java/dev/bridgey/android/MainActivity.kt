package dev.bridgey.android

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bridgey.core.discovery.DiscoveredPeer
import dev.bridgey.core.discovery.NsdDiscoveryService
import java.io.File

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

private enum class PermissionPrompt {
    BridgeyNotifications,
    NotificationForwarding,
    DirectCalls,
}

private data class SharedContent(
    val text: String? = null,
    val files: List<Uri> = emptyList(),
)

class MainActivity : ComponentActivity() {
    private lateinit var discovery: NsdDiscoveryService
    private lateinit var pairing: PairingCoordinator
    private lateinit var bridgeySettings: BridgeySettings
    private var notificationAccessEnabled by mutableStateOf(false)
    private var appNotificationsEnabled by mutableStateOf(false)
    private var sharedContent by mutableStateOf<SharedContent?>(null)
    private var showOnboarding by mutableStateOf(false)
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissionState()
    }
    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        bridgeySettings.setDirectCallsEnabled(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser) {
            setContent { BridgeyTheme { UnsupportedProfile() } }
            return
        }
        bridgey.enableBridgey()
        showOnboarding = !getSharedPreferences("bridgey_onboarding", MODE_PRIVATE)
            .getBoolean("completed", false)
        pairing = bridgey.pairing
        discovery = bridgey.discovery
        bridgeySettings = bridgey.settings
        sharedContent = intent.toSharedContent()
        startForegroundService(Intent(this, BridgeyConnectionService::class.java))
        setContent {
            BridgeyTheme {
                BridgeyApp(
                    discovery = discovery,
                    pairing = pairing,
                    settings = bridgey.settings,
                    onDeviceNameChanged = bridgey::updateDeviceName,
                    appNotificationsEnabled = appNotificationsEnabled,
                    notificationAccessEnabled = notificationAccessEnabled,
                    onTurnOff = {
                        startService(Intent(this, BridgeyConnectionService::class.java).setAction(BridgeyConnectionService.ACTION_TURN_OFF))
                        finishAndRemoveTask()
                    },
                    onRequestAppNotifications = ::requestAppNotifications,
                    onOpenAppNotificationSettings = ::openAppNotificationSettings,
                    onOpenNotificationAccessSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestDirectCalls = ::requestDirectCalls,
                    onExportDiagnostics = ::exportDiagnostics,
                    sharedContent = sharedContent,
                    onSharedContentHandled = ::clearSharedContent,
                    showOnboarding = showOnboarding,
                    onOnboardingComplete = {
                        getSharedPreferences("bridgey_onboarding", MODE_PRIVATE)
                            .edit().putBoolean("completed", true).apply()
                        showOnboarding = false
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedContent = intent.toSharedContent()
    }

    private fun Intent.toSharedContent(): SharedContent? {
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

        val streams = when (action) {
            Intent.ACTION_SEND_MULTIPLE -> streamUris()
            else -> listOfNotNull(streamUri())
        }.distinct()
        val sharedText = getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() && streams.isEmpty() }
        return SharedContent(text = sharedText, files = streams)
            .takeIf { it.text != null || it.files.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun refreshPermissionState() {
        appNotificationsEnabled = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        notificationAccessEnabled = NotificationAccess.isEnabled(this)
        if (
            ::bridgeySettings.isInitialized && bridgeySettings.state.value.directCallsEnabled &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            bridgeySettings.setDirectCallsEnabled(false)
        }
    }

    private fun clearSharedContent() {
        sharedContent = null
        // Prevent a handled share intent from being replayed after configuration changes.
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun requestAppNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings()
        }
    }

    private fun requestDirectCalls() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            bridgeySettings.setDirectCallsEnabled(true)
        } else {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun openAppNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun exportDiagnostics() {
        val report = pairing.diagnosticsReport()
        val diagnosticsDirectory = File(cacheDir, "diagnostics")
        val reportFile = runCatching {
            check(diagnosticsDirectory.exists() || diagnosticsDirectory.mkdirs())
            File(diagnosticsDirectory, "Bridgey-Diagnostics.json").apply {
                writeText(report, Charsets.UTF_8)
            }
        }.getOrElse {
            android.widget.Toast.makeText(this, "Could not create diagnostics file", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val reportUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            reportFile,
        )
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "Bridgey diagnostics")
                putExtra(Intent.EXTRA_STREAM, reportUri)
                clipData = ClipData.newUri(contentResolver, "Bridgey diagnostics", reportUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Export Bridgey diagnostics",
        ))
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
    settings: BridgeySettings,
    onDeviceNameChanged: (String) -> Unit,
    appNotificationsEnabled: Boolean,
    notificationAccessEnabled: Boolean,
    onTurnOff: () -> Unit,
    onRequestAppNotifications: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onRequestDirectCalls: () -> Unit,
    onExportDiagnostics: () -> Unit,
    sharedContent: SharedContent?,
    onSharedContentHandled: () -> Unit,
    showOnboarding: Boolean,
    onOnboardingComplete: () -> Unit,
) {
    val peers by discovery.peers.collectAsStateWithLifecycle()
    val pairingState by pairing.state.collectAsStateWithLifecycle()
    val trustedIds by pairing.trustedDeviceIds.collectAsStateWithLifecycle()
    val clipboardStatus by pairing.clipboardStatus.collectAsStateWithLifecycle()
    val fileTransfers by pairing.fileTransfers.collectAsStateWithLifecycle()
    val phoneRinging by pairing.phoneRinging.collectAsStateWithLifecycle()
    val macRinging by pairing.macRinging.collectAsStateWithLifecycle()
    val trustedDevices by pairing.trustedDevices.collectAsStateWithLifecycle()
    val remoteFeatures by pairing.remoteFeatures.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    var permissionPrompt by remember { mutableStateOf<PermissionPrompt?>(null) }
    var showingSettings by remember { mutableStateOf(false) }

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
                actions = {
                    TextButton(onClick = { showingSettings = !showingSettings }) {
                        Text(if (showingSettings) "Done" else "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (showingSettings) {
            SettingsScreen(
                state = settingsState,
                trustedDevices = trustedDevices,
                connectedDeviceId = (pairingState as? PairingState.Connected)?.deviceId,
                remoteFeatures = remoteFeatures,
                onDeviceNameChanged = onDeviceNameChanged,
                onGlobalFeatureChanged = { feature, enabled ->
                    settings.setGlobal(feature, enabled)
                    if (feature == BridgeyFeature.FIND_DEVICE && !enabled) pairing.stopFinding()
                    if (feature == BridgeyFeature.CALLS && !enabled) settings.setDirectCallsEnabled(false)
                },
                onDeviceFeatureChanged = { deviceId, feature, enabled ->
                    settings.setForDevice(deviceId, feature, enabled)
                    if (
                        feature == BridgeyFeature.FIND_DEVICE && !enabled &&
                        (pairingState as? PairingState.Connected)?.deviceId == deviceId
                    ) pairing.stopFinding()
                },
                onNotificationApplicationChanged = { packageName, enabled ->
                    settings.setNotificationApplicationEnabled(packageName, enabled)
                    BridgeyNotificationListenerService.filterChanged(packageName, enabled)
                },
                onDirectCallsChanged = { enabled ->
                    if (enabled) permissionPrompt = PermissionPrompt.DirectCalls
                    else settings.setDirectCallsEnabled(false)
                },
                onForget = pairing::forget,
                onExportDiagnostics = onExportDiagnostics,
                modifier = Modifier.padding(padding),
            )
        } else {
            DeviceScreen(
                peers = peers,
                trustedIds = trustedIds,
                pairingState = pairingState,
                clipboardStatus = clipboardStatus,
                fileTransfers = fileTransfers,
                phoneRinging = phoneRinging,
                macRinging = macRinging,
                enabledFeatures = BridgeyFeature.entries.associateWith { feature ->
                    settings.isEnabled(feature, (pairingState as? PairingState.Connected)?.deviceId) &&
                        remoteFeatures[feature] != false
                },
                pairing = pairing,
                appNotificationsEnabled = appNotificationsEnabled,
                notificationAccessEnabled = notificationAccessEnabled,
                onAppNotifications = {
                    if (appNotificationsEnabled) onOpenAppNotificationSettings()
                    else permissionPrompt = PermissionPrompt.BridgeyNotifications
                },
                onNotificationForwarding = {
                    if (notificationAccessEnabled) onOpenNotificationAccessSettings()
                    else permissionPrompt = PermissionPrompt.NotificationForwarding
                },
                onTurnOff = onTurnOff,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.welcome_title)) },
            text = { Text(stringResource(R.string.welcome_body)) },
            confirmButton = {
                Button(onClick = onOnboardingComplete) {
                    Text(stringResource(R.string.welcome_continue))
                }
            },
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

    permissionPrompt?.let { prompt ->
        val isForwarding = prompt == PermissionPrompt.NotificationForwarding
        val isDirectCalls = prompt == PermissionPrompt.DirectCalls
        AlertDialog(
            onDismissRequest = { permissionPrompt = null },
            title = {
                Text(
                    when {
                        isForwarding -> "Forward Android notifications?"
                        isDirectCalls -> "Allow direct calls from Mac?"
                        else -> "Allow Bridgey notifications?"
                    },
                )
            },
            text = {
                Text(
                    when {
                        isForwarding -> "This optional access lets Bridgey read notification titles and text and send them only to your paired Mac over the encrypted local connection."
                        isDirectCalls -> "This optional Phone permission lets an authenticated paired Mac start a call immediately. Leave it off to receive a notification that opens the Android dialer for confirmation."
                        else -> "Bridgey uses notifications to keep the connection visible and show file transfers, received files, and Find Device. It does not use notifications for advertising."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    permissionPrompt = null
                    when {
                        isForwarding -> onOpenNotificationAccessSettings()
                        isDirectCalls -> onRequestDirectCalls()
                        else -> onRequestAppNotifications()
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { permissionPrompt = null }) { Text("Not now") } },
        )
    }

    sharedContent?.let { content ->
        val isConnected = pairingState is PairingState.Connected
        val requiredFeature = if (content.files.isNotEmpty()) BridgeyFeature.FILES else BridgeyFeature.CLIPBOARD
        val featureAvailable = settings.isEnabled(
            requiredFeature,
            (pairingState as? PairingState.Connected)?.deviceId,
        ) && remoteFeatures[requiredFeature] != false
        val summary = when {
            content.files.size == 1 -> "Send the selected file to your Mac?"
            content.files.isNotEmpty() -> "Send ${content.files.size} selected files to your Mac?"
            else -> "Send the shared text to your Mac?"
        }
        AlertDialog(
            onDismissRequest = onSharedContentHandled,
            title = { Text("Share with Bridgey") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary)
                    when {
                        !isConnected -> Text(
                            "Waiting for a trusted Mac to reconnect.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        !featureAvailable -> Text(
                            "This feature is turned off on one of your devices.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = isConnected && featureAvailable,
                    onClick = {
                        content.text?.let { pairing.sendText(it) }
                        content.files.forEach(pairing::sendFile)
                        onSharedContentHandled()
                    },
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = onSharedContentHandled) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsScreen(
    state: BridgeySettingsState,
    trustedDevices: List<TrustedDevice>,
    connectedDeviceId: String?,
    remoteFeatures: Map<BridgeyFeature, Boolean>,
    onDeviceNameChanged: (String) -> Unit,
    onGlobalFeatureChanged: (BridgeyFeature, Boolean) -> Unit,
    onDeviceFeatureChanged: (String, BridgeyFeature, Boolean) -> Unit,
    onNotificationApplicationChanged: (String, Boolean) -> Unit,
    onDirectCallsChanged: (Boolean) -> Unit,
    onForget: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editedName by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("This phone") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it.take(64) },
                        label = { Text("Device name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onDeviceNameChanged(editedName) },
                        enabled = editedName.trim().isNotEmpty() && editedName.trim() != state.deviceName,
                    ) { Text("Save name") }
                }
            }
        }

        item { SectionTitle("Features on this phone") }
        item {
            Text(
                "These switches control what this phone shares with every paired device. Changes appear on a connected device immediately.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(BridgeyFeature.entries, key = { it.key }) { feature ->
            FeatureToggle(
                title = feature.title,
                enabled = state.globalFeatures[feature] != false,
                onChanged = { onGlobalFeatureChanged(feature, it) },
            )
        }

        if (state.globalFeatures[BridgeyFeature.CALLS] != false) {
            item {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Start calls without confirmation", fontWeight = FontWeight.Medium)
                            Text(
                                "Optional. When off, Mac call requests open the Android dialer from a notification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.directCallsEnabled,
                            onCheckedChange = onDirectCallsChanged,
                        )
                    }
                }
            }
        }

        if (state.globalFeatures[BridgeyFeature.NOTIFICATIONS] != false) {
            item { SectionTitle("Notification applications") }
            item {
                Text(
                    if (state.notificationApplications.isEmpty()) {
                        "Applications appear here after they post a notification."
                    } else {
                        "Choose which applications may forward notifications to your paired Mac."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(
                state.notificationApplications.entries.sortedBy { "${it.value.lowercase()}\u0000${it.key}" },
                key = { "notification.${it.key}" },
            ) { application ->
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(application.value, fontWeight = FontWeight.Medium)
                            Text(
                                application.key,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = application.key !in state.disabledNotificationPackages,
                            onCheckedChange = { onNotificationApplicationChanged(application.key, it) },
                        )
                    }
                }
            }
        }

        item { SectionTitle("Paired devices") }
        if (trustedDevices.isEmpty()) {
            item { Text("No paired devices", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(trustedDevices, key = { it.id }) { device ->
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(device.id.take(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Choose what this phone may use with this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BridgeyFeature.entries.forEach { feature ->
                        val globallyEnabled = state.globalFeatures[feature] != false
                        val disabledRemotely = device.id == connectedDeviceId && remoteFeatures[feature] == false
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(feature.title, color = if (globallyEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (disabledRemotely) {
                                    Text("Off on ${device.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                            Switch(
                                checked = globallyEnabled && state.deviceFeatures[device.id]?.get(feature) != false,
                                enabled = globallyEnabled,
                                onCheckedChange = { onDeviceFeatureChanged(device.id, feature, it) },
                            )
                        }
                    }
                    TextButton(onClick = { onForget(device.id) }) { Text("Forget device", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { SectionTitle("Diagnostics") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export a bounded event log without clipboard text, notification content, file names, addresses, or device identifiers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onExportDiagnostics) { Text("Share diagnostics file") }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun FeatureToggle(title: String, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(checked = enabled, onCheckedChange = onChanged)
        }
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
    enabledFeatures: Map<BridgeyFeature, Boolean>,
    pairing: PairingCoordinator,
    appNotificationsEnabled: Boolean,
    notificationAccessEnabled: Boolean,
    onAppNotifications: () -> Unit,
    onNotificationForwarding: () -> Unit,
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
                    clipboardEnabled = enabledFeatures[BridgeyFeature.CLIPBOARD] != false,
                    filesEnabled = enabledFeatures[BridgeyFeature.FILES] != false,
                    findEnabled = enabledFeatures[BridgeyFeature.FIND_DEVICE] != false,
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
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Recent transfers")
                    Spacer(Modifier.weight(1f))
                    if (fileTransfers.values.any { !it.active }) {
                        TextButton(onClick = pairing::clearTransferHistory) { Text("Clear") }
                    }
                }
            }
            items(fileTransfers.values.sortedByDescending(FileTransferState::startedAtMillis), key = { it.id }) { transfer ->
                TransferCard(
                    transfer = transfer,
                    cancel = { pairing.cancelFileTransfer(transfer.id) },
                    retry = { pairing.retryFileTransfer(transfer.id) },
                )
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
                enabled = appNotificationsEnabled,
                title = "Bridgey notifications",
                detail = if (appNotificationsEnabled) "Connection, transfer, and Find Device controls are visible" else "Optional, but recommended for background controls",
                action = if (appNotificationsEnabled) "Manage" else "Enable",
                onClick = onAppNotifications,
            )
        }
        if (enabledFeatures[BridgeyFeature.NOTIFICATIONS] != false) {
            item {
                ServiceCard(
                    enabled = notificationAccessEnabled,
                    title = "Notification forwarding",
                    detail = if (notificationAccessEnabled) "Android notifications appear on your Mac" else "Permission is required to forward notifications",
                    action = if (notificationAccessEnabled) "Manage" else "Enable",
                    onClick = onNotificationForwarding,
                )
            }
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
    clipboardEnabled: Boolean,
    filesEnabled: Boolean,
    findEnabled: Boolean,
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
                if (clipboardEnabled) QuickAction("Clipboard", "Copy", Modifier.weight(1f), onClipboard)
                if (filesEnabled) QuickAction("File", "Send", Modifier.weight(1f), onFile)
                if (findEnabled) {
                    QuickAction(
                        if (phoneRinging || macRinging) "Stop" else "Ring",
                        if (phoneRinging) "Phone" else "Mac",
                        Modifier.weight(1f),
                        if (phoneRinging || macRinging) onStopRing else onRing,
                    )
                }
            }
            if (!clipboardEnabled && !filesEnabled && !findEnabled) {
                Text("Quick actions are turned off in Settings on one of your devices.", style = MaterialTheme.typography.bodySmall)
            }
            clipboardStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f)) }
        }
    }
}

@Composable
private fun QuickAction(title: String, subtitle: String, modifier: Modifier, action: () -> Unit) {
    Surface(
        onClick = action,
        modifier = modifier.semantics { contentDescription = "$title, $subtitle" },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .86f),
    ) {
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
private fun TransferCard(transfer: FileTransferState, cancel: () -> Unit, retry: () -> Unit) {
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
                when {
                    transfer.active -> TextButton(onClick = cancel) { Text("Cancel") }
                    transfer.retryable -> TextButton(onClick = retry) { Text("Retry") }
                }
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
            Box(Modifier.size(10.dp).background(if (enabled) Color(0xFF2EAD69) else MaterialTheme.colorScheme.outline, CircleShape))
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
