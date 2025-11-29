package com.egron.lampan.raop

import android.util.Log
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Security
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RaopSession(private var host: String, private val port: Int = 7000, private val logCallback: ((String) -> Unit)? = null) {
    private val TAG = "RaopSession"
    private val client = RtspClient(host, port, logCallback)
    private var sessionId: String? = null
    private var clientInstance: String? = null
    private var activeRemote: String? = null
    private var serverSession: String? = null 
    private var clientControlSocket: DatagramSocket? = null
    private var clientTimingSocket: DatagramSocket? = null
    private var clientAudioSocket: DatagramSocket? = null
    private var syncJob: kotlinx.coroutines.Job? = null
    
    private var serverAudioPort: Int = -1
    private var serverControlPort: Int = -1
    private var serverTimingPort: Int = -1
    private var serverAddress: InetAddress? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var rtpSeqNum = 0
    private var rtpTimestamp = 0L
    private val random = Random() 
    
    private val alacEncoder = AlacEncoder()
    private val aesKey = ByteArray(16)
    private val aesIv = ByteArray(16)
    private var aesCipher: Cipher? = null
    private var aesKeySpec: SecretKeySpec? = null
    private var aesIvSpec: IvParameterSpec? = null

    // Hardcoded RSA Public Key for AirPlay (Apple's Airport Express Key)
    private val rsaModulusBase64 = "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="
    private val rsaExponentBase64 = "AQAB"

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // New method for integration tests to set internal state directly
    fun setupForTest(testClientIp: String, testSessionId: String, testClientInstance: String, testHost: String, testServerAudioPort: Int, testServerSession: String?, testClientAudioSocket: DatagramSocket?) {
        this.host = testHost 
        this.sessionId = testSessionId
        this.clientInstance = testClientInstance
        this.serverAudioPort = testServerAudioPort
        this.serverSession = testServerSession
        this.clientAudioSocket = testClientAudioSocket
    }

    suspend fun connect() {
        client.connect() 
        
        if (sessionId == null) { 
            sessionId = (Math.abs(random.nextInt()) % 1000000000 + 100000000).toString() 
        } 
        
        if (clientInstance == null) { 
            val clientInstanceBytes = ByteArray(8)
            random.nextBytes(clientInstanceBytes)
            clientInstance = clientInstanceBytes.joinToString("") { "%02X".format(it) }
        }
        if (activeRemote == null) { 
            activeRemote = Math.abs(random.nextInt()).toString() 
        } 
        
        val clientIp = getLocalIpAddress() ?: "127.0.0.1"
        log("RaopSession: Client IP is $clientIp, Target: $host:$port")

        // Resolve host once
        serverAddress = InetAddress.getByName(host)

        val options = client.sendRequest("OPTIONS", "*", mapOf(
            "User-Agent" to "PipeWire/1.4.9",
            "DACP-ID" to clientInstance!!,
            "Client-Instance" to clientInstance!!
        ))
        log("OPTIONS code ${options.code}")

        // Setup Encryption (AES + RSA)
        // setupEncryption()
        // val rsaAesKeyEncoded = encryptAesKeyWithRsa()
        //val aesIvEncoded = Base64.getEncoder().encodeToString(aesIv).trim()
        //val appleChallengeEncoded = generateAppleChallenge()

        // Standard AirPlay 1 SDP
        val rtpMapLine = "a=rtpmap:96 AppleLossless"
        val fmtpLine = "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100"

        val sdp = "v=0\r\n" +
             "o=iTunes $sessionId 0 IN IP4 $clientIp\r\n" +
             "s=iTunes\r\n" +
             "c=IN IP4 $host\r\n" + 
             "t=0 0\r\n" +
             "m=audio 0 RTP/AVP 96\r\n" +
             "$rtpMapLine\r\n" +
             "$fmtpLine\r\n" +
             "a=min-latency:11025"
        log("SDP Content:\n$sdp")

        val announceHeaders = mapOf(
            "Content-Type" to "application/sdp",
            "Client-Instance" to clientInstance!!,
            "DACP-ID" to clientInstance!!,
            "User-Agent" to "PipeWire/1.4.9",
        )

        val announce = client.sendRequest("ANNOUNCE", "rtsp://$clientIp/$sessionId", 
            announceHeaders, sdp)
            
        if (announce.code != 200) {
            val errorMsg = "ANNOUNCE failed: ${announce.code}"
            log(errorMsg)
            // Proceeding anyway as some devices might be lax, but usually this is fatal
        }
        
        delay(200) 

        // Only create sockets if not set by test
        if (clientControlSocket == null) clientControlSocket = DatagramSocket(0)
        if (clientTimingSocket == null) clientTimingSocket = DatagramSocket(0)
        if (clientAudioSocket == null) clientAudioSocket = DatagramSocket(0)
        
        val clientControlPort = clientControlSocket!!.localPort
        val clientTimingPort = clientTimingSocket!!.localPort
        
        log("Opened UDP ports: Control=$clientControlPort, Timing=$clientTimingPort (Wildcard Binding)")
        
        startUdpListener(clientControlSocket!!, "Control")
        startUdpListener(clientTimingSocket!!, "Timing")
        // startSyncSender() removed from here

        val setupHeaders = mapOf(
             "Client-Instance" to clientInstance!!,
             "DACP-ID" to clientInstance!!,
             "User-Agent" to "PipeWire/1.4.9",
             "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=$clientControlPort;timing_port=$clientTimingPort"
        )
        
        val setup = client.sendRequest("SETUP", "rtsp://$clientIp/$sessionId", setupHeaders)
        if (setup.code != 200) {
            val errorMsg = "SETUP failed: ${setup.code}"
            log(errorMsg)
            throw Exception(errorMsg)
        }
        
        val serverTransportHeader = setup.headers["Transport"]
        if (serverTransportHeader != null) {
            val parts = serverTransportHeader.split(";")
            for (part in parts) {
                if (part.startsWith("server_port=")) serverAudioPort = part.substringAfter("=").toInt()
                if (part.startsWith("control_port=")) serverControlPort = part.substringAfter("=").toInt()
                if (part.startsWith("timing_port=")) serverTimingPort = part.substringAfter("=").toInt()
            }
            log("Server ports: Audio=$serverAudioPort, Control=$serverControlPort, Timing=$serverTimingPort")
        }

        serverSession = setup.headers["Session"]
        if (serverSession != null) {
            log("Server Session ID: $serverSession")
        }
        
        // Send initial sync and silence to prime receiver
        startSyncSender()
        log("Sending pre-record silence...")
        val silencePcm = ByteArray(352 * 4) // 352 samples * 4 bytes (16-bit stereo)
        for (i in 0 until 10) {
            sendFrame(silencePcm)
            delay(8)
        }
        sendSync()
        
        val recordHeaders = mutableMapOf(
             "Client-Instance" to clientInstance!!,
             "DACP-ID" to clientInstance!!,
             "User-Agent" to "PipeWire/1.4.9",
             "Range" to "npt=0-",
             "RTP-Info" to "seq=$rtpSeqNum;rtptime=$rtpTimestamp"
        )
        if (serverSession != null) {
            recordHeaders["Session"] = serverSession!!
        } else {
            log("RECORD request sent without Session ID.")
        }
        
        val record = client.sendRequest("RECORD", "rtsp://$clientIp/$sessionId", recordHeaders)
        if (record.code != 200) {
            val errorMsg = "RECORD failed: ${record.code}"
            log(errorMsg)
            throw Exception(errorMsg)
        }
        
        // Send ~2 seconds of silence (250 packets of 352 samples) to wake up receiver / sync
        log("Sending silence burst...")
        for (i in 0 until 250) {
            sendFrame(silencePcm)
            delay(8) // ~7.98ms per frame
        }
        log("Silence burst finished.")
        sendSync()
        
        // Set initial volume to ~-10dB (0.66 * 30 - 30 = -10.2)
        // 0dB might be too loud or ignored.
        delay(100)
        setVolume(0.66f)
        log("Initial volume set to -10dB (approx)")
    }

    private fun setupEncryption() {
        SecureRandom().nextBytes(aesKey)
        SecureRandom().nextBytes(aesIv)
        
        try {
            aesKeySpec = SecretKeySpec(aesKey, "AES")
            aesIvSpec = IvParameterSpec(aesIv)
            aesCipher = Cipher.getInstance("AES/CBC/NoPadding")
            aesCipher!!.init(Cipher.ENCRYPT_MODE, aesKeySpec, aesIvSpec)
        } catch (e: Exception) {
            log("Error initializing AES cipher: ${e.message}")
            e.printStackTrace()
        }
    }

private fun encryptAesKeyWithRsa(): String {
    try {
        val modulusBytes = Base64.getDecoder().decode(rsaModulusBase64)
        val exponentBytes = Base64.getDecoder().decode(rsaExponentBase64)

        // BigInteger constructor: BigInteger(signum, magnitude)
        // signum=1 means positive, but magnitude must not be null or have leading zeros
        val modulus = BigInteger(1, modulusBytes)
        val exponent = BigInteger(1, exponentBytes)

        val spec = RSAPublicKeySpec(modulus, exponent)
        val factory = KeyFactory.getInstance("RSA")
        val pubKey = factory.generatePublic(spec)

        // Use BouncyCastle provider for OAEP padding
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding", "BC")
        rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encryptedKey = rsaCipher.doFinal(aesKey)

        return Base64.getEncoder().encodeToString(encryptedKey).trim()
    } catch (e: Exception) {
        log("RSA Encryption failed: ${e.message}")
        e.printStackTrace()
        throw e // Don't return empty string, let it fail loudly
    }
}

    private fun generateAppleChallenge(): String {
        val challenge = ByteArray(16)
        SecureRandom().nextBytes(challenge)
        return Base64.getEncoder().withoutPadding().encodeToString(challenge).trim()
    }

    private fun startUdpListener(socket: DatagramSocket, name: String) {
        scope.launch {
            log("UDP $name listener started on port ${socket.localPort}")
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (!socket.isClosed) {
                    packet.length = buffer.size // Reset length
                    socket.receive(packet)
                    val len = packet.length
                    val hex = buffer.take(minOf(len, 16)).joinToString("") { "%02x".format(it) }
                    log("UDP $name received $len bytes from ${packet.address}:${packet.port}: $hex...")
                    
                    if (name == "Timing" && len >= 32 && buffer[0] == 0x80.toByte() && buffer[1] == 0xD2.toByte()) {
                        val response = ByteArray(32)
                        response[0] = 0x80.toByte()
                        response[1] = 0xD3.toByte()
                        response[2] = 0x00; response[3] = 0x00
                        System.arraycopy(buffer, 24, response, 8, 8)
                        val nowNtp = ntpTime()
                        putLong(response, 16, nowNtp)
                        putLong(response, 24, nowNtp)
                        val reply = DatagramPacket(response, 32, packet.address, packet.port)
                        socket.send(reply)
                        log("Sent Timing Reply (0xD3) to ${packet.address}:${packet.port}")
                    }
                }
            } catch (e: Exception) {
                if (!socket.isClosed) {
                    log("UDP $name listener error: ${e.message}")
                }
            }
        }
    }
    
    private fun ntpTime(): Long {
        val offset = 2208988800L
        val now = System.currentTimeMillis()
        val seconds = (now / 1000) + offset
        val fraction = ((now % 1000) * 4294967296L / 1000)
        return (seconds shl 32) or fraction
    }
    
    private fun putLong(buffer: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 7 downTo 0) {
            buffer[offset + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
    }

    fun sendFrame(pcm: ByteArray) {
        if (serverAudioPort == -1 || clientAudioSocket == null) return

        try {
            // 1. Encode PCM to ALAC (Raw PCM wrapped in ALAC)
            val alacData = alacEncoder.encode(pcm, 352) 
            // 352 samples is standard frame size for AirPlay. 
            // Input pcm should be 352 * 4 = 1408 bytes.
            
            // 2. Encrypt using AES-CBC, but only multiples of 16 bytes
            val encryptedData = alacData.clone()
            val lenToEncrypt = alacData.size and 0x0F.inv() // Clear last 4 bits -> multiple of 16
            
            if (lenToEncrypt > 0 && aesCipher != null && aesKeySpec != null && aesIvSpec != null) {
                 // Re-init cipher to ensure IV is reset/reused per packet as per AirPlay spec
                 aesCipher!!.init(Cipher.ENCRYPT_MODE, aesKeySpec, aesIvSpec)
                 val cipherOutput = aesCipher!!.doFinal(encryptedData, 0, lenToEncrypt)
                 System.arraycopy(cipherOutput, 0, encryptedData, 0, cipherOutput.size)
                 // The remaining bytes (alacData.size - lenToEncrypt) are left as is in encryptedData
            }

            val packet = RtpPacket(
                payloadType = 96,
                seqNum = rtpSeqNum,
                timestamp = rtpTimestamp,
                ssrc = 0x12345678,
                data = encryptedData
            )
            
            val packetBytes = packet.toByteArray()
            if (serverAddress != null) {
                val dp = DatagramPacket(packetBytes, packetBytes.size, serverAddress!!, serverAudioPort)
                clientAudioSocket?.send(dp)
                
                if (rtpSeqNum < 5) {
                    log("Sent RTP Packet Seq=${rtpSeqNum} TS=$rtpTimestamp Len=${packetBytes.size} (Encrypted)")
                }
            }
            
            rtpSeqNum = (rtpSeqNum + 1) % 65536
            rtpTimestamp += 352 
            
        } catch (e: Exception) {
            log("Send error: ${e.message}")
        }
    }

    fun setVolume(vol: Float) {
        // Map 0.0-1.0 to -30.0 to 0.0 dB
        val clampedVol = vol.coerceIn(0.0f, 1.0f)
        val db = if (clampedVol == 0.0f) -144.0f else (clampedVol * 30.0f) - 30.0f
        
        try {
            // Format to match pcap style "volume: -8.699799"
            val content = "volume: %.6f".format(db)
            val headers = mutableMapOf(
                "Content-Type" to "text/parameters",
                "Client-Instance" to clientInstance!!,
                "DACP-ID" to clientInstance!!,
                "User-Agent" to "PipeWire/1.4.9"
            )
            if (serverSession != null) {
                headers["Session"] = serverSession!!
            }
            client.sendRequest("SET_PARAMETER", "rtsp://$host:$port/${sessionId!!}", headers, body = content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        syncJob?.cancel()
        try {
            val headers = mutableMapOf(
                "Client-Instance" to clientInstance!!,
                "DACP-ID" to clientInstance!!,
                "User-Agent" to "PipeWire/1.4.9"
            )
            if (serverSession != null) {
                headers["Session"] = serverSession!!
            }
            client.sendRequest("TEARDOWN", "rtsp://$host:$port/${sessionId!!}", headers)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        client.close()
        clientControlSocket?.close()
        clientTimingSocket?.close()
        clientAudioSocket?.close()
    }
    
    private fun startSyncSender() {
        syncJob = scope.launch {
            while (true) {
                sendSync()
                delay(3000)
            }
        }
    }

    private fun sendSync() {
        if (serverControlPort == -1 || clientControlSocket == null) return
        
        try {
            val buffer = ByteArray(20)
            // Header: 0x80 0xD4 0x00 0x04
            buffer[0] = 0x80.toByte()
            buffer[1] = 0xD4.toByte()
            buffer[2] = 0x00
            buffer[3] = 0x04
            
            val nowRtp = rtpTimestamp
            val nowNtp = ntpTime()
            
            // RTP Timestamp (active/next)
            putInt(buffer, 4, nowRtp.toInt())
            // NTP Timestamp
            putLong(buffer, 8, nowNtp)
            // RTP Timestamp (capture/now)
            putInt(buffer, 16, nowRtp.toInt())
            
            val dp = DatagramPacket(buffer, buffer.size, serverAddress!!, serverControlPort)
            clientControlSocket?.send(dp)
            // log("Sent Sync (0xD4) to Control Port $serverControlPort: ${toHex(buffer)}")
        } catch (e: Exception) {
            log("Sync error: ${e.message}")
        }
    }

    private fun putInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value shr 24).toByte()
        buffer[offset + 1] = (value shr 16).toByte()
        buffer[offset + 2] = (value shr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
    
    private fun generateCurve25519KeyPairBouncyCastle(): AsymmetricCipherKeyPair {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val kpGen = X25519KeyPairGenerator()
        kpGen.init(KeyGenerationParameters(SecureRandom(), 255))
        return kpGen.generateKeyPair()
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
