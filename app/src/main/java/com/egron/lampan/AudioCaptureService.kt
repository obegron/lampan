package com.egron.lampan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.egron.lampan.R
import com.egron.lampan.audio.AudioCapture
import com.egron.lampan.raop.AirPlay2Session
import com.egron.lampan.raop.AirPlayProtocol
import com.egron.lampan.raop.RaopSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AudioCaptureService : Service() {

    private var audioCapture: AudioCapture? = null
    private var raopSessions = emptyList<RaopSession>()
    private var airPlay2Sessions = emptyList<AirPlay2Session>()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                val resultCode = intent.getIntExtra("RESULT_CODE", 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("DATA", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("DATA")
                }
                val host = intent.getStringExtra("HOST") ?: "192.168.1.1"
                val port = intent.getIntExtra("PORT", 7000)
                val initialVolume = intent.getFloatExtra("INITIAL_VOLUME", 1.0f)
                val useAirPlay2 = intent.getBooleanExtra("AIRPLAY2", false)
                val useTransientAirPlay2 = intent.getBooleanExtra("AIRPLAY2_TRANSIENT", false)
                val airPlay2Password = intent.getStringExtra("AIRPLAY2_PASSWORD")
                val receivers = intent.getStringArrayListExtra("RECEIVERS").orEmpty()
                
                if (resultCode != 0 && data != null) {
                    startForegroundService()
                    startCapture(
                        resultCode,
                        data,
                        host,
                        port,
                        initialVolume,
                        useAirPlay2,
                        useTransientAirPlay2,
                        airPlay2Password,
                        receivers,
                    )
                }
            }
            "STOP" -> {
                stopCapture()
                stopSelf()
            }
            "SET_VOLUME" -> {
                val vol = intent.getFloatExtra("VOLUME", 1.0f)
                scope.launch {
                    raopSessions.forEach { it.setVolume(vol) }
                    airPlay2Sessions.forEach { it.setVolume(vol) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = "AudioCaptureChannel"
        val channel = NotificationChannel(
            channelId,
            "Audio Capture Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Lampan")
            .setContentText("Streaming Audio...")
            .setSmallIcon(android.R.drawable.ic_media_play) // Using system drawable for now
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent,
        host: String,
        port: Int,
        initialVolume: Float,
        useAirPlay2: Boolean,
        useTransientAirPlay2: Boolean,
        airPlay2Password: String?,
        receiverAddresses: List<String>,
    ) {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mpManager.getMediaProjection(resultCode, data) ?: throw Exception("MediaProjection denied or null")

            val receiver = "$host:$port"
            val requestedReceivers = receiverAddresses.ifEmpty { listOf(receiver) }.distinct()
            sendStatusBroadcast(
                if (requestedReceivers.size > 1) {
                    "Connecting to ${requestedReceivers.size} receivers with synchronized AirPlay 2..."
                } else {
                    "Connecting to $receiver with " +
                        "${if (useAirPlay2) "AirPlay 2" else "AirPlay 1"}..."
                },
            )

            val credentialStore = AirPlay2CredentialStore(this)
            val preferences = PreferencesManager(this)
            val targets = requestedReceivers.map { address ->
                val (targetHost, targetPort) = parseReceiverAddress(address)
                val capabilities = preferences.getAirPlayCapabilities(address)
                val targetUsesAirPlay2 = capabilities?.preferredProtocol == AirPlayProtocol.AIRPLAY_2 ||
                    (requestedReceivers.size == 1 && capabilities == null && useAirPlay2)
                ReceiverTarget(
                    address = address,
                    host = targetHost,
                    port = targetPort,
                    name = capabilities?.name ?: address,
                    useAirPlay2 = targetUsesAirPlay2,
                    transient = capabilities?.airPlay2RequiresPassword == false ||
                        (requestedReceivers.size == 1 &&
                            capabilities == null &&
                            useTransientAirPlay2),
                    password = (
                        if (address == receiver && !airPlay2Password.isNullOrEmpty()) {
                            airPlay2Password
                        } else {
                            credentialStore.loadPassword(address)
                        }
                        ) ?: DEFAULT_TRANSIENT_PASSWORD.takeIf { targetUsesAirPlay2 },
                )
            }
            if (targets.size > 1 && targets.any { !it.useAirPlay2 }) {
                throw IllegalArgumentException(
                    "Synchronized multi-receiver streaming currently requires AirPlay 2 on every receiver",
                )
            }

            val preparedAirPlay2 = targets.filter(ReceiverTarget::useAirPlay2).map { target ->
                val sessionLog: (String) -> Unit = if (targets.size > 1) {
                    { message -> sendStatusBroadcast("[${target.name}] $message") }
                } else {
                    ::sendStatusBroadcast
                }
                target to AirPlay2Session(
                    host = target.host,
                    port = target.port,
                    credentials = if (target.transient) {
                        null
                    } else {
                        credentialStore.load(target.address)
                    },
                    password = target.password,
                    onCredentialsCreated = { credentials ->
                        credentialStore.save(target.address, credentials)
                        sendStatusBroadcast("[AP2] Pairing identity saved")
                    },
                    log = sessionLog,
                    transientPairing = target.transient,
                    onTransientPasswordAuthenticated = {
                        if (!target.password.isNullOrEmpty()) {
                            try {
                                credentialStore.savePassword(target.address, target.password)
                                sendStatusBroadcast("[AP2] Receiver password saved securely")
                            } catch (error: AirPlay2CredentialStoreException) {
                                sendStatusBroadcast("[AP2] Could not save receiver password: ${error.message}")
                            }
                        }
                    },
                )
            }
            val preparedAirPlay1 = targets.filterNot(ReceiverTarget::useAirPlay2).map { target ->
                target to RaopSession(target.host, target.port, ::sendStatusBroadcast)
            }
            airPlay2Sessions = preparedAirPlay2.map { it.second }
            raopSessions = preparedAirPlay1.map { it.second }
            
            audioCapture = AudioCapture(mediaProjection, { status -> 
                sendStatusBroadcast(status)
            }) { pcmData ->
                // This runs on IO thread from AudioCapture
                airPlay2Sessions.forEach { it.sendFrame(pcmData) }
                raopSessions.forEach { it.sendFrame(pcmData) }
            }
            
            scope.launch {
                try {
                    val synchronizedGroup = preparedAirPlay2.size > 1
                    preparedAirPlay2.forEach { (target, session) ->
                        try {
                            session.connect(
                                initialVolume = initialVolume,
                                synchronizeImmediately = !synchronizedGroup,
                            )
                        } catch (error: Exception) {
                            throw Exception("${target.name}: ${error.message}", error)
                        }
                    }
                    preparedAirPlay1.forEach { (target, session) ->
                        try {
                            session.connect(initialVolume)
                        } catch (error: Exception) {
                            throw Exception("${target.name}: ${error.message}", error)
                        }
                    }
                    if (synchronizedGroup) {
                        val sharedStartMillis = System.currentTimeMillis() + GROUP_START_DELAY_MS
                        preparedAirPlay2.forEach { (_, session) ->
                            session.synchronizeAt(sharedStartMillis)
                        }
                        sendStatusBroadcast(
                            "[AP2] ${preparedAirPlay2.size} receivers share one RTP/NTP timeline",
                        )
                    }
                    sendStatusBroadcast("Connected. Starting capture...")
                    audioCapture?.start()
                } catch (e: Exception) {
                    e.printStackTrace()
                    sendErrorBroadcast("Connection failed: ${e.message}")
                    stopCapture()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendErrorBroadcast("Failed to start capture: ${e.message}")
            stopCapture()
            stopSelf()
        }
    }

    private fun sendStatusBroadcast(msg: String) {
        val intent = Intent("com.egron.lampan.STATUS")
        intent.putExtra("STATUS_MSG", msg)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(msg: String) {
        val intent = Intent("com.egron.lampan.ERROR")
        intent.putExtra("ERROR_MSG", msg)
        intent.setPackage(packageName) // Explicit intent for security
        sendBroadcast(intent)
    }

    private fun stopCapture() {
        val capture = audioCapture
        val airPlay1 = raopSessions
        val airPlay2 = airPlay2Sessions
        audioCapture = null
        raopSessions = emptyList()
        airPlay2Sessions = emptyList()
        capture?.stop()
        scope.launch {
            airPlay1.forEach { it.stop() }
            airPlay2.forEach { it.stop() }
        }
    }

    private fun parseReceiverAddress(address: String): Pair<String, Int> {
        val parts = address.split(':')
        return if (parts.size == 2) {
            parts[0].trim() to (parts[1].trim().toIntOrNull() ?: 7000)
        } else {
            address.trim() to 7000
        }
    }

    private data class ReceiverTarget(
        val address: String,
        val host: String,
        val port: Int,
        val name: String,
        val useAirPlay2: Boolean,
        val transient: Boolean,
        val password: String?,
    )

    private companion object {
        const val GROUP_START_DELAY_MS = 250L
        const val DEFAULT_TRANSIENT_PASSWORD = "3939"
    }
}
