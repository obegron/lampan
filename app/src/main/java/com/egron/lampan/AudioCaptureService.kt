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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioCaptureService : Service() {

    private var audioCapture: AudioCapture? = null
    private var nowPlayingMonitor: NowPlayingMonitor? = null
    private var receiverSessions = emptyMap<String, ReceiverSession>()
    private var receiverDelaysMs = emptyMap<String, Int>()
    private val receiverSessionLock = Any()
    private val receiverChangeMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var currentVolume = DEFAULT_INITIAL_VOLUME
    @Volatile private var captureStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                if (hasActiveStream()) {
                    sendReceiverStateBroadcast(
                        error = "Lampan is already streaming; disconnect it before starting again",
                    )
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra("RESULT_CODE", 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("DATA", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("DATA")
                }
                val host = intent.getStringExtra("HOST") ?: "192.168.1.1"
                val port = intent.getIntExtra("PORT", 7000)
                val initialVolume = intent.getFloatExtra(
                    "INITIAL_VOLUME",
                    DEFAULT_INITIAL_VOLUME,
                )
                val useAirPlay2 = intent.getBooleanExtra("AIRPLAY2", false)
                val useTransientAirPlay2 = intent.getBooleanExtra("AIRPLAY2_TRANSIENT", false)
                val airPlay2Password = intent.getStringExtra("AIRPLAY2_PASSWORD")
                val receivers = intent.getStringArrayListExtra("RECEIVERS").orEmpty()
                val delayReceivers = intent.getStringArrayListExtra(EXTRA_DELAY_RECEIVERS).orEmpty()
                val delayValues = intent.getIntegerArrayListExtra(EXTRA_RECEIVER_DELAYS_MS).orEmpty()
                val requestedDelaysMs = delayReceivers.zip(delayValues)
                    .associate { (address, delayMs) ->
                        address to delayMs.coerceIn(0, MAX_RECEIVER_DELAY_MS)
                    }
                
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
                        requestedDelaysMs,
                    )
                }
            }
            "STOP" -> {
                stopCapture()
                stopSelf()
            }
            ACTION_QUERY_STATE -> {
                val active = hasActiveStream()
                sendReceiverStateBroadcast()
                if (!active) stopSelf(startId)
            }
            ACTION_REFRESH_NOW_PLAYING -> {
                if (hasActiveStream()) {
                    startNowPlayingMonitor()
                } else {
                    stopSelf(startId)
                }
            }
            "SET_VOLUME" -> {
                val vol = intent.getFloatExtra("VOLUME", currentVolume)
                currentVolume = vol
                scope.launch {
                    receiverSessionSnapshot().forEach { it.setVolume(vol) }
                }
            }
            "ADD_RECEIVER" -> {
                val address = intent.getStringExtra("RECEIVER").orEmpty()
                val password = intent.getStringExtra("AIRPLAY2_PASSWORD")
                if (address.isNotEmpty()) {
                    scope.launch { addReceiver(address, password) }
                }
            }
            "REMOVE_RECEIVER" -> {
                val address = intent.getStringExtra("RECEIVER").orEmpty()
                if (address.isNotEmpty()) {
                    scope.launch { removeReceiver(address) }
                }
            }
            ACTION_SET_RECEIVER_DELAY -> {
                val address = intent.getStringExtra(EXTRA_DELAY_RECEIVER).orEmpty()
                val delayMs = intent.getIntExtra(EXTRA_RECEIVER_DELAY_MS, 0)
                    .coerceIn(0, MAX_RECEIVER_DELAY_MS)
                if (address.isNotEmpty()) {
                    scope.launch { setReceiverDelay(address, delayMs) }
                }
            }
            ACTION_SET_GROUP_DELAYS -> {
                val addresses = intent.getStringArrayListExtra(EXTRA_DELAY_RECEIVERS).orEmpty()
                val delays = intent.getIntegerArrayListExtra(EXTRA_RECEIVER_DELAYS_MS).orEmpty()
                scope.launch {
                    setGroupDelays(
                        addresses.zip(delays).associate { (address, delayMs) ->
                            address to delayMs.coerceIn(0, MAX_RECEIVER_DELAY_MS)
                        },
                    )
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
        requestedDelaysMs: Map<String, Int>,
    ) {
        try {
            currentVolume = initialVolume
            captureStarted = false
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mpManager.getMediaProjection(resultCode, data) ?: throw Exception("MediaProjection denied or null")

            val receiver = "$host:$port"
            val requestedReceivers = receiverAddresses.ifEmpty { listOf(receiver) }.distinct()
            sendStatusBroadcast(
                if (requestedReceivers.size > 1) {
                    "Connecting to ${requestedReceivers.size} synchronized AirPlay receivers..."
                } else {
                    "Connecting to $receiver with " +
                        "${if (useAirPlay2) "AirPlay 2" else "AirPlay 1"}..."
                },
            )

            val credentialStore = AirPlay2CredentialStore(this)
            val preferences = PreferencesManager(this)
            val targets = requestedReceivers.map { address ->
                buildReceiverTarget(
                    address = address,
                    preferences = preferences,
                    credentialStore = credentialStore,
                    fallbackUsesAirPlay2 = requestedReceivers.size == 1 &&
                        capabilitiesMissing(preferences, address) && useAirPlay2,
                    fallbackTransient = requestedReceivers.size == 1 &&
                        capabilitiesMissing(preferences, address) && useTransientAirPlay2,
                    enteredPassword = airPlay2Password.takeIf { address == receiver },
                )
            }
            val preparedSessions = targets.associate { target ->
                target.address to createReceiverSession(
                    target = target,
                    credentialStore = credentialStore,
                    prefixLogs = targets.size > 1,
                )
            }
            synchronized(receiverSessionLock) {
                receiverSessions = preparedSessions
                receiverDelaysMs = requestedReceivers.associateWith { address ->
                    requestedDelaysMs[address]?.coerceIn(0, MAX_RECEIVER_DELAY_MS) ?: 0
                }
            }
            
            audioCapture = AudioCapture(mediaProjection, { status -> 
                sendStatusBroadcast(status)
            }) { pcmData ->
                // This runs on IO thread from AudioCapture
                synchronized(receiverSessionLock) {
                    receiverSessions.values.forEach { it.sendFrame(pcmData) }
                }
            }
            
            scope.launch {
                try {
                    val synchronizedGroup = targets.size > 1
                    preparedSessions.values.filter(ReceiverSession::usesAirPlay2).forEach { session ->
                        try {
                            session.connect(
                                initialVolume = initialVolume,
                                synchronizeImmediately = !synchronizedGroup,
                            )
                        } catch (error: Exception) {
                            throw Exception("${session.target.name}: ${error.message}", error)
                        }
                    }
                    preparedSessions.values.filterNot(ReceiverSession::usesAirPlay2).forEach { session ->
                        try {
                            session.connect(initialVolume = initialVolume)
                        } catch (error: Exception) {
                            throw Exception("${session.target.name}: ${error.message}", error)
                        }
                    }
                    if (synchronizedGroup) {
                        val sharedStartMillis = System.currentTimeMillis() + GROUP_START_DELAY_MS
                        preparedSessions.forEach { (address, session) ->
                            session.synchronizeAt(
                                sharedStartMillis + (requestedDelaysMs[address] ?: 0),
                            )
                        }
                        val protocolLabel = when {
                            preparedSessions.values.any(ReceiverSession::usesAirPlay1) &&
                                preparedSessions.values.any(ReceiverSession::usesAirPlay2) ->
                                "AirPlay 1 + 2"
                            preparedSessions.values.any(ReceiverSession::usesAirPlay2) -> "AirPlay 2"
                            else -> "AirPlay 1"
                        }
                        sendStatusBroadcast(
                            "[Group] ${targets.size} receivers share one RTP/NTP timeline " +
                                "($protocolLabel)",
                        )
                        val adjusted = requestedDelaysMs.filterValues { it > 0 }
                        if (adjusted.isNotEmpty()) {
                            sendStatusBroadcast(
                                "[Group] Restored saved speaker timing for " +
                                    "${adjusted.size} receiver(s)",
                            )
                        }
                    } else {
                        // A standalone RAOP receiver needs the same short future-dated
                        // start used by grouped playback. Without it, its first RTP
                        // packet is timestamped for "now" and can already be late by
                        // the time it reaches the receiver's render buffer.
                        preparedSessions.values.singleOrNull()
                            ?.takeIf(ReceiverSession::usesAirPlay1)
                            ?.let { session ->
                                session.synchronizeAt(
                                    System.currentTimeMillis() + GROUP_START_DELAY_MS,
                                )
                            }
                    }
                    sendReceiverStateBroadcast()
                    sendStatusBroadcast("Connected. Starting capture...")
                    audioCapture?.start()
                    captureStarted = true
                    startNowPlayingMonitor()
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

    private suspend fun addReceiver(address: String, enteredPassword: String?) {
        receiverChangeMutex.withLock {
            if (!captureStarted) {
                sendReceiverStateBroadcast(
                    error = "Wait for the current stream to finish connecting before adding a receiver",
                )
                return
            }
            if (address in receiverSessionAddresses()) {
                sendReceiverStateBroadcast()
                return
            }

            val credentialStore = AirPlay2CredentialStore(this)
            val preferences = PreferencesManager(this)
            var prepared: ReceiverSession? = null
            try {
                val target = buildReceiverTarget(
                    address = address,
                    preferences = preferences,
                    credentialStore = credentialStore,
                    enteredPassword = enteredPassword,
                )
                val session = createReceiverSession(
                    target = target,
                    credentialStore = credentialStore,
                    prefixLogs = true,
                )
                prepared = session
                sendStatusBroadcast("[Group] Connecting ${target.name} while capture continues...")
                session.connect(
                    initialVolume = currentVolume,
                    synchronizeImmediately = false,
                )
                if (!captureStarted) {
                    throw IllegalStateException("The stream was stopped while adding ${target.name}")
                }

                val sharedStartMillis = System.currentTimeMillis() + GROUP_START_DELAY_MS
                synchronized(receiverSessionLock) {
                    val synchronizedSessions = receiverSessions + (address to session)
                    val synchronizedDelays = receiverDelaysMs + (address to 0)
                    synchronizedSessions.forEach { (receiverAddress, receiverSession) ->
                        receiverSession.synchronizeAt(
                            sharedStartMillis + (synchronizedDelays[receiverAddress] ?: 0),
                        )
                    }
                    receiverSessions = synchronizedSessions
                    receiverDelaysMs = synchronizedDelays
                }
                sendStatusBroadcast(
                    "[Group] ${target.name} joined at the next shared audio frame",
                )
                sendReceiverStateBroadcast()
            } catch (error: Exception) {
                prepared?.stop()
                sendReceiverStateBroadcast(
                    error = "Could not add $address: ${error.message}",
                )
            }
        }
    }

    private suspend fun removeReceiver(address: String) {
        receiverChangeMutex.withLock {
            val removed = synchronized(receiverSessionLock) {
                if (receiverSessions.size <= 1) {
                    null
                } else {
                    receiverSessions[address]?.also {
                        receiverSessions = receiverSessions - address
                        receiverDelaysMs = receiverDelaysMs - address
                    }
                }
            }
            if (removed == null) {
                val message = if (address in receiverSessionAddresses()) {
                    "Use Disconnect to stop the final receiver"
                } else {
                    null
                }
                sendReceiverStateBroadcast(error = message)
                return
            }

            sendReceiverStateBroadcast()
            removed.stop()
            sendStatusBroadcast("[Group] ${removed.target.name} disconnected")
        }
    }

    private fun buildReceiverTarget(
        address: String,
        preferences: PreferencesManager,
        credentialStore: AirPlay2CredentialStore,
        fallbackUsesAirPlay2: Boolean = false,
        fallbackTransient: Boolean = false,
        enteredPassword: String? = null,
    ): ReceiverTarget {
        val (targetHost, targetPort) = parseReceiverAddress(address)
        val capabilities = preferences.getAirPlayCapabilities(address)
        val targetUsesAirPlay2 = capabilities?.preferredProtocol == AirPlayProtocol.AIRPLAY_2 ||
            (capabilities == null && fallbackUsesAirPlay2)
        return ReceiverTarget(
            address = address,
            host = targetHost,
            port = targetPort,
            name = capabilities?.name ?: address,
            useAirPlay2 = targetUsesAirPlay2,
            transient = capabilities?.airPlay2RequiresPassword == false ||
                (capabilities == null && fallbackTransient),
            password = enteredPassword?.takeIf(String::isNotEmpty)
                ?: credentialStore.loadPassword(address)
                ?: DEFAULT_TRANSIENT_PASSWORD.takeIf { targetUsesAirPlay2 },
        )
    }

    private fun capabilitiesMissing(
        preferences: PreferencesManager,
        address: String,
    ): Boolean = preferences.getAirPlayCapabilities(address) == null

    private fun createReceiverSession(
        target: ReceiverTarget,
        credentialStore: AirPlay2CredentialStore,
        prefixLogs: Boolean,
    ): ReceiverSession {
        val sessionLog: (String) -> Unit = if (prefixLogs) {
            { message -> sendStatusBroadcast("[${target.name}] $message") }
        } else {
            ::sendStatusBroadcast
        }
        return if (target.useAirPlay2) {
            ReceiverSession(
                target = target,
                airPlay2 = AirPlay2Session(
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
                                sendStatusBroadcast(
                                    "[AP2] Could not save receiver password: ${error.message}",
                                )
                            }
                        }
                    },
                ),
            )
        } else {
            ReceiverSession(
                target = target,
                airPlay1 = RaopSession(target.host, target.port, sessionLog),
            )
        }
    }

    private fun receiverSessionSnapshot(): List<ReceiverSession> =
        synchronized(receiverSessionLock) { receiverSessions.values.toList() }

    private fun receiverSessionAddresses(): Set<String> =
        synchronized(receiverSessionLock) { receiverSessions.keys.toSet() }

    private fun hasActiveStream(): Boolean =
        captureStarted || audioCapture != null || receiverSessionAddresses().isNotEmpty()

    private fun setReceiverDelay(address: String, delayMs: Int) {
        val session = synchronized(receiverSessionLock) {
            receiverSessions[address]?.also {
                receiverDelaysMs = receiverDelaysMs + (address to delayMs)
            }
        }
        if (session == null || !captureStarted) {
            sendReceiverStateBroadcast(error = "Receiver is not currently streaming")
            return
        }

        // Keep the next frame safely in the future while changing its mapping.
        // Only this receiver moves; the chosen reference and other sessions keep
        // their established timelines.
        session.synchronizeAt(
            System.currentTimeMillis() + GROUP_START_DELAY_MS + delayMs,
        )
        sendStatusBroadcast("[Group] ${session.target.name} delay set to $delayMs ms")
        sendReceiverStateBroadcast()
    }

    private fun setGroupDelays(delaysMs: Map<String, Int>) {
        val sessions = synchronized(receiverSessionLock) {
            if (!captureStarted || receiverSessions.keys != delaysMs.keys) {
                emptyMap()
            } else {
                receiverDelaysMs = receiverSessions.keys.associateWith { address ->
                    delaysMs[address] ?: 0
                }
                receiverSessions.toMap()
            }
        }
        if (sessions.isEmpty()) {
            sendReceiverStateBroadcast(error = "The selected receiver group is not streaming")
            return
        }

        val sharedTargetMillis = System.currentTimeMillis() + GROUP_START_DELAY_MS
        sessions.forEach { (address, session) ->
            session.synchronizeAt(sharedTargetMillis + (delaysMs[address] ?: 0))
        }
        sendStatusBroadcast("[Group] Reference receiver changed; speaker timing reset")
        sendReceiverStateBroadcast()
    }

    private fun startNowPlayingMonitor() {
        nowPlayingMonitor?.close()
        nowPlayingMonitor = null
        if (!BuildConfig.NOW_PLAYING_ENABLED) return
        if (!PreferencesManager(this).isNowPlayingInformationEnabled()) return
        if (!isNowPlayingAccessEnabled(this)) return
        nowPlayingMonitor = NowPlayingMonitor(
            context = this,
            onUpdate = { info ->
                receiverSessionSnapshot().forEach { it.updateNowPlaying(info) }
            },
            onStatus = ::sendStatusBroadcast,
        ).also(NowPlayingMonitor::start)
    }

    private fun sendReceiverStateBroadcast(error: String? = null) {
        val intent = Intent(ACTION_RECEIVER_STATE).apply {
            putStringArrayListExtra(
                EXTRA_ACTIVE_RECEIVERS,
                ArrayList(receiverSessionAddresses()),
            )
            putExtra(EXTRA_IS_STREAMING, hasActiveStream())
            putExtra(EXTRA_CURRENT_VOLUME, currentVolume)
            val delays = synchronized(receiverSessionLock) { receiverDelaysMs.toMap() }
            putStringArrayListExtra(EXTRA_DELAY_RECEIVERS, ArrayList(delays.keys))
            putIntegerArrayListExtra(
                EXTRA_RECEIVER_DELAYS_MS,
                ArrayList(delays.values),
            )
            error?.let { putExtra(EXTRA_RECEIVER_ERROR, it) }
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun stopCapture() {
        nowPlayingMonitor?.close()
        nowPlayingMonitor = null
        val capture = audioCapture
        captureStarted = false
        val sessions = synchronized(receiverSessionLock) {
            receiverSessions.values.toList().also {
                receiverSessions = emptyMap()
                receiverDelaysMs = emptyMap()
            }
        }
        audioCapture = null
        capture?.stop()
        sendReceiverStateBroadcast()
        scope.launch {
            sessions.forEach { it.stop() }
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

    private data class ReceiverSession(
        val target: ReceiverTarget,
        val airPlay1: RaopSession? = null,
        val airPlay2: AirPlay2Session? = null,
    ) {
        val usesAirPlay1: Boolean get() = airPlay1 != null
        val usesAirPlay2: Boolean get() = airPlay2 != null

        suspend fun connect(
            initialVolume: Float,
            synchronizeImmediately: Boolean = true,
        ) {
            airPlay1?.connect(initialVolume)
            airPlay2?.connect(initialVolume, synchronizeImmediately)
        }

        fun sendFrame(pcm: ByteArray) {
            airPlay1?.sendFrame(pcm)
            airPlay2?.sendFrame(pcm)
        }

        fun synchronizeAt(unixTimeMillis: Long) {
            airPlay1?.synchronizeAt(unixTimeMillis)
            airPlay2?.synchronizeAt(unixTimeMillis)
        }

        fun setVolume(volume: Float) {
            airPlay1?.setVolume(volume)
            airPlay2?.setVolume(volume)
        }

        fun updateNowPlaying(info: NowPlayingInfo?) {
            airPlay2?.updateNowPlaying(info)
        }

        fun stop() {
            airPlay1?.stop()
            airPlay2?.stop()
        }
    }

    companion object {
        const val ACTION_RECEIVER_STATE = "com.egron.lampan.RECEIVER_STATE"
        const val ACTION_QUERY_STATE = "com.egron.lampan.QUERY_STREAM_STATE"
        const val ACTION_REFRESH_NOW_PLAYING = "com.egron.lampan.REFRESH_NOW_PLAYING"
        const val ACTION_SET_RECEIVER_DELAY = "com.egron.lampan.SET_RECEIVER_DELAY"
        const val ACTION_SET_GROUP_DELAYS = "com.egron.lampan.SET_GROUP_DELAYS"
        const val EXTRA_ACTIVE_RECEIVERS = "ACTIVE_RECEIVERS"
        const val EXTRA_RECEIVER_ERROR = "RECEIVER_ERROR"
        const val EXTRA_IS_STREAMING = "IS_STREAMING"
        const val EXTRA_CURRENT_VOLUME = "CURRENT_VOLUME"
        const val EXTRA_DELAY_RECEIVER = "DELAY_RECEIVER"
        const val EXTRA_DELAY_RECEIVERS = "DELAY_RECEIVERS"
        const val EXTRA_RECEIVER_DELAY_MS = "RECEIVER_DELAY_MS"
        const val EXTRA_RECEIVER_DELAYS_MS = "RECEIVER_DELAYS_MS"

        const val GROUP_START_DELAY_MS = 250L
        private const val DEFAULT_INITIAL_VOLUME = 0.5f
        private const val DEFAULT_TRANSIENT_PASSWORD = "3939"
    }
}
