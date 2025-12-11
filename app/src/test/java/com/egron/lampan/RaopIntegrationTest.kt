package com.egron.lampan

import com.egron.lampan.raop.AlacEncoder
import com.egron.lampan.raop.RaopSession
import com.egron.lampan.raop.RtpPacket
import com.egron.lampan.raop.RtspClient
import org.junit.Test
import java.io.File
import java.util.Base64
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.PublicKey
import java.security.KeyPair
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.KeyAgreement
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import java.security.SecureRandom
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.lang.Thread
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import kotlin.math.PI

class RaopIntegrationTest {

    private fun generateCurve25519KeyPairBouncyCastle(): AsymmetricCipherKeyPair {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val kpGen = X25519KeyPairGenerator()
        kpGen.init(KeyGenerationParameters(SecureRandom(), 255))
        return kpGen.generateKeyPair()
    }

    private fun startUdpListener(socket: DatagramSocket, name: String) {
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            println("UDP $name listener started on port ${socket.localPort}")
            try {
                while (!socket.isClosed) {
                    packet.length = buffer.size
                    socket.receive(packet)
                    val len = packet.length
                    // val hex = buffer.take(Math.min(len, 16)).joinToString("") { "%02x".format(it) }
                    // println("UDP $name received $len bytes from ${packet.address}:${packet.port}: $hex...")
                    
                    // Handle Timing Request (0x80 0xD2)
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
                        println("Sent Timing Reply (0xD3) to ${packet.address}:${packet.port}")
                    }
                }
            } catch (e: Exception) {
                if (!socket.isClosed) {
                    println("UDP $name listener error: ${e.message}")
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
    
    private fun generateSineWave(durationMs: Int, freq: Double = 440.0): ByteArray {
        val sampleRate = 44100
        val numSamples = (durationMs * sampleRate / 1000)
        val buffer = ByteArray(numSamples * 4)
        for (i in 0 until numSamples) {
            val sample = (sin(2 * PI * i * freq / sampleRate) * 32767).toInt().toShort()
            val low = (sample.toInt() and 0xFF).toByte()
            val high = ((sample.toInt() shr 8) and 0xFF).toByte()
            buffer[i * 4] = low
            buffer[i * 4 + 1] = high
            buffer[i * 4 + 2] = low
            buffer[i * 4 + 3] = high
        }
        return buffer
    }

    // @Test
    fun testDiscoveryAndHandshake() {
        val targetIp = "192.168.0.21"
        val targetPort = 7000

        println("Connecting to $targetIp:$targetPort...")
        
        val raopSession = RaopSession(targetIp, targetPort) { msg -> println("RaopSession Log: $msg") }

        try {
             val client = RtspClient(targetIp, targetPort)
             client.connect()
             
             val clientIp = client.getLocalAddress()?.hostAddress ?: InetAddress.getLocalHost().hostAddress
             println("Client IP Address: $clientIp")
             
             val random = Random()
             val sessionId = (Math.abs(random.nextInt()) % 1000000000 + 100000000).toString()
             val clientInstanceBytes = ByteArray(8)
             random.nextBytes(clientInstanceBytes)
             val clientInstance = clientInstanceBytes.joinToString("") { "%02X".format(it) }

             // Sockets
             val controlSocket = DatagramSocket(6001) 
             val timingSocket = DatagramSocket(6002) 
             val clientAudioSocket = DatagramSocket(0) 

             startUdpListener(controlSocket, "Control")
             startUdpListener(timingSocket, "Timing")

             // 1. OPTIONS
             val optionsResponse = client.sendRequest("OPTIONS", "*", mapOf(
                "User-Agent" to "Lampan/0.1.0",
                "DACP-ID" to clientInstance,
                "Client-Instance" to clientInstance
             ))
             println("OPTIONS Response: ${optionsResponse.code}")
             
             if (optionsResponse.code == 200) {
                 // 2. POST /auth-setup
                 val bcClientKeyPair = generateCurve25519KeyPairBouncyCastle()
                 val clientPublicKeyBytes = (bcClientKeyPair.public as X25519PublicKeyParameters).encoded 
                 val authRequestBody = ByteArray(33)
                 authRequestBody[0] = 0x01.toByte()
                 System.arraycopy(clientPublicKeyBytes, 0, authRequestBody, 1, 32)
                 
                 val authSetupHeaders = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Client-Instance" to clientInstance,
                    "DACP-ID" to clientInstance,
                    "User-Agent" to "Lampan/0.1.0"
                 )
                 val authSetupResponse = client.sendRequest("POST", "/auth-setup", authSetupHeaders, rawBody = authRequestBody)
                 println("AUTH-SETUP Response: ${authSetupResponse.code}")
                 
                 Thread.sleep(200)
                 
                 if (authSetupResponse.code == 200) {
                     // Crypto setup omitted for test speed
                     println("Shared Secret generated (mock).")

                     // 3. ANNOUNCE
                     val sdp = "v=0\r\n" +
                         "o=iTunes $sessionId 0 IN IP4 $clientIp\r\n" +
                         "s=iTunes\r\n" +
                         "c=IN IP4 $targetIp\r\n" +
                         "t=0 0\r\n" +
                         "m=audio 0 RTP/AVP 96\r\n" +
                         "a=rtpmap:96 AppleLossless\r\n" +
                         "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n" +
                         "a=min-latency:11025"
                     
                     val announceHeaders = mapOf(
                        "Content-Type" to "application/sdp",
                        "Client-Instance" to clientInstance,
                        "DACP-ID" to clientInstance,
                        "User-Agent" to "Lampan/0.1.0"
                     )
                     val announce = client.sendRequest("ANNOUNCE", "rtsp://$clientIp/$sessionId", announceHeaders, sdp)
                     println("ANNOUNCE Response: ${announce.code}")
                     
                     Thread.sleep(200)

                     if (announce.code == 200) {
                         // 4. SETUP
                         val setupHeaders = mapOf(
                             "Client-Instance" to clientInstance,
                             "DACP-ID" to clientInstance,
                             "User-Agent" to "Lampan/0.1.0",
                             "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${controlSocket.localPort};timing_port=${timingSocket.localPort}"
                         )
                         val setup = client.sendRequest("SETUP", "rtsp://$clientIp/$sessionId", setupHeaders)
                         println("SETUP Response: ${setup.code}")
                         
                         val serverSession = setup.headers["Session"]
                         val serverTransport = setup.headers["Transport"]
                         var serverAudioPort = 0
                         var serverControlPort = 0
                         if (serverTransport != null) {
                             val parts = serverTransport.split(";")
                             for (part in parts) {
                                 val p = part.trim()
                                 if (p.startsWith("server_port=")) {
                                     serverAudioPort = p.substringAfter("=").toInt()
                                 }
                                 if (p.startsWith("control_port=")) {
                                     serverControlPort = p.substringAfter("=").toInt()
                                 }
                             }
                         }
                         
                         // Inject state into RaopSession for sendFrame
                         raopSession.setupForTest(
                             testSessionId = sessionId,
                             testClientInstance = clientInstance,
                             testHost = targetIp,
                             testServerAudioPort = serverAudioPort,
                             testServerSession = serverSession,
                             testClientAudioSocket = clientAudioSocket,
                             testClientControlSocket = controlSocket,
                             testServerControlPort = serverControlPort
                         )

                         if (setup.code == 200 && serverSession != null) {
                             // 5. RECORD
                             val recordHeaders = mapOf(
                                 "Client-Instance" to clientInstance,
                                 "DACP-ID" to clientInstance,
                                 "User-Agent" to "Lampan/0.1.0",
                                 "Session" to serverSession,
                                 "Range" to "npt=0-",
                                 "RTP-Info" to "seq=0;rtptime=0"
                             )
                             val record = client.sendRequest("RECORD", "rtsp://$clientIp/$sessionId", recordHeaders)
                             println("RECORD Response: ${record.code}")

                             if (record.code == 200) {
                                 println("RECORD Successful.")
                                 
                                 // 6. SET_PARAMETER (Volume) - Match PipeWire flow
                                 val paramHeaders = mapOf(
                                     "Client-Instance" to clientInstance,
                                     "DACP-ID" to clientInstance,
                                     "User-Agent" to "Lampan/0.1.0",
                                     "Session" to serverSession,
                                     "Content-Type" to "text/parameters"
                                 )
                                 val volBody = "volume: 0.000000\r\n"
                                 val volResp = client.sendRequest("SET_PARAMETER", "rtsp://$targetIp:$targetPort/$sessionId", paramHeaders, volBody)
                                 println("SET_VOLUME Response: ${volResp.code}")
                                 
                                 // 7. SET_PARAMETER (Progress)
                                 val progBody = "progress: 0/0/0\r\n"
                                 val progResp = client.sendRequest("SET_PARAMETER", "rtsp://$targetIp:$targetPort/$sessionId", paramHeaders, progBody)
                                 println("SET_PROGRESS Response: ${progResp.code}")
                                 
                                 println("Streaming silence (50 packets) then sine wave...")
                                 
                                 // Send silence first
                                 val silenceData = ByteArray(1408) // 352 frames of silence
                                 for (i in 0 until 50) {
                                     raopSession.sendFrame(silenceData)
                                     Thread.sleep(8)
                                 }
                                 
                                 val toneData = generateSineWave(6000, 440.0)
                                 val packetSize = 352 * 4
                                 val numPackets = toneData.size / packetSize
                                 
                                 for (i in 0 until numPackets) {
                                     val offset = i * packetSize
                                     val chunk = toneData.copyOfRange(offset, offset + packetSize)
                                     raopSession.sendFrame(chunk)
                                     if (i % 100 == 0) println("Sent packet $i")
                                     Thread.sleep(8) 
                                 }
                                 println("Finished streaming.")
                             }
                         }
                         
                         controlSocket.close()
                         timingSocket.close()
                         clientAudioSocket.close()
                     }
                 }
             }
             client.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}