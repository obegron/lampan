package com.egron.lampan

import com.egron.lampan.raop.AlacEncoder
import com.egron.lampan.raop.RaopSession
import com.egron.lampan.raop.RtpPacket
import com.egron.lampan.raop.RtspClient
import com.egron.lampan.raop.parsePairSetupResponse
import com.egron.lampan.raop.CryptoUtils
import org.junit.Test
import org.junit.Assert.assertEquals
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

class RaopSonyTvTest {

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

    @Test
    fun testDiscoveryAndHandshake() {
        val targetIp = "192.168.0.12"
        val targetPort = 7000

        println("Connecting to $targetIp:$targetPort...")
        
        val raopSession = RaopSession(targetIp, targetPort) { msg -> println("RaopSession Log: $msg") }

        try {
             val client = RtspClient(targetIp, targetPort) { msg -> println("RtspClient: $msg") }
             client.connect()
             
             val clientIp = client.getLocalAddress()?.hostAddress ?: InetAddress.getLocalHost().hostAddress
             println("Client IP Address: $clientIp")
             
             val random = Random()
             val sessionId = (Math.abs(random.nextInt()) % 1000000000 + 100000000).toString()
             val clientInstanceBytes = ByteArray(8)
             random.nextBytes(clientInstanceBytes)
             val clientInstance = clientInstanceBytes.joinToString("") { "%02X".format(it) }

             // Sockets
             val controlSocket = DatagramSocket(0) 
             val timingSocket = DatagramSocket(0) 
             val clientAudioSocket = DatagramSocket(0) 

             startUdpListener(controlSocket, "Control")
             startUdpListener(timingSocket, "Timing")

             // 1. OPTIONS
             val optionsResponse = client.sendRequest("OPTIONS", "*", mapOf(
                "User-Agent" to "AirPlay/377.40.00",
                "DACP-ID" to clientInstance,
                "Client-Instance" to clientInstance
             ))
             println("OPTIONS Response: ${optionsResponse.code}")
             
             var isHandshakeSuccess = false

             if (optionsResponse.code == 403) {
                 println("Target requires pairing (403 on OPTIONS). Attempting /pair-setup...")
                 
                 // Reconnect to ensure clean state
                 client.close()
                 client.connect()
                 
                 // 1. Generate Client Ephemeral Key Pair (X25519)
                 val clientKeyPair = generateCurve25519KeyPairBouncyCastle()
                 val clientPublicKey = (clientKeyPair.public as X25519PublicKeyParameters).encoded
                 val clientPrivateKey = (clientKeyPair.private as org.bouncycastle.crypto.params.X25519PrivateKeyParameters)

                 val pairSetupHeaders = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "User-Agent" to "AirPlay/377.40.00"
                 )
                 
                 // Body: 0x01 + 32-byte Client Public Key
                 val pairSetupBody = ByteArray(33)
                 pairSetupBody[0] = 0x01.toByte()
                 System.arraycopy(clientPublicKey, 0, pairSetupBody, 1, 32)
                 
                 val pairSetupResponse = client.sendRequest("POST", "/pair-setup", pairSetupHeaders, rawBody = pairSetupBody)
                 println("PAIR-SETUP Response: ${pairSetupResponse.code}")
                 
                 assertEquals(200, pairSetupResponse.code)
                 
                 if (pairSetupResponse.code == 200) {
                     println("Pairing initiated successfully. Parsing response...")
                     val parsedResponse = parsePairSetupResponse(pairSetupResponse.rawBody)
                     
                     val serverPublicKeyBytes = parsedResponse.serverEphemeralPublicKey
                     println("Server Ephemeral Public Key: ${client.hexDump(serverPublicKeyBytes)}")
                     
                     // 2. Calculate Shared Secret (ECDH)
                     val agreement = X25519Agreement()
                     agreement.init(clientPrivateKey)
                     val sharedSecret = ByteArray(agreement.agreementSize)
                     val serverPublicKeyParams = X25519PublicKeyParameters(serverPublicKeyBytes, 0)
                     agreement.calculateAgreement(serverPublicKeyParams, sharedSecret, 0)
                     
                     println("Shared Secret Calculated (${sharedSecret.size} bytes):")
                     println(client.hexDump(sharedSecret))

                     // Now, we would typically generate our own ephemeral key, perform ECDH, and then
                     // send a signed pair-verify or auth-setup.
                     // For now, we proceed to auth-setup, which will likely fail without the crypto.
                     isHandshakeSuccess = true
                 }
             } else if (optionsResponse.code == 200) {
                 isHandshakeSuccess = true
             }

             if (isHandshakeSuccess) {
                 // Handshake success logic here if needed
             }
             client.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
