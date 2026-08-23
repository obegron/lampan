package com.egron.lampan.raop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RtspClientTest {
    @Test
    fun connectUsesConfiguredReceiverPort() {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val acceptThread = thread(isDaemon = true) {
                server.accept().use { accepted.countDown() }
            }
            val client = RtspClient(
                host = "127.0.0.1",
                port = server.localPort,
                logCallback = {},
            )
            try {
                client.connect()
                assertTrue(accepted.await(2, TimeUnit.SECONDS))
            } finally {
                client.close()
                acceptThread.join(2_000)
            }
        }
    }

    @Test
    fun quietExchangeStillReadsResponseWithoutFillingWireLog() {
        ServerSocket(0).use { server ->
            val serverThread = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().write(
                        "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    )
                    socket.getOutputStream().flush()
                }
            }
            val logs = mutableListOf<String>()
            val client = RtspClient(
                host = "127.0.0.1",
                port = server.localPort,
                logCallback = logs::add,
            )
            try {
                client.connect()
                logs.clear()
                val response = client.sendRequest(
                    method = "POST",
                    url = "/feedback",
                    headers = emptyMap(),
                    logExchange = false,
                )

                assertEquals(200, response.code)
                assertTrue(logs.isEmpty())
            } finally {
                client.close()
                serverThread.join(2_000)
            }
        }
    }
}
