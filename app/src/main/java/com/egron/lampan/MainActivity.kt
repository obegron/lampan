package com.egron.lampan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.painterResource
import com.egron.lampan.raop.AirPlayDevice
import com.egron.lampan.raop.AirPlay2Client
import com.egron.lampan.raop.AirPlayDiscovery
import com.egron.lampan.raop.AirPlayProtocol
import com.egron.lampan.raop.AirPlayReceiverProbe
import com.egron.lampan.ui.theme.LampanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkTheme by rememberSaveable { mutableStateOf(true) }
            LampanTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = { isDarkTheme = !isDarkTheme }
                    )
                }
            }
        }
    }
}

// Helper to get current WiFi SSID
private fun getCurrentSsid(context: Context): String {
    val rawSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val transportInfo = capabilities?.transportInfo
        if (transportInfo is WifiInfo) {
            transportInfo.ssid
        } else {
            "<unknown ssid>"
        }
    } else {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        info.ssid
    }
    // ssid returns with quotes, e.g. "MyNetwork", or <unknown ssid>
    return rawSsid.replace("\"", "")
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun requestMediaProjection(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    launcher.launch(mgr.createScreenCaptureIntent())
}

private enum class ReceiverReachability {
    NONE,
    CHECKING,
    REACHABLE,
    VERIFIED,
    DIFFERENT_RECEIVER,
    UNREACHABLE,
}

private const val REACHABILITY_REFRESH_MS = 10_000L
private val RECEIVER_AVAILABLE_COLOR = Color(0xFF4CAF50)

private fun aggregateReceiverReachability(
    addresses: Set<String>,
    statuses: Map<String, ReceiverReachability>,
): ReceiverReachability {
    if (addresses.isEmpty()) return ReceiverReachability.NONE
    val selectedStatuses = addresses.map { statuses[it] ?: ReceiverReachability.CHECKING }
    return when {
        ReceiverReachability.DIFFERENT_RECEIVER in selectedStatuses ->
            ReceiverReachability.DIFFERENT_RECEIVER
        ReceiverReachability.UNREACHABLE in selectedStatuses -> ReceiverReachability.UNREACHABLE
        ReceiverReachability.CHECKING in selectedStatuses -> ReceiverReachability.CHECKING
        selectedStatuses.all { it == ReceiverReachability.VERIFIED } ->
            ReceiverReachability.VERIFIED
        else -> ReceiverReachability.REACHABLE
    }
}

private fun normalizedReceiverAddress(rawAddress: String): String {
    val (host, port) = parseIpAndPort(rawAddress)
    return if (host.isEmpty()) "" else "$host:$port"
}

private fun preferredReceiverAddress(device: AirPlayDevice): String {
    val port = requireNotNull(device.portFor(device.preferredProtocol))
    return "${device.ip}:$port"
}

