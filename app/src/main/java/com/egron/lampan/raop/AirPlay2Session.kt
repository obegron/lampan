package com.egron.lampan.raop

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/** Native AirPlay 2 realtime audio session using NTP timing and encrypted ALAC RTP. */
class AirPlay2Session(
    private val host: String,
    private val port: Int = 7000,
    private val credentials: HomeKitPairingCredentials?,
    private val password: String?,
    private val onCredentialsCreated: (HomeKitPairingCredentials) -> Unit,
    private val log: (String) -> Unit = {},
    private val transientPairing: Boolean = false,
    private val onTransientPasswordAuthenticated: () -> Unit = {},
) {
    private val random = SecureRandom()
    private val encoder = AlacEncoder()
    private val sessionId = random.nextInt()
    private val sessionIdUnsigned = sessionId.toLong() and 0xFFFF_FFFFL
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    private var sequence = random.nextInt(0x1_0000)
    private var rtpTimestamp = RENDER_LATENCY_FRAMES.toLong()
    private var packetCount = 0L
    private var metadataSent = false
    private var pendingPcm = ByteArray(0)
    private var connection: AirPlay2Connection? = null
    private var audioCipher: AirPlay2AudioPacketCipher? = null
    private var serverAddress: InetAddress? = null
    private var serverDataPort = -1
    private var serverControlPort = -1
    private var eventSocket: Socket? = null
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    @Volatile
    private var running = false

    fun connect(
        initialVolume: Float = 1.0f,
        synchronizeImmediately: Boolean = true,
    ) {
        check(connection == null) { "AirPlay 2 session is already connected" }
        serverAddress = InetAddress.getByName(host)
        timingSocket = datagramSocket()
        controlSocket = datagramSocket()
        dataSocket = datagramSocket()

        try {
            val connected = when {
                transientPairing -> {
                    val transient = AirPlay2Client(host, port, log).connectTransient(
                        password ?: DEFAULT_TRANSIENT_PASSWORD,
                    )
                    onTransientPasswordAuthenticated()
                    transient
                }
                credentials != null -> {
                    AirPlay2Client(host, port, log).connect(
                        credentials = credentials,
                        password = null,
                        onCredentialsCreated = onCredentialsCreated,
                    ).connection
                }
                else -> {
                    val pairingPassword = password ?: throw AirPlay2ConnectionException(
                        "A receiver password or displayed setup code is required for first pairing",
                    )
                    try {
                        log("[AP2] Trying transient password pairing")
                        val transient =
                            AirPlay2Client(host, port, log).connectTransient(pairingPassword)
                        onTransientPasswordAuthenticated()
                        transient
                    } catch (transientError: Exception) {
                        log(
                            "[AP2] Transient pairing was not accepted; trying registered pairing " +
                                "(${transientError.message})",
                        )
                        AirPlay2Client(host, port, log).connect(
                            credentials = null,
                            password = pairingPassword,
                            onCredentialsCreated = onCredentialsCreated,
                        ).connection
                    }
                }
            }
            connection = connected
            audioCipher = AirPlay2AudioPacketCipher(
                connected.sessionKeys.sharedSecret.copyOfRange(0, 32),
            )
            running = true
            startTimingResponder()
            startRetransmitResponder()
            setupSession(connected)
            startFeedbackWorker(connected)
            setVolume(initialVolume)
            if (synchronizeImmediately) {
                sendSyncPacket(first = true)
                sendInitialMetadata(connected)
            }
            log("[AP2] Native realtime audio session ready; starting phone capture")
        } catch (error: Exception) {
            closeResources(sendTeardown = false)
            throw error
        }
    }

    private fun setupSession(connected: AirPlay2Connection) {
        val localAddress = connected.client.getLocalAddress()?.hostAddress
            ?: throw AirPlay2ConnectionException("Could not determine the phone's local address")
        val sessionUri = sessionUri(localAddress)
        val setupHeaders = connected.headers + mapOf(
            "Content-Type" to BINARY_PLIST,
            "X-Apple-StreamID" to "1",
        )

        val sessionBody = NSDictionary().apply {
            put("deviceID", connected.headers.getValue("X-Apple-Device-ID"))
            put("sessionUUID", sessionUuid)
            put("timingPort", requireNotNull(timingSocket).localPort)
            put("timingProtocol", "NTP")
            put("name", "Lampan")
            put("macAddress", connected.headers.getValue("X-Apple-Device-ID"))
            put("sourceVersion", "550.10")
        }
        log("[AP2] Sending NTP session SETUP (timing port ${timingSocket?.localPort})")
        val sessionResponse = checked(
            connected.client.sendRequest(
                "SETUP",
                sessionUri,
                setupHeaders,
                rawBody = BinaryPropertyListWriter.writeToArray(sessionBody),
            ),
            "session SETUP",
        )
        val sessionPlist = parseDictionary(connected.client, sessionResponse.rawBody, "session SETUP")
        val eventPort = sessionPlist.number("eventPort")?.intValue() ?: 0
        log("[AP2] Session SETUP accepted; event port $eventPort")
        if (eventPort in 1..0xFFFF) {
            openEventChannel(connected, eventPort)
        } else {
            log("[AP2] Receiver returned no event port; continuing without reverse events")
        }

        log("[AP2] Sending RECORD before stream SETUP")
        val record = connected.client.sendRequest("RECORD", sessionUri, connected.headers)
        if (record.code !in 200..299) {
            log("[AP2] RECORD returned RTSP ${record.code}; attempting stream SETUP anyway")
        } else {
            log("[AP2] RECORD accepted")
        }

        val stream = NSDictionary().apply {
            put("audioFormat", AUDIO_FORMAT_ALAC_44K_16_STEREO)
            put("audioMode", "default")
            put("controlPort", requireNotNull(controlSocket).localPort)
            put("dataPort", requireNotNull(dataSocket).localPort)
            put("ct", 2)
            put("isMedia", true)
            put("latencyMax", RENDER_LATENCY_FRAMES)
            put("latencyMin", MIN_LATENCY_FRAMES)
            put("shk", NSData(connected.sessionKeys.sharedSecret.copyOfRange(0, 32)))
            put("spf", FRAMES_PER_PACKET)
            put("sr", SAMPLE_RATE)
            put("streamConnectionID", sessionIdUnsigned)
            put("supportsDynamicStreamID", false)
            put("type", RTP_PAYLOAD_TYPE)
        }
        val streamBody = NSDictionary().apply {
            put("streams", NSArray(stream))
        }
        log(
            "[AP2] Sending realtime ALAC stream SETUP " +
                "(data ${dataSocket?.localPort}, control ${controlSocket?.localPort})",
        )
        val streamResponse = checked(
            connected.client.sendRequest(
                "SETUP",
                sessionUri,
                setupHeaders,
                rawBody = BinaryPropertyListWriter.writeToArray(streamBody),
            ),
            "stream SETUP",
        )
        val root = parseDictionary(connected.client, streamResponse.rawBody, "stream SETUP")
        val streams = root["streams"] as? NSArray
            ?: throw AirPlay2ConnectionException("Stream SETUP response had no streams array")
        val responseStream = streams.objectAtIndex(0) as? NSDictionary
            ?: throw AirPlay2ConnectionException("Stream SETUP response had no stream dictionary")
        serverDataPort = responseStream.number("dataPort")?.intValue() ?: -1
        serverControlPort = responseStream.number("controlPort")?.intValue() ?: serverDataPort
        if (serverDataPort !in 1..0xFFFF) {
            throw AirPlay2ConnectionException("Stream SETUP returned no valid data port")
        }
        if (serverControlPort !in 1..0xFFFF) serverControlPort = serverDataPort
        log(
            "[AP2] Stream SETUP accepted; receiver data=$serverDataPort, " +
                "control=$serverControlPort",
        )
    }

    private fun openEventChannel(connected: AirPlay2Connection, eventPort: Int) {
        log("[AP2] Opening encrypted reverse event channel to $host:$eventPort")
        val socket = Socket().apply {
            connect(InetSocketAddress(host, eventPort), SOCKET_TIMEOUT_MS)
            tcpNoDelay = true
        }
        eventSocket = socket
        val channel = HomeKitRtspChannel(
            HomeKitControlCipher(
                writeKey = connected.sessionKeys.eventReadKey,
                readKey = connected.sessionKeys.eventWriteKey,
            ),
        )
        thread(name = "Lampan-AirPlay2-Events", isDaemon = true) {
            try {
                while (running && !socket.isClosed) {
                    val request = channel.readMessage(socket.getInputStream())
                    val cseq = rtspHeader(request, "CSeq")
                    log("[AP2] Receiver event: ${rtspStartLine(request)}")
                    val response = buildString {
                        append("RTSP/1.0 200 OK\r\n")
                        append("Server: AirTunes/550.10\r\n")
                        if (cseq != null) append("CSeq: $cseq\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    socket.getOutputStream().write(channel.encrypt(response))
                    socket.getOutputStream().flush()
                }
            } catch (error: Exception) {
                if (running && error !is SocketException) {
                    log("[AP2] Event channel stopped: ${error.message}")
                }
            }
        }
        log("[AP2] Event channel connected")
    }

    @Synchronized
    fun sendFrame(pcm: ByteArray) {
        if (!running || serverDataPort < 0 || pcm.isEmpty()) return
        pendingPcm = HomeKitCrypto.concat(pendingPcm, pcm)
        while (pendingPcm.size >= PCM_BYTES_PER_PACKET) {
            val frame = pendingPcm.copyOfRange(0, PCM_BYTES_PER_PACKET)
            pendingPcm = pendingPcm.copyOfRange(PCM_BYTES_PER_PACKET, pendingPcm.size)
            sendAudioPacket(frame)
        }
    }

    private fun sendAudioPacket(pcm: ByteArray) {
        try {
            if (packetCount > 0 && packetCount % SYNC_INTERVAL_PACKETS == 0L) {
                sendSyncPacket(first = false)
            }
            val packet = requireNotNull(audioCipher).encode(
                sequence = sequence,
                timestamp = rtpTimestamp,
                ssrc = sessionId,
                alacPayload = encoder.encodeAirPlay2(pcm),
                marker = packetCount == 0L,
            )
            requireNotNull(dataSocket).send(
                DatagramPacket(packet, packet.size, serverAddress, serverDataPort),
            )
            storeRetransmit(sequence, packet)
            sequence = (sequence + 1) and 0xFFFF
            rtpTimestamp = (rtpTimestamp + FRAMES_PER_PACKET) and 0xFFFF_FFFFL
            packetCount++
            if (packetCount == 1L) log("[AP2] First encrypted audio packet sent")
        } catch (error: Exception) {
            log("[AP2] Audio send failed: ${error.message}")
        }
    }

    fun setVolume(volume: Float) {
        val connected = connection ?: return
        val clamped = volume.coerceIn(0f, 1f)
        val decibels = if (clamped == 0f) -144f else clamped * 30f - 30f
        val response = connected.client.sendRequest(
            "SET_PARAMETER",
            sessionUri(connected.client.getLocalAddress()?.hostAddress ?: return),
            connected.headers + ("Content-Type" to "text/parameters"),
            body = "volume: %.6f\r\n".format(Locale.US, decibels),
            logExchange = false,
        )
        log("[AP2] Volume %.2f dB (RTSP ${response.code})".format(Locale.US, decibels))
    }

    /** Give several prepared sessions the exact same RTP-to-NTP start mapping. */
    fun synchronizeAt(unixTimeMillis: Long) {
        check(running && serverControlPort in 1..0xFFFF) {
            "AirPlay 2 session must be connected before synchronization"
        }
        sendSyncPacket(first = true, ntpTimestamp = ntpTime(unixTimeMillis))
        connection?.let(::sendInitialMetadata)
    }

    /** Sonos-class receivers may acknowledge audio SETUP but wait for a track announcement. */
    private fun sendInitialMetadata(connected: AirPlay2Connection) {
        if (metadataSent) return
        val response = connected.client.sendRequest(
            "SET_PARAMETER",
            sessionUri(connected.client.getLocalAddress()?.hostAddress ?: return),
            connected.headers + mapOf(
                "Content-Type" to "application/x-dmap-tagged",
                "RTP-Info" to "rtptime=$rtpTimestamp",
            ),
            rawBody = buildAirPlayDmapMetadata(title = "Lampan"),
            logExchange = false,
        )
        if (response.code in 200..299) {
            metadataSent = true
            log("[AP2] Initial track metadata accepted")
        } else {
            log("[AP2] Initial track metadata returned RTSP ${response.code}")
        }
    }

    fun stop() {
        closeResources(sendTeardown = true)
    }

    private fun closeResources(sendTeardown: Boolean) {
        val connected = connection
        running = false
        if (sendTeardown && connected != null) {
            try {
                val local = connected.client.getLocalAddress()?.hostAddress
                if (local != null) {
                    connected.client.sendRequest("TEARDOWN", sessionUri(local), connected.headers)
                }
            } catch (error: Exception) {
                log("[AP2] TEARDOWN failed: ${error.message}")
            }
        }
        eventSocket?.close()
        timingSocket?.close()
        controlSocket?.close()
        dataSocket?.close()
        connected?.close()
        eventSocket = null
        timingSocket = null
        controlSocket = null
        dataSocket = null
        connection = null
        audioCipher = null
        serverDataPort = -1
        serverControlPort = -1
        pendingPcm = ByteArray(0)
        metadataSent = false
        log("[AP2] Session closed")
    }

    private fun startFeedbackWorker(connected: AirPlay2Connection) {
        thread(name = "Lampan-AirPlay2-Feedback", isDaemon = true) {
            var successfulFeedbackChecks = 0
            while (running) {
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                    if (!running) break
                    val response = connected.client.sendRequest(
                        "POST",
                        "/feedback",
                        connected.headers,
                        logExchange = false,
                    )
                    if (response.code !in 200..299) {
                        log("[AP2] Feedback returned RTSP ${response.code}")
                    } else {
                        successfulFeedbackChecks++
                        if (successfulFeedbackChecks % FEEDBACK_HEALTH_LOG_INTERVAL == 0) {
                            log(
                                "[AP2] Stream healthy " +
                                    "($successfulFeedbackChecks feedback checks acknowledged)",
                            )
                        }
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (error: Exception) {
                    if (running) log("[AP2] Feedback failed: ${error.message}")
                    break
                }
            }
        }
    }

    private fun startTimingResponder() {
        val socket = requireNotNull(timingSocket)
        thread(name = "Lampan-AirPlay2-Timing", isDaemon = true) {
            val buffer = ByteArray(2_048)
            while (running && !socket.isClosed) {
                try {
                    val request = DatagramPacket(buffer, buffer.size)
                    socket.receive(request)
                    if (request.length < 32 || (buffer[1].toInt() and 0x7F) != 0x52) continue
                    val now = ntpTime()
                    val response = ByteBuffer.allocate(32).apply {
                        put(buffer[0])
                        put(0xD3.toByte())
                        putShort(7)
                        putInt(0)
                        put(buffer, 24, 8)
                        putLong(now)
                        putLong(now)
                    }.array()
                    socket.send(DatagramPacket(response, response.size, request.address, request.port))
                } catch (_: SocketTimeoutException) {
                    // Periodically observe [running].
                } catch (error: Exception) {
                    if (running && error !is SocketException) {
                        log("[AP2] Timing responder stopped: ${error.message}")
                    }
                }
            }
        }
    }

    private fun sendSyncPacket(
        first: Boolean,
        ntpTimestamp: Long = ntpTime(),
    ) {
        if (serverControlPort !in 1..0xFFFF) return
        try {
            val current = rtpTimestamp.toInt()
            val response = ByteBuffer.allocate(20).apply {
                put(if (first) 0x90.toByte() else 0x80.toByte())
                put(0xD4.toByte())
                putShort(7)
                putInt(current - RENDER_LATENCY_FRAMES)
                putLong(ntpTimestamp)
                putInt(current)
            }.array()
            requireNotNull(controlSocket).send(
                DatagramPacket(response, response.size, serverAddress, serverControlPort),
            )
        } catch (error: Exception) {
            log("[AP2] Sync send failed: ${error.message}")
        }
    }

    private fun startRetransmitResponder() {
        val socket = requireNotNull(controlSocket)
        thread(name = "Lampan-AirPlay2-Retransmit", isDaemon = true) {
            val buffer = ByteArray(2_048)
            while (running && !socket.isClosed) {
                try {
                    val request = DatagramPacket(buffer, buffer.size)
                    socket.receive(request)
                    if (request.length < 8 || (buffer[1].toInt() and 0x7F) != 0x55) continue
                    val first = ((buffer[4].toInt() and 0xFF) shl 8) or
                        (buffer[5].toInt() and 0xFF)
                    val count = ((buffer[6].toInt() and 0xFF) shl 8) or
                        (buffer[7].toInt() and 0xFF)
                    for (offset in 0 until count) {
                        val requested = (first + offset) and 0xFFFF
                        val slot = requested and (RETRANSMIT_SLOTS - 1)
                        val original = synchronized(retransmitPackets) {
                            if (retransmitSequences[slot] == requested) {
                                retransmitPackets[slot]?.copyOf()
                            } else {
                                null
                            }
                        } ?: continue
                        val response = ByteBuffer.allocate(4 + original.size).apply {
                            put(0x80.toByte())
                            put(0xD6.toByte())
                            putShort(requested.toShort())
                            put(original)
                        }.array()
                        socket.send(
                            DatagramPacket(response, response.size, request.address, request.port),
                        )
                    }
                    log("[AP2] Retransmit request: first=$first count=$count")
                } catch (_: SocketTimeoutException) {
                    // Periodically observe [running].
                } catch (error: Exception) {
                    if (running && error !is SocketException) {
                        log("[AP2] Retransmit responder stopped: ${error.message}")
                    }
                }
            }
        }
    }

    private val retransmitPackets = arrayOfNulls<ByteArray>(RETRANSMIT_SLOTS)
    private val retransmitSequences = IntArray(RETRANSMIT_SLOTS) { -1 }

    private fun storeRetransmit(sentSequence: Int, packet: ByteArray) {
        val slot = sentSequence and (RETRANSMIT_SLOTS - 1)
        synchronized(retransmitPackets) {
            retransmitSequences[slot] = sentSequence
            retransmitPackets[slot] = packet.copyOf()
        }
    }

    private fun datagramSocket(): DatagramSocket = DatagramSocket(0).apply {
        soTimeout = DATAGRAM_TIMEOUT_MS
    }

    private fun checked(response: RtspResponse, step: String): RtspResponse {
        if (response.code !in 200..299) {
            throw AirPlay2ConnectionException("$step returned RTSP ${response.code}")
        }
        return response
    }

    private fun parseDictionary(
        client: RtspClient,
        data: ByteArray,
        step: String,
    ): NSDictionary {
        if (data.isEmpty()) throw AirPlay2ConnectionException("$step returned no plist body")
        return try {
            client.parseBinaryPlist(data)
        } catch (error: Exception) {
            throw AirPlay2ConnectionException("$step returned an invalid binary plist", error)
        }
    }

    private fun NSDictionary.number(key: String): NSNumber? = this[key] as? NSNumber

    private fun sessionUri(localAddress: String): String =
        "rtsp://$localAddress/$sessionIdUnsigned"

    private fun rtspStartLine(message: ByteArray): String =
        String(message, Charsets.ISO_8859_1).lineSequence().firstOrNull() ?: "RTSP event"

    private fun rtspHeader(message: ByteArray, name: String): String? =
        String(message, Charsets.ISO_8859_1).lineSequence()
            .firstOrNull { it.substringBefore(':').equals(name, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun ntpTime(millis: Long = System.currentTimeMillis()): Long {
        val seconds = millis / 1_000 + NTP_UNIX_EPOCH_OFFSET
        val fraction = (millis % 1_000) * 0x1_0000_0000L / 1_000
        return (seconds shl 32) or fraction
    }

    private companion object {
        const val BINARY_PLIST = "application/x-apple-binary-plist"
        const val SAMPLE_RATE = 44_100
        const val FRAMES_PER_PACKET = 352
        const val PCM_BYTES_PER_PACKET = FRAMES_PER_PACKET * 2 * 2
        const val RTP_PAYLOAD_TYPE = 96
        const val AUDIO_FORMAT_ALAC_44K_16_STEREO = 0x40000
        const val MIN_LATENCY_FRAMES = 11_025
        const val RENDER_LATENCY_FRAMES = 88_200
        const val SYNC_INTERVAL_PACKETS = 125L
        const val RETRANSMIT_SLOTS = 512
        const val DATAGRAM_TIMEOUT_MS = 1_000
        const val SOCKET_TIMEOUT_MS = 5_000
        const val FEEDBACK_INTERVAL_MS = 2_000L
        const val FEEDBACK_HEALTH_LOG_INTERVAL = 30
        const val NTP_UNIX_EPOCH_OFFSET = 2_208_988_800L
        const val DEFAULT_TRANSIENT_PASSWORD = "3939"
    }
}

/** Build the small DMAP item used to announce a playable audio track. */
internal fun buildAirPlayDmapMetadata(
    title: String,
    artist: String = "",
    album: String = "",
): ByteArray {
    val item = ByteArrayOutputStream()
    item.write(dmapAtom("mikd", byteArrayOf(2)))
    item.write(dmapAtom("minm", title.toByteArray(Charsets.UTF_8)))
    item.write(dmapAtom("asar", artist.toByteArray(Charsets.UTF_8)))
    item.write(dmapAtom("asal", album.toByteArray(Charsets.UTF_8)))
    item.write(dmapAtom("astn", byteArrayOf(0, 1)))
    return dmapAtom("mlit", item.toByteArray())
}

private fun dmapAtom(tag: String, value: ByteArray): ByteArray {
    require(tag.length == 4) { "DMAP tags must contain four ASCII characters" }
    return ByteBuffer.allocate(8 + value.size).apply {
        put(tag.toByteArray(Charsets.US_ASCII))
        putInt(value.size)
        put(value)
    }.array()
}
