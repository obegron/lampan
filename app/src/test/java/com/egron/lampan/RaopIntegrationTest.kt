package com.egron.lampan

import com.egron.lampan.raop.RaopSession
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import kotlin.math.PI

class RaopIntegrationTest {

    @Test
    fun testFullSession() {
        val targetIp = "192.168.0.21" // Speaker IP
        val targetPort = 7000
        
        println("Starting Full Session Test against $targetIp:$targetPort")

        val latch = CountDownLatch(1)
        
        // Instantiate the real session class
        // We pass a callback to capture logs
        val session = RaopSession(targetIp, targetPort) { msg ->
            println("[RaopSession] $msg")
        }

        val testThread = Thread {
            try {
                println("Connecting...")
                // connect() is a suspend function, but here we are blocking for the test
                // modifying connect to be blocking or using runBlocking would be ideal
                // but since we can't easily change the signature to non-suspend without breaking app...
                // We will wrap it.
                
                kotlinx.coroutines.runBlocking {
                    session.connect()
                }
                
                println("Connection successful! Streaming audio...")
                
                // Generate 6 seconds of audio tone
                val toneData = generateSineWave(6000, 440.0)
                val packetSize = 352 * 4
                val numPackets = toneData.size / packetSize
                
                for (i in 0 until numPackets) {
                    val offset = i * packetSize
                    if (offset + packetSize <= toneData.size) {
                        val chunk = toneData.copyOfRange(offset, offset + packetSize)
                        session.sendFrame(chunk)
                        if (i % 50 == 0) println("Sent packet $i")
                        Thread.sleep(8) // Real-time simulation
                    }
                }
                
                println("Streaming finished. Stopping...")
                session.stop()
                println("Stopped.")
                latch.countDown()
                
            } catch (e: Exception) {
                e.printStackTrace()
                latch.countDown()
            }
        }
        
        testThread.start()
        
        // Wait for test to complete (max 15 seconds)
        val completed = latch.await(15, TimeUnit.SECONDS)
        if (!completed) {
            println("Test timed out!")
            testThread.interrupt()
        }
    }

    private fun generateSineWave(durationMs: Int, freq: Double = 440.0): ByteArray {
        val sampleRate = 44100
        val numSamples = (durationMs * sampleRate / 1000)
        val buffer = ByteArray(numSamples * 4)
        
        for (i in 0 until numSamples) {
            val sample = (sin(2 * PI * i * freq / sampleRate) * 32767).toInt().toShort()
            // Little Endian generation (RaopSession handles swap if needed)
            val low = (sample.toInt() and 0xFF).toByte()
            val high = ((sample.toInt() shr 8) and 0xFF).toByte()
            buffer[i * 4] = low
            buffer[i * 4 + 1] = high
            buffer[i * 4 + 2] = low
            buffer[i * 4 + 3] = high
        }
        return buffer
    }
}
