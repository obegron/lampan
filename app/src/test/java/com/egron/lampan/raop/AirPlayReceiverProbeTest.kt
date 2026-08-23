package com.egron.lampan.raop

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class AirPlayReceiverProbeTest {
    @Test
    fun infoProbeReadsAndNormalizesStableReceiverIdentity() {
        val body = BinaryPropertyListWriter.writeToArray(
            NSDictionary().apply {
                put("deviceID", "c4cd1b547c3f")
                put("name", "Living Room TV")
                put("model", "Example-1")
            },
        )
        ServerSocket(0).use { server ->
            val serverThread = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().write(
                        (
                            "RTSP/1.0 200 OK\r\n" +
                                "Content-Type: application/x-apple-binary-plist\r\n" +
                                "Content-Length: ${body.size}\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    socket.getOutputStream().write(body)
                    socket.getOutputStream().flush()
                }
            }

            val info = AirPlayReceiverProbe("127.0.0.1", server.localPort).getInfo()

            assertEquals("C4:CD:1B:54:7C:3F", info.receiverId)
            assertEquals(setOf("C4:CD:1B:54:7C:3F"), info.receiverIds)
            assertEquals("Living Room TV", info.name)
            assertEquals("Example-1", info.model)
            serverThread.join(2_000)
        }
    }

    @Test
    fun infoProbeAcceptsDeviceAndPairingIdentitiesAsAliases() {
        val body = BinaryPropertyListWriter.writeToArray(
            NSDictionary().apply {
                put("deviceID", "78:28:CA:84:BA:30")
                put("pi", "5f6602b1-1538-4521-bd91-7a1a2a58ea40")
            },
        )
        ServerSocket(0).use { server ->
            val serverThread = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().write(
                        (
                            "RTSP/1.0 200 OK\r\n" +
                                "Content-Type: application/x-apple-binary-plist\r\n" +
                                "Content-Length: ${body.size}\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    socket.getOutputStream().write(body)
                    socket.getOutputStream().flush()
                }
            }

            val info = AirPlayReceiverProbe("127.0.0.1", server.localPort).getInfo()

            assertEquals("78:28:CA:84:BA:30", info.receiverId)
            assertEquals(
                setOf(
                    "78:28:CA:84:BA:30",
                    "5F6602B1-1538-4521-BD91-7A1A2A58EA40",
                ),
                info.receiverIds,
            )
            serverThread.join(2_000)
        }
    }

    @Test
    fun receiverIdentityNormalizationDoesNotRewriteArbitraryIdentifiers() {
        assertEquals("C4:CD:1B:54:7C:3F", normalizeAirPlayReceiverId("c4-cd-1b-54-7c-3f"))
        assertEquals("RECEIVER-ONE", normalizeAirPlayReceiverId("receiver-one"))
    }
}
