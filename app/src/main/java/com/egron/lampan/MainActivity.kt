package com.egron.lampan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.painterResource
import com.egron.lampan.raop.AirPlayDevice
import com.egron.lampan.raop.AirPlayDiscovery
import com.egron.lampan.ui.theme.LampanTheme

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

@Composable
fun MainScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val currentSsid = remember { getCurrentSsid(context) }

    // Initialize IP from prefs based on SSID, or fallback to last used, or empty
    var ipAddress by remember {
        mutableStateOf(
            prefsManager.getIpForSsid(currentSsid).ifEmpty {
                prefsManager.getLastUsedIp()
            }
        )
    }

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
    var volume by remember { mutableStateOf(1.0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Use a list for logs
    var statusLogs by remember { mutableStateOf(listOf("Ready.")) }
    val listState = rememberLazyListState()

    // Discovery State
    var isScanning by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf(emptyList<AirPlayDevice>()) }
    val discovery = remember { AirPlayDiscovery(context) }

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
                        statusLogs = (statusLogs + status).takeLast(100)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.egron.lampan.ERROR")
            addAction("com.egron.lampan.STATUS")
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

    // Auto-scroll to bottom
    LaunchedEffect(statusLogs.size) {
        listState.animateScrollToItem(statusLogs.size - 1)
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            discovery.discoverDevices().collect {
                discoveredDevices = it
            }
        }
    }

    // MediaProjection Launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startService(context, result.resultCode, result.data!!, ipAddress, volume)
            isConnected = true
        } else {
            Toast.makeText(context, "MediaProjection denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Permissions Launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            // Request MediaProjection
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mgr.createScreenCaptureIntent())
        } else {
            Toast.makeText(context, "Audio Permission required", Toast.LENGTH_SHORT).show()
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
                Text(
                    text = "Pick a device or enter an IP manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { updateIpAddress(it) },
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

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isScanning) {
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
                        Text(if (isScanning) "Stop Scan" else "Scan")
                    }

                    Button(
                        onClick = {
                            // Request Permissions first
                            val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionsLauncher.launch(perms.toTypedArray())
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isConnected
                    ) {
                        Text("Connect")
                    }
                }

                if (isConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            stopService(context)
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

                if (discoveredDevices.isNotEmpty()) {
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
                            DeviceRow(device = device) {
                                updateIpAddress(device.ip)
                                isScanning = false
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
                        onValueChange = {
                            volume = it
                            val intent = Intent(context, AudioCaptureService::class.java).apply {
                                action = "SET_VOLUME"
                                putExtra("VOLUME", it)
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
private fun DeviceRow(device: AirPlayDevice, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${device.ip}:${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.small
                    )
            )
        }
    }
}

fun startService(context: Context, resultCode: Int, data: Intent, rawIp: String, volume: Float) {
    val (host, port) = parseIpAndPort(rawIp)
    val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = "START"
        putExtra("RESULT_CODE", resultCode)
        putExtra("DATA", data)
        putExtra("HOST", host)
        putExtra("PORT", port)
        putExtra("INITIAL_VOLUME", volume)
    }
    ContextCompat.startForegroundService(context, intent)
}

fun stopService(context: Context) {
    val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = "STOP"
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
