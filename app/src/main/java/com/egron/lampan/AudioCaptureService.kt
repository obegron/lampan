package com.egron.lampan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.egron.lampan.R
import com.egron.lampan.calibration.CalibrationProtocol
import com.egron.lampan.calibration.CalibrationSignal
import com.egron.lampan.audio.AudioCapture
import com.egron.lampan.raop.AirPlay2Session
import com.egron.lampan.raop.AirPlayProtocol
import com.egron.lampan.raop.NetworkClock
import com.egron.lampan.raop.RaopSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

class AudioCaptureService : Service() {

    private var audioCapture: AudioCapture? = null
    private var nowPlayingMonitor: NowPlayingMonitor? = null
    private var receiverSessions = emptyMap<String, ReceiverSession>()
    private var receiverDelaysMs = emptyMap<String, Int>()
    private val receiverSessionLock = Any()
    private val networkClock = NetworkClock()
    private var groupTimeline: GroupTimeline? = null
    private data class CalibrationRun(
        val token: String,
        val savedDelays: Map<String, Int>,
        var lastContact: Long = System.nanoTime(),
        var speaker: String? = null,
        var probeFrame: Long = Long.MAX_VALUE,
    )
    private var calibration: CalibrationRun? = null
    private val receiverChangeMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var currentVolume = DEFAULT_INITIAL_VOLUME
    private var speakerVolumes = SpeakerVolumes()
    private var volumeCommandJob: Job? = null
    @Volatile private var captureStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val phoneMediaMute by lazy {
        PhoneMediaMute(AndroidMediaMuteOutput(getSystemService(AudioManager::class.java)))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CalibrationProtocol.COMMAND -> handleCalibration(intent)
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
            ACTION_REFRESH_PHONE_MUTE -> {
                updatePhoneMediaMute()
                sendReceiverStateBroadcast()
                if (!hasActiveStream()) stopSelf(startId)
            }
            "SET_VOLUME" -> {
                val vol = intent.getFloatExtra("VOLUME", currentVolume)
                val receiver = intent.getStringExtra(EXTRA_VOLUME_RECEIVER)
                val requestId = intent.getLongExtra(EXTRA_VOLUME_REQUEST_ID, 0)
                val previous = volumeCommandJob
                volumeCommandJob = scope.launch {
                    previous?.join()
                    receiverChangeMutex.withLock {
                        if (vol.isFinite()) setVolumes(receiver, (vol * 100).roundToInt(), requestId)
                    }
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
                speakerVolumes = SpeakerVolumes.from(requestedReceivers.associateWith { address ->
                    (preferences.getReceiverVolume(address, minOf(initialVolume, DEFAULT_INITIAL_VOLUME)) * 100).roundToInt()
                })
                currentVolume = speakerVolumes.group / 100f
                receiverDelaysMs = requestedReceivers.associateWith { address ->
                    requestedDelaysMs[address]?.coerceIn(0, MAX_RECEIVER_DELAY_MS) ?: 0
                }
            }
            
            lateinit var capture: AudioCapture
            capture = AudioCapture(mediaProjection, ::sendStatusBroadcast, onCaptureStopped = {
                mainHandler.post {
                    if (audioCapture === capture) {
                        stopCapture()
                        stopSelf()
                    }
                }
            }) { pcmData ->
                // This runs on IO thread from AudioCapture
                synchronized(receiverSessionLock) {
                    val timeline = groupTimeline ?: GroupTimeline(
                        networkClock.unixNanos() + GROUP_START_DELAY_MS * 1_000_000L,
                    ).also { timeline ->
                        groupTimeline = timeline
                        receiverSessions.forEach { (address, session) ->
                            session.synchronizeAtNanos(
                                timeline.startUnixNanos + (receiverDelaysMs[address] ?: 0) * 1_000_000L,
                            )
                        }
                    }
                    calibration?.let {
                        if (System.nanoTime() - it.lastContact > 30_000_000_000L) endCalibration()
                    }
                    val run = calibration
                    if (run == null) {
                        receiverSessions.values.forEach { it.sendFrame(pcmData) }
                    } else {
                        val silence = ByteArray(pcmData.size)
                        val probe = CalibrationSignal.pcm(timeline.nextFrame - run.probeFrame, pcmData.size / 4)
                        receiverSessions.forEach { (address, session) ->
                            session.sendFrame(if (address == run.speaker) probe else silence)
                        }
                    }
                    timeline.advance(pcmData.size / 4)
                }
            }
            audioCapture = capture
            
            scope.launch {
                try {
                    val synchronizedGroup = targets.size > 1
                    preparedSessions.values.filter(ReceiverSession::usesAirPlay2).forEach { session ->
                        try {
                            session.connect(
                                initialVolume = volumeFor(session.target.address),
                                synchronizeImmediately = false,
                            )
                        } catch (error: Exception) {
                            throw Exception("${session.target.name}: ${error.message}", error)
                        }
                    }
                    preparedSessions.values.filterNot(ReceiverSession::usesAirPlay2).forEach { session ->
                        try {
                            session.connect(initialVolume = volumeFor(session.target.address))
                        } catch (error: Exception) {
                            throw Exception("${session.target.name}: ${error.message}", error)
                        }
                    }
                    if (synchronizedGroup) {
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
                    }
                    sendStatusBroadcast("Connected. Starting capture...")
                    synchronized(receiverSessionLock) {
                        check(receiverSessions === preparedSessions && audioCapture === capture) {
                            "Streaming stopped while connecting"
                        }
                        capture.start()
                        captureStarted = true
                        updatePhoneMediaMute()
                    }
                    sendReceiverStateBroadcast()
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
            if (synchronized(receiverSessionLock) { calibration != null }) {
                sendReceiverStateBroadcast(error = "Finish calibration before changing speakers")
                return
            }
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
                val receiverVolume = preferences.getReceiverVolume(
                    address, minOf(currentVolume, DEFAULT_INITIAL_VOLUME),
                )
                val initialReceiverVolume = synchronized(receiverSessionLock) {
                    speakerVolumes.add(address, (receiverVolume * 100).roundToInt())
                        .levels.getValue(address) / 100f
                }
                sendStatusBroadcast("[Group] Connecting ${target.name} while capture continues...")
                session.connect(
                    initialVolume = initialReceiverVolume,
                    synchronizeImmediately = false,
                )
                if (!captureStarted) {
                    throw IllegalStateException("The stream was stopped while adding ${target.name}")
                }

                synchronized(receiverSessionLock) {
                    val timeline = requireNotNull(groupTimeline) { "Waiting for captured audio" }
                    check(captureStarted) { "Stream stopped while connecting" }
                    check(calibration == null) { "Finish calibration before changing speakers" }
                    session.synchronizeAtNanos(timeline.timeAt(timeline.nextFrame))
                    receiverSessions = receiverSessions + (address to session)
                    speakerVolumes = speakerVolumes.add(address, (receiverVolume * 100).roundToInt())
                    currentVolume = speakerVolumes.group / 100f
                    receiverDelaysMs = receiverDelaysMs + (address to 0)
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
            if (synchronized(receiverSessionLock) { calibration != null }) {
                sendReceiverStateBroadcast(error = "Finish calibration before changing speakers")
                return
            }
            val removed = synchronized(receiverSessionLock) {
                if (calibration != null) {
                    sendReceiverStateBroadcast(error = "Finish calibration before changing speakers")
                    return
                }
                if (receiverSessions.size <= 1) {
                    null
                } else {
                    receiverSessions[address]?.also {
                        receiverSessions = receiverSessions - address
                        speakerVolumes = speakerVolumes.remove(address)
                        currentVolume = speakerVolumes.group / 100f
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
                ).also { it.networkClock = networkClock },
            )
        } else {
            ReceiverSession(
                target = target,
                airPlay1 = RaopSession(target.host, target.port, sessionLog).also { it.networkClock = networkClock },
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
        val applied = synchronized(receiverSessionLock) {
            val session = receiverSessions[address]
            if (calibration != null || session == null || !captureStarted || groupTimeline == null) false else {
                session.shiftDelay(delayMs - (receiverDelaysMs[address] ?: 0))
                receiverDelaysMs = receiverDelaysMs + (address to delayMs)
                true
            }
        }
        sendReceiverStateBroadcast(if (applied) null else "Receiver is not currently streaming")
    }

    private fun setGroupDelays(delaysMs: Map<String, Int>) {
        val applied = synchronized(receiverSessionLock) {
            if (calibration != null || !captureStarted || groupTimeline == null || receiverSessions.keys != delaysMs.keys) false else {
                receiverSessions.forEach { (address, session) ->
                    session.shiftDelay(delaysMs.getValue(address) - (receiverDelaysMs[address] ?: 0))
                }
                receiverDelaysMs = delaysMs.toMap()
                true
            }
        }
        sendReceiverStateBroadcast(if (applied) null else "The selected receiver group is not streaming")
    }

    // Called under receiverSessionLock, so commands and PCM boundaries are atomic.
    private fun endCalibration(keepDelays: Boolean = false) {
        val run = calibration ?: return
        if (!keepDelays) {
            receiverSessions.forEach { (address, session) ->
                session.shiftDelay((run.savedDelays[address] ?: 0) - (receiverDelaysMs[address] ?: 0))
            }
            receiverDelaysMs = run.savedDelays
        }
        calibration = null
    }

    private fun handleCalibration(intent: Intent) {
        val token = intent.getStringExtra("TOKEN").orEmpty()
        val request = intent.getStringExtra("REQUEST").orEmpty()
        val response = Intent(CalibrationProtocol.RESULT).setPackage(packageName)
            .putExtra("TOKEN", token).putExtra("REQUEST", request)
        try {
            synchronized(receiverSessionLock) {
                val operation = intent.getStringExtra("OP")
                if (operation == "begin") {
                    check(captureStarted && groupTimeline != null) { "Start streaming before calibration" }
                    check(calibration == null) { "Calibration is already running" }
                    check(intent.getStringArrayListExtra("ADDRESSES")?.toSet() == receiverSessions.keys) { "Speaker group changed" }
                    calibration = CalibrationRun(token, receiverDelaysMs.toMap())
                } else {
                    val run = requireNotNull(calibration) { "Calibration ended; please start again" }
                    check(run.token == token) { "Calibration session changed" }
                    run.lastContact = System.nanoTime()
                    when (operation) {
                        "probe" -> {
                            val timeline = requireNotNull(groupTimeline)
                            val address = intent.getStringExtra("ADDRESS").orEmpty()
                            check(address in receiverSessions) { "Speaker disconnected" }
                            run.speaker = address
                            run.probeFrame = timeline.nextFrame + CalibrationSignal.RATE
                            response.putExtra("EXPECTED", LongArray(3) { index ->
                                networkClock.monotonicAt(timeline.timeAt(run.probeFrame + index * CalibrationSignal.SPACING)) +
                                    2_000_000_000L + (receiverDelaysMs[address] ?: 0) * 1_000_000L
                            })
                        }
                        "apply" -> {
                            val addresses = intent.getStringArrayListExtra("ADDRESSES").orEmpty()
                            val delays = intent.getIntegerArrayListExtra("DELAYS").orEmpty()
                            check(addresses.toSet() == receiverSessions.keys && addresses.size == delays.size) { "Speaker group changed" }
                            check(delays.all { it in 0..MAX_RECEIVER_DELAY_MS }) { "Required correction exceeds the supported range" }
                            addresses.zip(delays).forEach { (address, delay) ->
                                receiverSessions.getValue(address).shiftDelay(delay - (receiverDelaysMs[address] ?: 0))
                            }
                            receiverDelaysMs = addresses.zip(delays).toMap()
                        }
                        "commit" -> endCalibration(keepDelays = true)
                        "end" -> endCalibration()
                        "heartbeat" -> Unit
                        else -> error("Unknown calibration operation")
                    }
                }
            }
        } catch (error: Exception) {
            response.putExtra("ERROR", error.message ?: "Calibration failed")
        }
        sendBroadcast(response)
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

    private fun sendReceiverStateBroadcast(error: String? = null, volumeRequestId: Long = 0) {
        val volumes = synchronized(receiverSessionLock) { speakerVolumes }
        val intent = Intent(ACTION_RECEIVER_STATE).apply {
            putStringArrayListExtra(
                EXTRA_ACTIVE_RECEIVERS,
                ArrayList(receiverSessionAddresses()),
            )
            putExtra(EXTRA_IS_STREAMING, hasActiveStream())
            putExtra(EXTRA_CURRENT_VOLUME, volumes.group / 100f)
            putExtra(EXTRA_PHONE_MEDIA_MUTED, captureStarted &&
                getSystemService(AudioManager::class.java).isStreamMute(AudioManager.STREAM_MUSIC))
            putExtra(EXTRA_VOLUME_REQUEST_ID, volumeRequestId)
            putStringArrayListExtra(EXTRA_VOLUME_RECEIVERS, ArrayList(volumes.levels.keys))
            putIntegerArrayListExtra(EXTRA_RECEIVER_VOLUMES, ArrayList(volumes.levels.values))
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
        val sessions = synchronized(receiverSessionLock) {
            captureStarted = false
            receiverSessions.values.toList().also {
                receiverSessions = emptyMap()
                groupTimeline = null
                calibration = null
                receiverDelaysMs = emptyMap()
                speakerVolumes = SpeakerVolumes()
            }
        }
        audioCapture = null
        updatePhoneMediaMute()
        capture?.stop()
        sendReceiverStateBroadcast()
        scope.launch {
            sessions.forEach { it.stop() }
        }
    }

    private fun updatePhoneMediaMute() = synchronized(receiverSessionLock) {
        try {
            if (captureStarted && PreferencesManager(this).isPhoneMediaMuteEnabled()) {
                phoneMediaMute.apply()
            } else {
                phoneMediaMute.restore()
            }
        } catch (error: Exception) {
            sendReceiverStateBroadcast(error = "Could not change phone media mute: ${error.message}")
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

    private fun volumeFor(address: String): Float = synchronized(receiverSessionLock) {
        (speakerVolumes.levels[address] ?: 50) / 100f
    }

    private fun setVolumes(address: String?, percent: Int, requestId: Long) {
        val (before, desired, sessions) = synchronized(receiverSessionLock) {
            if (!captureStarted) return
            val before = speakerVolumes
            val desired = if (address == null) before.adjustGroup(percent)
                else before.adjustSpeaker(address, percent)
            Triple(before, desired, receiverSessions.toMap())
        }
        val failed = mutableListOf<String>()
        val levels = before.levels.toMutableMap()
        sessions.forEach { (receiver, session) ->
            val next = desired.levels.getValue(receiver)
            if (next != before.levels[receiver]) {
                try {
                    session.setVolume(next / 100f)
                    levels[receiver] = next
                } catch (error: Exception) {
                    failed += session.target.name
                    sendStatusBroadcast("Volume update failed for ${session.target.name}: ${error.message}")
                }
            }
        }
        synchronized(receiverSessionLock) {
            // Stop can run while network requests are in progress.
            if (!captureStarted || receiverSessions != sessions) return
            speakerVolumes = if (failed.isEmpty()) desired else SpeakerVolumes.from(levels)
            currentVolume = speakerVolumes.group / 100f
            val preferences = PreferencesManager(this)
            preferences.saveVolume(currentVolume)
            speakerVolumes.levels.forEach { (receiver, level) ->
                preferences.saveReceiverVolume(receiver, level / 100f)
            }
        }
        sendReceiverStateBroadcast(
            error = failed.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "Could not change volume on ",
            ),
            volumeRequestId = requestId,
        )
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

        fun synchronizeAtNanos(unixTimeNanos: Long) {
            airPlay1?.synchronizeAtNanos(unixTimeNanos)
            airPlay2?.synchronizeAtNanos(unixTimeNanos)
        }

        fun shiftDelay(deltaMillis: Int) {
            if (deltaMillis == 0) return
            airPlay1?.shiftDelay(deltaMillis)
            airPlay2?.shiftDelay(deltaMillis)
        }

        fun setVolume(volume: Float) {
            airPlay1?.setVolume(volume, strict = true)
            airPlay2?.setVolume(volume, strict = true)
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
        const val ACTION_REFRESH_PHONE_MUTE = "com.egron.lampan.REFRESH_PHONE_MUTE"
        const val ACTION_SET_RECEIVER_DELAY = "com.egron.lampan.SET_RECEIVER_DELAY"
        const val ACTION_SET_GROUP_DELAYS = "com.egron.lampan.SET_GROUP_DELAYS"
        const val EXTRA_ACTIVE_RECEIVERS = "ACTIVE_RECEIVERS"
        const val EXTRA_RECEIVER_ERROR = "RECEIVER_ERROR"
        const val EXTRA_IS_STREAMING = "IS_STREAMING"
        const val EXTRA_CURRENT_VOLUME = "CURRENT_VOLUME"
        const val EXTRA_PHONE_MEDIA_MUTED = "PHONE_MEDIA_MUTED"
        const val EXTRA_VOLUME_RECEIVER = "VOLUME_RECEIVER"
        const val EXTRA_VOLUME_REQUEST_ID = "VOLUME_REQUEST_ID"
        const val EXTRA_VOLUME_RECEIVERS = "VOLUME_RECEIVERS"
        const val EXTRA_RECEIVER_VOLUMES = "RECEIVER_VOLUMES"
        const val EXTRA_DELAY_RECEIVER = "DELAY_RECEIVER"
        const val EXTRA_DELAY_RECEIVERS = "DELAY_RECEIVERS"
        const val EXTRA_RECEIVER_DELAY_MS = "RECEIVER_DELAY_MS"
        const val EXTRA_RECEIVER_DELAYS_MS = "RECEIVER_DELAYS_MS"

        const val GROUP_START_DELAY_MS = 250L
        private const val DEFAULT_INITIAL_VOLUME = 0.5f
        private const val DEFAULT_TRANSIENT_PASSWORD = "3939"
    }
}