@Composable
fun MainScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val airPlay2CredentialStore = remember { AirPlay2CredentialStore(context) }
    val uiScope = rememberCoroutineScope()
    val currentSsid = remember { getCurrentSsid(context) }

    val initialIpAddress = remember {
        prefsManager.getIpForSsid(currentSsid).ifEmpty {
            prefsManager.getLastUsedIp()
        }
    }
    val initialDevice = remember(initialIpAddress) {
        val receiver = normalizedReceiverAddress(initialIpAddress)
        val remembered = receiver
            .takeIf { it.isNotEmpty() }
            ?.let(prefsManager::getAirPlayCapabilities)
        remembered ?: receiver.takeIf { it.isNotEmpty() }?.let {
            val (host, port) = parseIpAndPort(initialIpAddress)
            if (
                airPlay2CredentialStore.contains(it) ||
                airPlay2CredentialStore.containsPassword(it)
            ) {
                AirPlayDevice(
                    name = host,
                    ip = host,
                    airPlay2Port = port,
                    airPlay2RequiresPassword = true,
                )
            } else {
                null
            }
        }
    }
    var ipAddress by remember { mutableStateOf(initialIpAddress) }

    // Function to update IP and save to prefs
    val updateIpAddress = { newIp: String ->
        ipAddress = newIp
        if (currentSsid.isNotEmpty() && currentSsid != "<unknown ssid>") {
            prefsManager.saveIpForSsid(currentSsid, newIp)
        }
        prefsManager.saveLastUsedIp(newIp)
    }

    val focusManager = LocalFocusManager.current
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var receiverProtocol by rememberSaveable {
        mutableStateOf(initialDevice?.preferredProtocol ?: AirPlayProtocol.AIRPLAY_1)
    }
    var selectedDevice by remember { mutableStateOf(initialDevice) }
    var knownDevices by remember {
        mutableStateOf(
            (prefsManager.getKnownAirPlayDevices() + listOfNotNull(initialDevice))
                .distinctBy { it.ip }
                .sortedBy { it.name.lowercase() },
        )
    }
    var selectedReceiverAddresses by remember {
        mutableStateOf(initialDevice?.let(::preferredReceiverAddress)?.let(::setOf).orEmpty())
    }
    var pendingReceiverAddresses by remember { mutableStateOf(emptySet<String>()) }
    var isAddingDevice by remember { mutableStateOf(initialDevice == null) }
    var receiverReachabilityByAddress by remember {
        mutableStateOf(emptyMap<String, ReceiverReachability>())
    }
    var airPlay2Password by remember { mutableStateOf("") }
    var airPlay2PasswordDrafts by remember {
        mutableStateOf(emptyMap<String, String>())
    }
    var hasSavedAirPlay2Pairing by remember { mutableStateOf(false) }
    var hasSavedAirPlay2Password by remember { mutableStateOf(false) }
    var confirmForgetAirPlay2Pairing by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1.0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Use a list for logs
    var statusLogs by remember { mutableStateOf(listOf("Ready.")) }
    val listState = rememberLazyListState()
    val appendLog: (String) -> Unit = { message ->
        uiScope.launch {
            statusLogs = (statusLogs + message).takeLast(100)
        }
    }

    // Discovery State
    var isScanning by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf(emptyList<AirPlayDevice>()) }
    val discovery = remember { AirPlayDiscovery(context) }

    val rememberCurrentCapabilities: () -> Unit = {
        val (host, port) = parseIpAndPort(ipAddress)
        if (host.isNotEmpty()) {
            val current = selectedDevice
            val remembered = AirPlayDevice(
                name = current?.name ?: host,
                ip = host,
                airPlay1Port = current?.airPlay1Port
                    ?: port.takeIf { receiverProtocol == AirPlayProtocol.AIRPLAY_1 },
                airPlay2Port = current?.airPlay2Port
                    ?: port.takeIf { receiverProtocol == AirPlayProtocol.AIRPLAY_2 },
                airPlay2RequiresPassword = current?.airPlay2RequiresPassword
                    ?: true.takeIf { receiverProtocol == AirPlayProtocol.AIRPLAY_2 },
                receiverId = current?.receiverId,
                protocolPreference = current?.protocolPreference,
            )
            prefsManager.saveAirPlayCapabilities(remembered)
            selectedDevice = remembered
            knownDevices = prefsManager.getKnownAirPlayDevices()
            if (selectedReceiverAddresses.isEmpty()) {
                selectedReceiverAddresses = setOf(preferredReceiverAddress(remembered))
            }
            isAddingDevice = false
        }
    }

    LaunchedEffect(ipAddress) {
        val (host, port) = parseIpAndPort(ipAddress)
        if (host.isEmpty()) {
            hasSavedAirPlay2Pairing = false
            hasSavedAirPlay2Password = false
            airPlay2Password = ""
        } else {
            val receiver = "$host:$port"
            try {
                val (hasPairing, savedPassword) = withContext(Dispatchers.IO) {
                    airPlay2CredentialStore.contains(receiver) to
                        airPlay2CredentialStore.loadPassword(receiver)
                }
                hasSavedAirPlay2Pairing = hasPairing
                hasSavedAirPlay2Password = savedPassword != null
                airPlay2Password = airPlay2PasswordDrafts[receiver]
                    ?: savedPassword.orEmpty()
            } catch (error: AirPlay2CredentialStoreException) {
                hasSavedAirPlay2Password = false
                airPlay2Password = airPlay2PasswordDrafts[receiver].orEmpty()
                appendLog("[AP2] ${error.message}")
            }
        }
    }

    val currentReceiverAddress = normalizedReceiverAddress(ipAddress)
    val activeReceiverAddresses = selectedReceiverAddresses.ifEmpty {
        setOf(currentReceiverAddress).filter(String::isNotEmpty).toSet()
    }
    val receiverReachability = aggregateReceiverReachability(
        activeReceiverAddresses,
        receiverReachabilityByAddress,
    )

    LaunchedEffect(ipAddress, isConnected, selectedReceiverAddresses, knownDevices) {
        val targets = (selectedReceiverAddresses + currentReceiverAddress)
            .filter(String::isNotEmpty)
            .toSet()
        if (targets.isEmpty()) {
            return@LaunchedEffect
        }
        if (isConnected) {
            receiverReachabilityByAddress = receiverReachabilityByAddress + targets.associateWith {
                receiverReachabilityByAddress[it]
                    ?.takeIf { status -> status == ReceiverReachability.VERIFIED }
                    ?: ReceiverReachability.REACHABLE
            }
            return@LaunchedEffect
        }
        while (true) {
            receiverReachabilityByAddress = receiverReachabilityByAddress +
                targets.associateWith { ReceiverReachability.CHECKING }
            val results = coroutineScope {
                targets.map { address ->
                    async(Dispatchers.IO) {
                        val (host, port) = parseIpAndPort(address)
                        address to runCatching {
                            AirPlayReceiverProbe(host, port).getInfo()
                        }.getOrNull()
                    }
                }.awaitAll().toMap()
            }
            var capabilitiesChanged = false
            val statuses = results.mapValues { (address, info) ->
                if (info == null) {
                    ReceiverReachability.UNREACHABLE
                } else {
                    val remembered = selectedDevice
                        ?.takeIf { preferredReceiverAddress(it) == address }
                        ?: prefsManager.getAirPlayCapabilities(address)
                    val rememberedId = remembered?.receiverId
                    val receivedId = info.receiverId
                    if (
                        rememberedId != null &&
                        info.receiverIds.isNotEmpty() &&
                        rememberedId !in info.receiverIds
                    ) {
                        ReceiverReachability.DIFFERENT_RECEIVER
                    } else if (receivedId != null && remembered != null) {
                        if (rememberedId != receivedId) {
                            val identified = remembered.copy(
                                name = info.name ?: remembered.name,
                                receiverId = receivedId,
                            )
                            prefsManager.saveAirPlayCapabilities(identified)
                            if (selectedDevice?.let(::preferredReceiverAddress) == address) {
                                selectedDevice = identified
                            }
                            capabilitiesChanged = true
                        }
                        ReceiverReachability.VERIFIED
                    } else {
                        ReceiverReachability.REACHABLE
                    }
                }
            }
            receiverReachabilityByAddress = receiverReachabilityByAddress + statuses
            if (capabilitiesChanged) {
                knownDevices = prefsManager.getKnownAirPlayDevices()
            }
            delay(REACHABILITY_REFRESH_MS)
        }
    }

    // Listen for errors and status from Service
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.egron.lampan.ERROR") {
                    val error = intent.getStringExtra("ERROR_MSG")
                    if (error != null) {
                        errorMessage = error
                        isConnected = false
                        statusLogs = statusLogs + "Error: $error"
                    }
                } else if (intent?.action == "com.egron.lampan.STATUS") {
                    val status = intent.getStringExtra("STATUS_MSG")
                    if (status != null) {
                        if (status == "[AP2] Pairing identity saved") {
                            hasSavedAirPlay2Pairing = true
                        }
                        if (status == "[AP2] Receiver password saved securely") {
                            hasSavedAirPlay2Password = true
                        }
                        if (status == "Connected. Starting capture...") {
                            receiverReachabilityByAddress = receiverReachabilityByAddress +
                                activeReceiverAddresses.associateWith { address ->
                                    receiverReachabilityByAddress[address]
                                        ?.takeIf {
                                            it == ReceiverReachability.VERIFIED
                                        }
                                        ?: ReceiverReachability.REACHABLE
                                }
                            rememberCurrentCapabilities()
                        }
                        statusLogs = (statusLogs + status).takeLast(100)
                    }
                } else if (intent?.action == AudioCaptureService.ACTION_RECEIVER_STATE) {
                    val active = intent.getStringArrayListExtra(
                        AudioCaptureService.EXTRA_ACTIVE_RECEIVERS,
                    ).orEmpty().toSet()
                    val receiverError = intent.getStringExtra(
                        AudioCaptureService.EXTRA_RECEIVER_ERROR,
                    )
                    selectedReceiverAddresses = active
                    pendingReceiverAddresses = emptySet()
                    receiverError?.let { errorMessage = it }

                    val focusedAddress = selectedDevice?.let(::preferredReceiverAddress)
                    if (active.isNotEmpty() && focusedAddress !in active) {
                        knownDevices.firstOrNull {
                            preferredReceiverAddress(it) in active
                        }?.let { next ->
                            selectedDevice = next
                            receiverProtocol = next.preferredProtocol
                            updateIpAddress(preferredReceiverAddress(next))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.egron.lampan.ERROR")
            addAction("com.egron.lampan.STATUS")
            addAction(AudioCaptureService.ACTION_RECEIVER_STATE)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Error") },
            text = { Text(errorMessage ?: "Unknown error") },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    if (confirmForgetAirPlay2Pairing) {
        AlertDialog(
            onDismissRequest = { confirmForgetAirPlay2Pairing = false },
            title = { Text("Forget saved AirPlay 2 access?") },
            text = {
                Text(
                    "This removes the saved receiver password and pairing key from this phone. " +
                        "For registered receivers, you may also need to remove Lampan from the " +
                        "receiver's paired-device list before pairing again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val (host, port) = parseIpAndPort(ipAddress)
                        val receiver = "$host:$port"
                        airPlay2CredentialStore.remove(receiver)
                        airPlay2CredentialStore.removePassword(receiver)
                        hasSavedAirPlay2Pairing = false
                        hasSavedAirPlay2Password = false
                        airPlay2Password = ""
                        airPlay2PasswordDrafts = airPlay2PasswordDrafts - receiver
                        confirmForgetAirPlay2Pairing = false
                        appendLog("[AP2] Saved AirPlay 2 access removed from this phone")
                    },
                ) {
                    Text("Forget")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetAirPlay2Pairing = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Auto-scroll to bottom
    LaunchedEffect(statusLogs.size) {
        listState.animateScrollToItem(statusLogs.size - 1)
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            discovery.discoverDevices().collect {
                discoveredDevices = it
                receiverReachabilityByAddress = receiverReachabilityByAddress +
                    it.associate { device ->
                        preferredReceiverAddress(device) to ReceiverReachability.REACHABLE
                    }
            }
        }
    }

    // MediaProjection Launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startService(
                context = context,
                resultCode = result.resultCode,
                data = result.data!!,
                rawIp = ipAddress,
                volume = volume,
                useAirPlay2 = receiverProtocol == AirPlayProtocol.AIRPLAY_2,
                useTransientAirPlay2 = receiverProtocol == AirPlayProtocol.AIRPLAY_2 &&
                    selectedDevice?.airPlay2RequiresPassword == false,
                airPlay2Password = airPlay2Password.takeIf { it.isNotEmpty() },
                receiverAddresses = selectedReceiverAddresses.toList(),
            )
            isConnected = true
            isConnecting = false
        } else {
            isConnecting = false
            Toast.makeText(context, "MediaProjection denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Permissions Launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO]
            ?: hasPermission(context, android.Manifest.permission.RECORD_AUDIO)
        if (audioGranted) {
            requestMediaProjection(context, launcher)
        } else {
            isConnecting = false
            Toast.makeText(
                context,
                "Playback capture permission is required by Android",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val scanPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isScanning = true
        } else {
            Toast.makeText(context, "Nearby Devices permission required", Toast.LENGTH_SHORT).show()
        }
    }

    val backgroundBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeaderCard(
                title = "Lampan",
                subtitle = "AirPlay audio streaming",
                isConnected = isConnected,
                currentSsid = currentSsid,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme
            )

            SectionCard(title = "Receiver") {
                if (knownDevices.isNotEmpty()) {
                    Text(
                        text = "Known Devices",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(knownDevices) { device ->
                            val deviceAddress = preferredReceiverAddress(device)
                            DeviceRow(
                                device = device,
                                selected = deviceAddress in selectedReceiverAddresses &&
                                    (!isAddingDevice || isConnected),
                                status = if (deviceAddress in pendingReceiverAddresses) {
                                    ReceiverReachability.CHECKING
                                } else {
                                    receiverReachabilityByAddress[deviceAddress]
                                },
                            ) {
                                val protocol = device.preferredProtocol
                                val port = requireNotNull(device.portFor(protocol))
                                val alreadySelected = deviceAddress in selectedReceiverAddresses
                                if (alreadySelected && selectedReceiverAddresses.size > 1) {
                                    if (isConnected) {
                                        if (pendingReceiverAddresses.isEmpty()) {
                                            pendingReceiverAddresses = setOf(deviceAddress)
                                            removeReceiverFromStream(context, deviceAddress)
                                        }
                                    } else {
                                        val remaining = selectedReceiverAddresses - deviceAddress
                                        selectedReceiverAddresses = remaining
                                        val next = knownDevices.firstOrNull {
                                            preferredReceiverAddress(it) in remaining
                                        }
                                        if (next != null) {
                                            selectedDevice = next
                                            receiverProtocol = next.preferredProtocol
                                            updateIpAddress(preferredReceiverAddress(next))
                                        }
                                    }
                                } else if (!alreadySelected) {
                                    selectedDevice = device
                                    receiverProtocol = protocol
                                    isAddingDevice = false
                                    isScanning = false
                                    updateIpAddress("${device.ip}:$port")
                                    if (isConnected) {
                                        val passwordDraft = airPlay2PasswordDrafts[deviceAddress]
                                        val missingAccess = protocol == AirPlayProtocol.AIRPLAY_2 &&
                                            device.airPlay2RequiresPassword != false &&
                                            !airPlay2CredentialStore.contains(deviceAddress) &&
                                            !airPlay2CredentialStore.containsPassword(deviceAddress) &&
                                            passwordDraft.isNullOrEmpty()
                                        when {
                                            missingAccess -> errorMessage =
                                                "Connect to ${device.name} alone once to save its " +
                                                    "AirPlay 2 password before adding it live"
                                            pendingReceiverAddresses.isEmpty() -> {
                                                pendingReceiverAddresses = setOf(deviceAddress)
                                                addReceiverToStream(
                                                    context,
                                                    deviceAddress,
                                                    passwordDraft,
                                                )
                                            }
                                        }
                                    } else {
                                        selectedReceiverAddresses =
                                            selectedReceiverAddresses + deviceAddress
                                    }
                                } else {
                                    selectedDevice = device
                                    receiverProtocol = protocol
                                    isAddingDevice = false
                                    updateIpAddress("${device.ip}:$port")
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (isAddingDevice || knownDevices.isEmpty()) {
                    Text(
                        text = "Scan for a receiver or enter its IP and port manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = {
                            val remembered = normalizedReceiverAddress(it)
                                .takeIf(String::isNotEmpty)
                                ?.let(prefsManager::getAirPlayCapabilities)
                            selectedDevice = remembered
                            if (remembered != null) {
                                receiverProtocol = remembered.preferredProtocol
                                selectedReceiverAddresses =
                                    setOf(preferredReceiverAddress(remembered))
                                isAddingDevice = false
                            } else {
                                selectedReceiverAddresses = emptySet()
                            }
                            updateIpAddress(it)
                        },
                        label = { Text("Receiver IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        enabled = !isConnected
                    )
                }

                if (receiverReachability != ReceiverReachability.NONE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val reachabilityColor = when (receiverReachability) {
                        ReceiverReachability.VERIFIED,
                        ReceiverReachability.REACHABLE -> RECEIVER_AVAILABLE_COLOR
                        ReceiverReachability.DIFFERENT_RECEIVER,
                        ReceiverReachability.UNREACHABLE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val reachabilityText = when (receiverReachability) {
                        ReceiverReachability.CHECKING -> "Checking AirPlay receiver…"
                        ReceiverReachability.VERIFIED -> "AirPlay receiver verified"
                        ReceiverReachability.REACHABLE -> "AirPlay receiver reachable"
                        ReceiverReachability.DIFFERENT_RECEIVER -> if (
                            activeReceiverAddresses.size > 1
                        ) {
                            "A selected address belongs to a different receiver"
                        } else {
                            "Different receiver found at this address"
                        }
                        ReceiverReachability.UNREACHABLE -> if (
                            activeReceiverAddresses.size > 1
                        ) {
                            "A selected AirPlay receiver is unavailable"
                        } else {
                            "AirPlay receiver unavailable"
                        }
                        ReceiverReachability.NONE -> ""
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    reachabilityColor,
                                    shape = MaterialTheme.shapes.small,
                                ),
                        )
                        Text(
                            text = reachabilityText,
                            style = MaterialTheme.typography.bodySmall,
                            color = reachabilityColor,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedDevice != null) {
                    Text(
                        text = if (selectedReceiverAddresses.size > 1) {
                            "${selectedReceiverAddresses.size} receivers selected; Lampan will " +
                                "map their AirPlay 1 and AirPlay 2 RTP streams to one shared " +
                                "network-time start."
                        } else {
                            "${selectedDevice?.protocolLabel} capabilities remembered; " +
                                "Lampan will use " +
                            if (receiverProtocol == AirPlayProtocol.AIRPLAY_2) {
                                "AirPlay 2."
                            } else {
                                "AirPlay 1."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val selectableDevice = selectedDevice
                    if (
                        selectableDevice?.airPlay1Port != null &&
                        selectableDevice.airPlay2Port != null &&
                        selectedReceiverAddresses.size <= 1
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Streaming protocol",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                AirPlayProtocol.AIRPLAY_1 to "AirPlay 1",
                                AirPlayProtocol.AIRPLAY_2 to "AirPlay 2",
                            ).forEach { (protocol, label) ->
                                val chooseProtocol = {
                                    val oldAddress = preferredReceiverAddress(selectableDevice)
                                    val updated = selectableDevice.copy(
                                        protocolPreference = protocol,
                                    )
                                    val newAddress = preferredReceiverAddress(updated)
                                    prefsManager.saveAirPlayCapabilities(updated)
                                    knownDevices = prefsManager.getKnownAirPlayDevices()
                                    selectedDevice = updated
                                    receiverProtocol = protocol
                                    selectedReceiverAddresses = if (
                                        oldAddress in selectedReceiverAddresses
                                    ) {
                                        selectedReceiverAddresses - oldAddress + newAddress
                                    } else {
                                        setOf(newAddress)
                                    }
                                    updateIpAddress(newAddress)
                                }
                                if (receiverProtocol == protocol) {
                                    Button(
                                        onClick = {},
                                        modifier = Modifier.weight(1f),
                                        enabled = !isConnected && !isConnecting,
                                    ) { Text(label) }
                                } else {
                                    OutlinedButton(
                                        onClick = chooseProtocol,
                                        modifier = Modifier.weight(1f),
                                        enabled = !isConnected && !isConnecting,
                                    ) { Text(label) }
                                }
                            }
                        }
                        Text(
                            text = "AirPlay 1 is the safer default. Choose AirPlay 2 for " +
                                "receivers such as the Sony TV that work with Android's " +
                                "unprivileged NTP timing path.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text("Manual connection protocol", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val airPlay1Selected = receiverProtocol == AirPlayProtocol.AIRPLAY_1
                        if (airPlay1Selected) {
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                enabled = !isConnected && !isConnecting,
                            ) { Text("AirPlay 1") }
                        } else {
                            OutlinedButton(
                                onClick = { receiverProtocol = AirPlayProtocol.AIRPLAY_1 },
                                modifier = Modifier.weight(1f),
                                enabled = !isConnected && !isConnecting,
                            ) { Text("AirPlay 1") }
                        }

                        if (!airPlay1Selected) {
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                enabled = !isConnected && !isConnecting,
                            ) { Text("AirPlay 2") }
                        } else {
                            OutlinedButton(
                                onClick = { receiverProtocol = AirPlayProtocol.AIRPLAY_2 },
                                modifier = Modifier.weight(1f),
                                enabled = !isConnected && !isConnecting,
                            ) { Text("AirPlay 2") }
                        }
                    }
                }

                if (receiverProtocol == AirPlayProtocol.AIRPLAY_2 && !isConnected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val needsPassword = selectedDevice?.airPlay2RequiresPassword != false
                    if (hasSavedAirPlay2Password) {
                        Text(
                            text = "AirPlay password saved securely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (hasSavedAirPlay2Pairing) {
                        Text(
                            text = "AirPlay pairing saved securely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (needsPassword) {
                        OutlinedTextField(
                            value = airPlay2Password,
                            onValueChange = { password ->
                                airPlay2Password = password
                                val receiver = normalizedReceiverAddress(ipAddress)
                                if (receiver.isNotEmpty()) {
                                    airPlay2PasswordDrafts =
                                        airPlay2PasswordDrafts + (receiver to password)
                                }
                            },
                            label = { Text("AirPlay password or setup code") },
                            supportingText = {
                                Text(
                                    "Optional. Lampan first tries the standard passwordless " +
                                        "AirPlay setup automatically.",
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() },
                            ),
                            enabled = !isConnecting,
                        )
                    } else {
                        Text(
                            text = "This receiver does not require an AirPlay password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!hasSavedAirPlay2Pairing && !hasSavedAirPlay2Password) {
                        Text(
                            text = "AirPlay 2 pairs securely, then streams captured phone audio as " +
                                "encrypted realtime ALAC. Lampan uses transient password pairing " +
                                "when supported and saves an identity when registration is required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (
                        needsPassword &&
                        !hasSavedAirPlay2Pairing &&
                        !hasSavedAirPlay2Password
                    ) {
                        OutlinedButton(
                            onClick = {
                                val (host, port) = parseIpAndPort(ipAddress)
                                if (host.isEmpty()) {
                                    errorMessage = "Enter a receiver IP address first"
                                } else {
                                    isConnecting = true
                                    uiScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                AirPlay2Client(host, port, appendLog).requestSetupCode()
                                            }
                                        } catch (error: Exception) {
                                            errorMessage = "Setup-code request failed: ${error.message}"
                                            appendLog("[AP2] Setup-code request failed: ${error.message}")
                                        } finally {
                                            isConnecting = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isConnecting,
                        ) {
                            Text("Ask Receiver to Show Code")
                        }
                    } else if (
                        needsPassword &&
                        (hasSavedAirPlay2Pairing || hasSavedAirPlay2Password)
                    ) {
                        TextButton(
                            onClick = { confirmForgetAirPlay2Pairing = true },
                            enabled = !isConnecting,
                        ) {
                            Text("Forget Saved AirPlay 2 Access")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!isAddingDevice && knownDevices.isNotEmpty()) {
                                if (!isConnected) {
                                    selectedDevice = null
                                    selectedReceiverAddresses = emptySet()
                                    receiverProtocol = AirPlayProtocol.AIRPLAY_1
                                    ipAddress = ""
                                }
                                isAddingDevice = true
                            } else if (isScanning) {
                                isScanning = false
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    scanPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                                } else {
                                    isScanning = true
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when {
                                !isAddingDevice && knownDevices.isNotEmpty() -> "Add Device"
                                isScanning -> "Stop Scan"
                                else -> "Scan"
                            },
                        )
                    }

                    Button(
                        onClick = {
                            val (host, _) = parseIpAndPort(ipAddress)
                            val missingGroupAccess = if (selectedReceiverAddresses.size > 1) {
                                knownDevices.firstOrNull { device ->
                                    val address = preferredReceiverAddress(device)
                                    address in selectedReceiverAddresses &&
                                        device.preferredProtocol == AirPlayProtocol.AIRPLAY_2 &&
                                        device.airPlay2RequiresPassword != false &&
                                        !airPlay2CredentialStore.contains(address) &&
                                        !airPlay2CredentialStore.containsPassword(address) &&
                                        !(address == normalizedReceiverAddress(ipAddress) &&
                                            airPlay2Password.isNotEmpty())
                                }
                            } else {
                                null
                            }
                            when {
                                host.isEmpty() -> {
                                    errorMessage = "Enter a receiver IP address first"
                                }
                                receiverReachability == ReceiverReachability.DIFFERENT_RECEIVER -> {
                                    errorMessage =
                                        "A different receiver is using this remembered address; " +
                                            "select it from a new scan before streaming"
                                }
                                missingGroupAccess != null -> {
                                    errorMessage =
                                        "Connect to ${missingGroupAccess.name} alone once to save " +
                                            "its AirPlay 2 password before grouping it"
                                }
                                else -> {
                                    errorMessage = null
                                    isConnecting = true
                                    val perms = mutableListOf<String>()
                                    if (!hasPermission(
                                            context,
                                            android.Manifest.permission.RECORD_AUDIO,
                                        )
                                    ) {
                                        perms.add(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !hasPermission(
                                            context,
                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    ) {
                                        perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    if (perms.isEmpty()) {
                                        requestMediaProjection(context, launcher)
                                    } else {
                                        permissionsLauncher.launch(perms.toTypedArray())
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isConnected && !isConnecting,
                    ) {
                        Text(
                            when {
                                isConnecting -> "Connecting..."
                                selectedReceiverAddresses.size > 1 ->
                                    "Stream to ${selectedReceiverAddresses.size}"
                                else -> "Stream"
                            },
                        )
                    }
                }

                if (isConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            stopService(context)
                            pendingReceiverAddresses = emptySet()
                            isConnected = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Disconnect")
                    }
                }

                if (
                    (isAddingDevice || knownDevices.isEmpty()) &&
                    discoveredDevices.isNotEmpty()
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Discovered Devices",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(discoveredDevices) { device ->
                            DeviceRow(
                                device = device,
                                status = if (
                                    preferredReceiverAddress(device) in pendingReceiverAddresses
                                ) {
                                    ReceiverReachability.CHECKING
                                } else {
                                    ReceiverReachability.REACHABLE
                                },
                            ) {
                                val rememberedPreference = knownDevices
                                    .firstOrNull { it.ip == device.ip }
                                    ?.protocolPreference
                                val selected = device.copy(
                                    protocolPreference = rememberedPreference,
                                )
                                val protocol = selected.preferredProtocol
                                val port = requireNotNull(selected.portFor(protocol))
                                selectedDevice = selected
                                receiverProtocol = protocol
                                prefsManager.saveAirPlayCapabilities(selected)
                                knownDevices = prefsManager.getKnownAirPlayDevices()
                                isAddingDevice = false
                                updateIpAddress("${selected.ip}:$port")
                                isScanning = false
                                val address = preferredReceiverAddress(selected)
                                if (isConnected) {
                                    val missingAccess = protocol == AirPlayProtocol.AIRPLAY_2 &&
                                        selected.airPlay2RequiresPassword != false &&
                                        !airPlay2CredentialStore.contains(address) &&
                                        !airPlay2CredentialStore.containsPassword(address)
                                    when {
                                        address in selectedReceiverAddresses -> Unit
                                        missingAccess -> errorMessage =
                                            "Connect to ${selected.name} alone once to save its " +
                                                "AirPlay 2 password before adding it live"
                                        pendingReceiverAddresses.isEmpty() -> {
                                            pendingReceiverAddresses = setOf(address)
                                            addReceiverToStream(context, address)
                                        }
                                    }
                                } else {
                                    selectedReceiverAddresses = setOf(address)
                                }
                            }
                        }
                    }
                }
            }

            if (isConnected) {
                SectionCard(title = "Streaming") {
                    Text("Volume", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = {
                            val intent = Intent(context, AudioCaptureService::class.java).apply {
                                action = "SET_VOLUME"
                                putExtra("VOLUME", volume)
                            }
                            context.startService(intent)
                        },
                        valueRange = 0f..1f
                    )
                }
            }

            SectionCard(title = "Logs") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Session output",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Lampan Logs", statusLogs.joinToString("\n"))
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Copy")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    ) {
                        items(statusLogs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Utilities") {
                Text(
                    text = "Quick audio ping to confirm local output is working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                        toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 500)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Play Test Sound (Local)")
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(
    title: String,
    subtitle: String,
    isConnected: Boolean,
    currentSsid: String,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onToggleTheme) {
                    Icon(
                        painter = painterResource(
                            id = if (isDarkTheme) R.drawable.ic_theme_sun else R.drawable.ic_theme_moon
                        ),
                        contentDescription = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme"
                    )
                }
                StatusPill(isConnected = isConnected)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (currentSsid.isNotEmpty() && currentSsid != "<unknown ssid>") {
            Text(
                text = "Wi-Fi: $currentSsid",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusPill(isConnected: Boolean) {
    val color = if (isConnected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isConnected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = if (isConnected) "Connected" else "Idle",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                if (title != null) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                content()
            }
        )
    }
}

@Composable
private fun DeviceRow(
    device: AirPlayDevice,
    selected: Boolean = false,
    status: ReceiverReachability? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${device.ip}:${device.portFor(device.preferredProtocol)} · " +
                        device.protocolLabel + if (
                            device.airPlay1Port != null && device.airPlay2Port != null
                        ) {
                            " · uses " + if (
                                device.preferredProtocol == AirPlayProtocol.AIRPLAY_2
                            ) {
                                "AirPlay 2"
                            } else {
                                "AirPlay 1"
                            }
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (status != null) {
                val statusColor = when (status) {
                    ReceiverReachability.VERIFIED,
                    ReceiverReachability.REACHABLE -> RECEIVER_AVAILABLE_COLOR
                    ReceiverReachability.DIFFERENT_RECEIVER,
                    ReceiverReachability.UNREACHABLE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            statusColor,
                            shape = MaterialTheme.shapes.small
                        )
                )
            }
        }
    }
}

fun startService(
    context: Context,
    resultCode: Int,
    data: Intent,
    rawIp: String,
    volume: Float,
    useAirPlay2: Boolean = false,
    useTransientAirPlay2: Boolean = false,
    airPlay2Password: String? = null,
    receiverAddresses: List<String> = emptyList(),
) {
    val (host, port) = parseIpAndPort(rawIp)
    val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = "START"
        putExtra("RESULT_CODE", resultCode)
        putExtra("DATA", data)
        putExtra("HOST", host)
        putExtra("PORT", port)
        putExtra("INITIAL_VOLUME", volume)
        putExtra("AIRPLAY2", useAirPlay2)
        putExtra("AIRPLAY2_TRANSIENT", useTransientAirPlay2)
        airPlay2Password?.let { putExtra("AIRPLAY2_PASSWORD", it) }
        putStringArrayListExtra("RECEIVERS", ArrayList(receiverAddresses))
    }
    ContextCompat.startForegroundService(context, intent)
}

fun stopService(context: Context) {
    val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = "STOP"
    }
    context.startService(intent)
}

private fun addReceiverToStream(
    context: Context,
    receiver: String,
    airPlay2Password: String? = null,
) {
    val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = "ADD_RECEIVER"
        putExtra("RECEIVER", receiver)
        airPlay2Password?.let { putExtra("AIRPLAY2_PASSWORD", it) }
    }
    context.startService(intent)
}

private fun removeReceiverFromStream(context: Context, receiver: String) {
    val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = "REMOVE_RECEIVER"
        putExtra("RECEIVER", receiver)
    }
    context.startService(intent)
}

// Helper function to parse IP and optional port
private fun parseIpAndPort(rawIp: String): Pair<String, Int> {
    val parts = rawIp.split(":")
    return if (parts.size == 2) {
        Pair(parts[0].trim(), parts[1].trim().toIntOrNull() ?: 7000)
    } else {
        Pair(rawIp.trim(), 7000) // Default port 7000
    }
}
