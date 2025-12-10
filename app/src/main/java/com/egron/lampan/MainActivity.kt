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
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.egron.lampan.raop.AirPlayDevice
import com.egron.lampan.raop.AirPlayDiscovery

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

// Helper to get current WiFi SSID
private fun getCurrentSsid(context: Context): String {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val info = wifiManager.connectionInfo
    // info.ssid returns with quotes, e.g. "MyNetwork", or <unknown ssid>
    return info.ssid.replace("\"", "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
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
    var expanded by remember { mutableStateOf(false) }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
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
                if (it.isNotEmpty()) {
                    expanded = true
                }
            }
        }
    }

    // MediaProjection Launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startService(context, result.resultCode, result.data!!, ipAddress)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Lampan - AirPlay",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Scrollable Log Box
        Text(
            text = "Logs (Tap to Copy)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Lampan Logs", statusLogs.joinToString("\n"))
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .padding(top = 8.dp, bottom = 4.dp)
        )
        
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp) // Reduced height slightly
                    .padding(vertical = 8.dp)
            ) {
                items(statusLogs) { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Discovery UI
        Button(
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Stop Scanning" else "Scan for AirPlay Devices")
        }

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { updateIpAddress(it) },
                        label = { Text("Receiver IP Address") },
                        modifier = Modifier
                            .fillMaxWidth()                    .menuAnchor(),
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                enabled = !isConnected
            )
            
            if (discoveredDevices.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    discoveredDevices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text("${device.name} (${device.ip}:${device.port})") },
                            onClick = {
                                updateIpAddress(device.ip)
                                expanded = false
                                isScanning = false // Stop scanning on select
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isConnected) {
            Button(
                onClick = {
                    // Request Permissions first
                    val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionsLauncher.launch(perms.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect & Stream")
            }
        } else {
            Button(
                onClick = {
                    stopService(context)
                    isConnected = false
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Volume")
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
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Always visible test sound button
        Button(
            onClick = {
                val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 500) // Play for 500ms
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Play Test Sound (Local)")
        }
    }
}

fun startService(context: Context, resultCode: Int, data: Intent, rawIp: String) {
    val (host, port) = parseIpAndPort(rawIp)
    val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = "START"
        putExtra("RESULT_CODE", resultCode)
        putExtra("DATA", data)
        putExtra("HOST", host)
        putExtra("PORT", port)
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
