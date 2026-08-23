package com.egron.lampan.raop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HomeKitRtspChannelTest {
    @Test
    fun exchangesMultiFrameRtspMessagesInBothDirections() {
        val clientWrite = ByteArray(32) { (it + 3).toByte() }
        val clientRead = ByteArray(32) { (it + 53).toByte() }
        val client = HomeKitRtspChannel(HomeKitControlCipher(clientWrite, clientRead))
        val server = HomeKitRtspChannel(HomeKitControlCipher(clientRead, clientWrite))
        val requestBody = ByteArray(1_500) { (it * 13).toByte() }
        val request = message("POST /command RTSP/1.0", requestBody)
        val responseBody = ByteArray(2_500) { (it * 17).toByte() }
        val response = message("RTSP/1.0 200 OK", responseBody)

        assertArrayEquals(
            request,
            server.readMessage(ByteArrayInputStream(client.encrypt(request))),
        )
        assertArrayEquals(
            response,
            client.readMessage(ByteArrayInputStream(server.encrypt(response))),
        )
    }

    private fun message(startLine: String, body: ByteArray): ByteArray {
        val header = (
            "$startLine\r\n" +
                "CSeq: 1\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        return header + body
    }
}
