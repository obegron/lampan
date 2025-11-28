package com.egron.lampan

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
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import java.security.SecureRandom
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.lang.Thread
import java.util.concurrent.Executors


class RaopIntegrationTest {

    private fun parseX509Certificate(certificateBytes: ByteArray): X509Certificate? {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        return try {
            val inputStream = ByteArrayInputStream(certificateBytes)
            val certFactory = CertificateFactory.getInstance("X.509", BouncyCastleProvider.PROVIDER_NAME)
            certFactory.generateCertificate(inputStream) as X509Certificate
        } catch (e: Exception) {
            System.err.println("Error parsing X.509 certificate: ${e.message}. Attempting DER parsing.")
            try {
                // Try parsing as DER directly
                val certFactoryDER = CertificateFactory.getInstance("X.509", BouncyCastleProvider.PROVIDER_NAME)
                val inputStreamDER = ByteArrayInputStream(certificateBytes)
                certFactoryDER.generateCertificate(inputStreamDER) as X509Certificate
            } catch (e2: Exception) {
                System.err.println("Error parsing X.509 certificate as DER: ${e2.message}")
                null
            }
        }
    }
    
    // Helper to generate Curve25519 KeyPair using BouncyCastle specific classes
    private fun generateCurve25519KeyPairBouncyCastle(): AsymmetricCipherKeyPair {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val kpGen = X25519KeyPairGenerator()
        kpGen.init(KeyGenerationParameters(SecureRandom(), 255)) // X25519 has 255-bit keys
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
                    socket.receive(packet)
                    val len = packet.length
                    val hex = buffer.take(Math.min(len, 16)).joinToString("") { "%02x".format(it) }
                    println("UDP $name received $len bytes from ${packet.address}:${packet.port}: $hex...")
                    
                    // Handle Timing Request (0x80 0xD2)
                    if (name == "Timing" && len >= 32 && buffer[0] == 0x80.toByte() && buffer[1] == 0xD2.toByte()) {
                        // Construct Timing Reply (0x80 0xD3) - 32 bytes
                        val response = ByteArray(32)
                        response[0] = 0x80.toByte()
                        response[1] = 0xD3.toByte()
                        response[2] = 0x00
                        response[3] = 0x00
                        // 4-7 zeros (Padding/Reserved)
                        
                        // 8-15: Copy Origin Timestamp (Request's Transmit Timestamp, bytes 24-31)
                        System.arraycopy(buffer, 24, response, 8, 8)
                        
                        // 16-23: Receive Timestamp (Current NTP)
                        val nowNtp = ntpTime()
                        putLong(response, 16, nowNtp)
                        
                        // 24-31: Transmit Timestamp (Current NTP)
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

    @Test
    fun testDiscoveryAndHandshake() {
        // This test is meant to be run manually against a real device
        val targetIp = "192.168.0.21" // Speaker IP
        val targetPort = 7000

        println("Connecting to $targetIp:$targetPort...")
        
        try {
             val client = RtspClient(targetIp, targetPort)
             client.connect()
             
             // Determine the correct local interface IP used for this connection
             val clientIp = client.getLocalAddress()?.hostAddress ?: InetAddress.getLocalHost().hostAddress
             println("Client IP Address (determined from socket): $clientIp")
             
             // 1. OPTIONS
             val optionsResponse = client.sendRequest("OPTIONS", "*", mapOf(
                "User-Agent" to "PipeWire/1.4.9",
                "DACP-ID" to "0CC3640156EF8D8A",
                "Client-Instance" to "0CC3640156EF8D8A"
             ))
             println("OPTIONS Response: ${optionsResponse.code}")
             println("OPTIONS Headers: ${optionsResponse.headers}")
             
             if (optionsResponse.code == 200) {
                 // Probe for info
                 val info = client.sendRequest("GET", "/info", mapOf("Content-Type" to "application/x-dmap-tagged"))
                 println("INFO Response: ${info.code}")
                 
                 // Save to file for analysis
                 File("info_response.bin").writeBytes(info.rawBody)

                 // Parse binary plist to find public key
                 try {
                    val infoPlist = client.parseBinaryPlist(info.rawBody)
                    println("INFO Plist Keys: ${infoPlist.allKeys().joinToString()}")
                 } catch (e: Exception) {
                    println("Failed to parse plist: ${e.message}")
                 }
                
                 // --- START: Mimicking PipeWire Capture Flow ---
                 // Generate random IDs
                 val random = java.util.Random()
                 // Use safe 32-bit integer range for Session ID
                 val sessionId = (Math.abs(random.nextInt()) % 1000000000 + 100000000).toString() 
                 
                 val clientInstanceBytes = ByteArray(8)
                 random.nextBytes(clientInstanceBytes)
                 val clientInstance = clientInstanceBytes.joinToString("") { "%02X".format(it) }
                 
                 println("Generated Session ID: $sessionId")
                 println("Generated Client-Instance: $clientInstance")

                 // 2. POST /auth-setup (as per test.cap)
                 val bcClientKeyPair = generateCurve25519KeyPairBouncyCastle()
                 val clientPublicKeyBytes = (bcClientKeyPair.public as X25519PublicKeyParameters).encoded 
                 
                 val authRequestBody = ByteArray(33)
                 authRequestBody[0] = 0x01.toByte() // Key type or flag
                 System.arraycopy(clientPublicKeyBytes, 0, authRequestBody, 1, 32)
                 
                 val authSetupHeaders = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Client-Instance" to clientInstance,
                    "DACP-ID" to clientInstance,
                    "User-Agent" to "PipeWire/1.4.9"
                 )
                 val authSetupResponse = client.sendRequest("POST", "/auth-setup", authSetupHeaders, rawBody = authRequestBody)
                 println("AUTH-SETUP Response: ${authSetupResponse.code}")
                 
                 Thread.sleep(200)
                 
                 var serverPublicKey: PublicKey? = null

                 // If auth-setup is successful, its body contains the public key
                 if (authSetupResponse.code == 200) {
                     println("AUTH-SETUP successful. Response body length: ${authSetupResponse.rawBody.size}")
                     File("auth_setup_response.bin").writeBytes(authSetupResponse.rawBody)
                     
                     val serverCurve25519PublicKeyBytes = client.parseAuthSetupResponse(authSetupResponse.rawBody)
                     println("Server Curve25519 Public Key Bytes (first 8): ${serverCurve25519PublicKeyBytes.take(8).joinToString("") { "%02x".format(it) }}...")

                     val bcAgreement = X25519Agreement()
                     bcAgreement.init(bcClientKeyPair.private)
                     val serverBCPublicKeyParams = X25519PublicKeyParameters(serverCurve25519PublicKeyBytes, 0)
                     val sharedSecretBytes = ByteArray(bcAgreement.agreementSize)
                     bcAgreement.calculateAgreement(serverBCPublicKeyParams, sharedSecretBytes, 0)

                     println("Shared Secret (first 8 bytes): ${sharedSecretBytes.take(8).joinToString("") { "%02x".format(it) }}...")

                     // 3. ANNOUNCE (as per test.cap)
                     val announceSdp = "v=0\r\n" +
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
                        "User-Agent" to "PipeWire/1.4.9"
                     )
                     val announce = client.sendRequest("ANNOUNCE", "rtsp://$clientIp/$sessionId", announceHeaders, announceSdp)
                     println("ANNOUNCE Response: ${announce.code}")
                     
                     Thread.sleep(200)

                     if (announce.code == 200) {
                         println("ANNOUNCE Successful! Proceeding to SETUP...")

                         // 4. SETUP (as per test.cap)
                         val clientInetAddress = InetAddress.getByName(clientIp) 
                         // Use ports 6001/6002 as per capture, wildcard binding
                         val controlSocket = java.net.DatagramSocket(6001) 
                         val timingSocket = java.net.DatagramSocket(6002)
                         
                         val clientControlPort = controlSocket.localPort // Will be 6001
                         val clientTimingPort = timingSocket.localPort   // Will be 6002
                         println("Client Control Port: $clientControlPort, Timing Port: $clientTimingPort")
                         
                         // Start UDP Listeners/Responders
                         startUdpListener(controlSocket, "Control")
                         startUdpListener(timingSocket, "Timing")

                         val setupHeaders = mapOf(
                             "Client-Instance" to clientInstance,
                             "DACP-ID" to clientInstance,
                             "User-Agent" to "PipeWire/1.4.9",
                             "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=$clientControlPort;timing_port=$clientTimingPort"
                         )
                         val setup = client.sendRequest("SETUP", "rtsp://$clientIp/$sessionId", setupHeaders)
                         println("SETUP Response: ${setup.code}")
                         println("SETUP Transport: ${setup.headers["Transport"]}")
                         
                         val serverSession = setup.headers["Session"]
                         println("SETUP Session: $serverSession")
                         
                         if (setup.code == 200 && serverSession != null) {
                             // 5. RECORD (as per test.cap)
                             val recordHeaders = mapOf(
                                 "Client-Instance" to clientInstance,
                                 "DACP-ID" to clientInstance,
                                 "User-Agent" to "PipeWire/1.4.9",
                                 "Session" to serverSession,
                                 "Range" to "npt=0-",
                                 "RTP-Info" to "seq=0;rtptime=0"
                             )
                             val record = client.sendRequest("RECORD", "rtsp://$clientIp/$sessionId", recordHeaders)
                             println("RECORD Response: ${record.code}")
                         } else {
                             println("SETUP Failed with code ${setup.code} or missing Session header.")
                         }
                         
                         // Keep test running briefly to allow UDP threads to process if needed
                         Thread.sleep(1000)
                         
                         // Close sockets after test
                         controlSocket.close()
                         timingSocket.close()
                     } else {
                         println("ANNOUNCE Failed with code ${announce.code}.")
                     }
                 } else {
                     println("AUTH-SETUP Failed with code ${authSetupResponse.code}.")
                 }
                 // --- END: Mimicking PipeWire Capture Flow ---
             }
             
             client.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}