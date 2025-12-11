package com.egron.lampan.raop

import android.util.Log
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.Security
import java.util.Random
import javax.crypto.KeyAgreement
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    
    private var serverAudioPort: Int = -1
    private var serverControlPort: Int = -1
    private var serverTimingPort: Int = -1
    private var serverAddress: InetAddress? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var rtpSeqNum = 0
    private var rtpTimestamp = 0L
    private var ssrc = 0
    private var packetCount = 0L
    private var firstSyncSent = false
    private val encoder = AlacEncoder()
    private val random = Random() 

    // Force L16 PCM mode via "Uncompressed ALAC"
    private val useL16 = true
    private val latencySamples = 11025

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    // New method for integration tests to set internal state directly
    fun setupForTest(testSessionId: String, testClientInstance: String, testHost: String, testServerAudioPort: Int, testServerSession: String?, testClientAudioSocket: DatagramSocket?, testClientControlSocket: DatagramSocket?, testServerControlPort: Int) {
        this.host = testHost 
        this.sessionId = testSessionId
        this.clientInstance = testClientInstance
        this.serverAudioPort = testServerAudioPort
        this.serverControlPort = testServerControlPort
        this.serverSession = testServerSession
        this.clientAudioSocket = testClientAudioSocket
        this.clientControlSocket = testClientControlSocket
        try {
            this.serverAddress = InetAddress.getByName(testHost)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun connect(initialVolume: Float = 1.0f) {
        client.connect()
        
        if (sessionId == null) { 
            sessionId = (Math.abs(random.nextInt()) % 1000000000 + 100000000).toString() 
        }
        
        // Randomize RTP state
        rtpSeqNum = random.nextInt(65536)
        rtpTimestamp = Math.abs(random.nextLong())
        ssrc = random.nextInt()
        packetCount = 0
        firstSyncSent = false
        
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

        serverAddress = InetAddress.getByName(host)

        val options = client.sendRequest("OPTIONS", "*", mapOf(
            "User-Agent" to "Lampan/0.1.0",
            "DACP-ID" to clientInstance!!,
            "Client-Instance" to clientInstance!!
        ))
        log("OPTIONS code ${options.code}")

        val bcClientKeyPair = generateCurve25519KeyPairBouncyCastle()
        val clientPublicKeyBytes = (bcClientKeyPair.public as X25519PublicKeyParameters).encoded
        
        val authRequestBody = ByteArray(33)
        authRequestBody[0] = 0x01.toByte() 
        System.arraycopy(clientPublicKeyBytes, 0, authRequestBody, 1, 32)
        
        val authSetupHeaders = mapOf(
           "Content-Type" to "application/octet-stream",
           "Client-Instance" to clientInstance!!,
           "DACP-ID" to clientInstance!!,
           "User-Agent" to "Lampan/0.1.0"
        )
        
        val authSetup = client.sendRequest("POST", "/auth-setup", authSetupHeaders, rawBody = authRequestBody)
        if (authSetup.code != 200) {
            val errorMsg = "AUTH-SETUP failed: ${authSetup.code}"
            log(errorMsg)
            throw Exception(errorMsg)
        }
        
        delay(200)
        
        val serverCurve25519PublicKeyBytes = client.parseAuthSetupResponse(authSetup.rawBody)
        val bcAgreement = X25519Agreement()
        bcAgreement.init(bcClientKeyPair.private)
        val serverBCPublicKeyParams = X25519PublicKeyParameters(serverCurve25519PublicKeyBytes, 0)
        val sharedSecretBytes = ByteArray(bcAgreement.agreementSize)
        bcAgreement.calculateAgreement(serverBCPublicKeyParams, sharedSecretBytes, 0)
        
        log("Shared Secret (first 8 bytes): ${sharedSecretBytes.take(8).joinToString("") { "%02x".format(it) }}...")
        
        // Announce AppleLossless (ALAC)
        val rtpMapLine = "a=rtpmap:96 AppleLossless"

        val sdp = "v=0\r\n" +
             "o=iTunes $sessionId 0 IN IP4 $clientIp\r\n" +
             "s=iTunes\r\n" +
             "c=IN IP4 $host\r\n" + 
             "t=0 0\r\n" +
             "m=audio 0 RTP/AVP 96\r\n" +
             "$rtpMapLine\r\n" +
             "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n" +
             "a=min-latency:$latencySamples"

        val announceHeaders = mapOf(
            "Content-Type" to "application/sdp",
            "Client-Instance" to clientInstance!!,
            "DACP-ID" to clientInstance!!,
            "User-Agent" to "Lampan/0.1.0"
        )

        val announce = client.sendRequest("ANNOUNCE", "rtsp://$clientIp/$sessionId", 
            announceHeaders, sdp)
            
        if (announce.code != 200) {
            val errorMsg = "ANNOUNCE failed: ${announce.code}"
            log(errorMsg)
            throw Exception(errorMsg)
        }
        
        delay(200)

        if (clientControlSocket == null) clientControlSocket = DatagramSocket(0)
        if (clientTimingSocket == null) clientTimingSocket = DatagramSocket(0)
        if (clientAudioSocket == null) clientAudioSocket = DatagramSocket(0)
        
        val clientControlPort = clientControlSocket!!.localPort
        val clientTimingPort = clientTimingSocket!!.localPort
        
        log("Opened UDP ports: Control=$clientControlPort, Timing=$clientTimingPort (Wildcard Binding)")
        
        startUdpListener(clientControlSocket!!, "Control")
        startUdpListener(clientTimingSocket!!, "Timing")

        val setupHeaders = mapOf(
             "Client-Instance" to clientInstance!!,
             "DACP-ID" to clientInstance!!,
             "User-Agent" to "Lampan/0.1.0",
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
                val p = part.trim()
                if (p.startsWith("server_port=")) serverAudioPort = p.substringAfter("=").toInt()
                if (p.startsWith("control_port=")) serverControlPort = p.substringAfter("=").toInt()
                if (p.startsWith("timing_port=")) serverTimingPort = p.substringAfter("=").toInt()
            }
            log("Server ports: Audio=$serverAudioPort, Control=$serverControlPort, Timing=$serverTimingPort")
        }

        serverSession = setup.headers["Session"]
        if (serverSession != null) {
            log("Server Session ID: $serverSession")
        }
        
        val recordHeaders = mutableMapOf(
             "Client-Instance" to clientInstance!!,
             "DACP-ID" to clientInstance!!,
             "User-Agent" to "Lampan/0.1.0",
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
        
        // Send initial Sync packet immediately
        sendSyncPacket(true)
        firstSyncSent = true
        
        // Set initial volume
        delay(100)
        setVolume(initialVolume)
        log("Initial volume set to $initialVolume")
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

                    // log("UDP $name received $len bytes from ${packet.address}:${packet.port}: $hex...")
                    
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
                        // log("Sent Timing Reply (0xD3) to ${packet.address}:${packet.port}")
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

    // Send UDP Sync Packet (Type 84) to Control Port
    private fun sendSyncPacket(first: Boolean) {
        if (serverControlPort == -1 || clientControlSocket == null || serverAddress == null) {
            log("Skipping Sync Packet: serverControlPort=$serverControlPort, socket=${clientControlSocket != null}, address=$serverAddress")
            return
        }
        
        try {
            // Sync Packet (PT=84) - 20 bytes
            // Header (8 bytes):
            // Byte 0: If first, X=1 (0x90). Else X=0 (0x80). V=2, P=0, CC=0.
            val b0 = if (first) 0x90 else 0x80
            
            val buffer = ByteBuffer.allocate(20) 
            
            buffer.put(b0.toByte()) 
            buffer.put(0xD4.toByte()) // M=1, PT=84
            
            // Sequence number 7 (Little Endian to match PipeWire quirk: 07 00)
            buffer.put(7.toByte())
            buffer.put(0.toByte())
            
            // Header Timestamp: current RTP timestamp minus latency
            buffer.putInt((rtpTimestamp - latencySamples).toInt())
            
            // Payload (12 bytes): 
            // NTP (8 bytes) + RTP (4 bytes)
            
            // NTP Timestamp (Current wall clock)
            val nowNtp = ntpTime()
            buffer.putLong(nowNtp)
            
            // RTP Timestamp (Current stream time)
            buffer.putInt(rtpTimestamp.toInt())
            
            val packetBytes = buffer.array()
            val packet = DatagramPacket(packetBytes, packetBytes.size, serverAddress!!, serverControlPort)
            clientControlSocket?.send(packet)
            // log("Sent Sync Packet (first=$first) to $serverControlPort")
            
        } catch (e: Exception) {
            log("Error sending sync packet: ${e.message}")
        }
    }

    fun sendFrame(pcm: ByteArray) {
        if (serverAudioPort == -1 || clientAudioSocket == null) return

        try {
            // Send Sync packet on first frame (if not sent yet) and periodically
            if (!firstSyncSent) {
                sendSyncPacket(true)
                firstSyncSent = true
            } else if (packetCount > 0 && packetCount % 125L == 0L) {
                sendSyncPacket(false)
            }

            // Use AlacEncoder (Uncompressed ALAC wrapper)
            val encodedData = encoder.encode(pcm)
            
            val packet = RtpPacket(
                payloadType = 96,
                seqNum = rtpSeqNum,
                timestamp = rtpTimestamp,
                ssrc = ssrc,
                data = encodedData,
                marker = packetCount == 0L // Set marker bit for the first audio packet
            )
            
            val packetBytes = packet.toByteArray()
            if (serverAddress != null) {
                val dp = DatagramPacket(packetBytes, packetBytes.size, serverAddress!!, serverAudioPort)
                clientAudioSocket?.send(dp)
            }
            
            rtpSeqNum = (rtpSeqNum + 1) % 65536
            rtpTimestamp += (pcm.size / 4)
            packetCount++
            
        } catch (e: Exception) {
            log("Send error: ${e.message}")
        }
    }

    fun setVolume(vol: Float) {
        val clampedVol = vol.coerceIn(0.0f, 1.0f)
        val db = if (clampedVol == 0.0f) -144.0f else (clampedVol * 30.0f) - 30.0f
        
        try {
            val content = "volume: $db"
            val headers = mutableMapOf(
                "Content-Type" to "text/parameters",
                "Client-Instance" to clientInstance!!,
                "DACP-ID" to clientInstance!!,
                "User-Agent" to "Lampan/0.1.0"
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
        try {
            val headers = mutableMapOf(
                "Client-Instance" to clientInstance!!,
                "DACP-ID" to clientInstance!!,
                "User-Agent" to "Lampan/0.1.0"
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
