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
import androidx.compose.foundation.lazy.rememberLazyListState

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

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var ipAddress by remember { mutableStateOf("192.168.0.21") }
    var isConnected by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1.0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Use a list for logs
    var statusLogs by remember { mutableStateOf(listOf("Ready.")) }
    val listState = rememberLazyListState()

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
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp) // Fixed height for logs
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
        
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Lampan Logs", statusLogs.joinToString("\n"))
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Logs")
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
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
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                    toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 500) // Play for 500ms
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Play Test Sound")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Volume")
            Slider(
                value = volume,
                onValueChange = { 
                    volume = it
                    // Send volume update (debouncing might be good in real app, but direct is fine for now)
                    // We need a way to communicate this to the service.
                    // Currently MainActivity has no reference to the Service/Session.
                    // We need to send an Intent to the Service to update volume.
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
