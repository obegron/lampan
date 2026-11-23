package com.egron.lampan.raop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RaopAuthSetupTest {
    @Test
    fun legacyReceiversReachAnnounceAfterOptionalAuthSetup() {
        for (status in listOf(200, 404, 405, 501)) {
            ServerSocket(0).use { server ->
                val methods = mutableListOf<String>()
                val serverError = AtomicReference<Throwable>()
                val worker = thread(isDaemon = true) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 3_000
                            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            repeat(3) { index ->
                                methods += input.readLine().substringBefore(" RTSP/")
                                var length = 0
                                while (true) {
                                    val line = input.readLine() ?: error("Unexpected connection close")
                                    if (line.isEmpty()) break
                                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                        length = line.substringAfter(':').trim().toInt()
                                    }
                                }
                                repeat(length) { check(input.read() != -1) }
                                // Stop at ANNOUNCE so this test needs no audio or UDP sockets.
                                val code = when (index) { 0 -> 200; 1 -> status; else -> 400 }
                                socket.getOutputStream().write(
                                    "RTSP/1.0 $code Test\r\nCSeq: ${index + 1}\r\n\r\n".toByteArray(),
                                )
                                socket.getOutputStream().flush()
                            }
                        }
                    } catch (error: Throwable) {
                        serverError.set(error)
                    }
                }
                val session = RaopSession("127.0.0.1", server.localPort) {}
                val failure = assertThrows(Exception::class.java) {
                    runBlocking { session.connect() }
                }
                worker.join(5_000)
                assertFalse("Fake receiver did not finish", worker.isAlive)
                serverError.get()?.let { throw AssertionError("Fake receiver failed", it) }
                assertEquals("ANNOUNCE failed: 400", failure.message)
                assertEquals(listOf("OPTIONS *", "POST /auth-setup"), methods.take(2))
                assertTrue(methods.last().startsWith("ANNOUNCE rtsp://"))
                session.stop()
            }
        }
    }

    @Test
    fun nonemptyRepliesStillRequireACompletePublicKey() {
        val client = RtspClient("127.0.0.1", 5000, logCallback = {})
        val key = ByteArray(32) { (it + 1).toByte() }
        assertTrue(shouldUseRaopAuthSetupKey(RtspResponse(200, emptyMap(), "", key)))
        assertTrue(key.contentEquals(client.parseAuthSetupResponse(key)))
        val truncated = RtspResponse(200, emptyMap(), "", ByteArray(31))
        assertTrue(shouldUseRaopAuthSetupKey(truncated))
        assertThrows(IllegalArgumentException::class.java) {
            client.parseAuthSetupResponse(truncated.rawBody)
        }
    }

    @Test
    fun authenticationAndUnexpectedServerErrorsRemainFailures() {
        for (status in listOf(401, 403, 500)) {
            val error = assertThrows(IllegalStateException::class.java) {
                shouldUseRaopAuthSetupKey(RtspResponse(status, emptyMap(), ""))
            }
            assertEquals("AUTH-SETUP failed: $status", error.message)
        }
    }
}
