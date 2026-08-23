package com.egron.lampan

import com.egron.lampan.raop.AirPlay2Client
import com.egron.lampan.raop.AirPlay2Session
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** Explicit, silent AirPlay 2 handshake against a user-selected receiver. */
class AirPlay2ReceiverIntegrationTest {
    @Test
    fun transientHandshakeWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("AIRPLAY2_HANDSHAKE_TEST") == "true")
        val host = System.getenv("AIRPLAY2_TEST_HOST")
        assumeTrue(!host.isNullOrBlank())
        val port = System.getenv("AIRPLAY2_TEST_PORT")?.toIntOrNull() ?: 7000
        val password = System.getenv("AIRPLAY2_TEST_PASSWORD") ?: "3939"

        AirPlay2Client(requireNotNull(host), port) { println(it) }
            .connectTransient(password)
            .close()
    }

    @Test
    fun silentStreamWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("AIRPLAY2_SILENT_STREAM_TEST") == "true")
        val host = System.getenv("AIRPLAY2_TEST_HOST")
        assumeTrue(!host.isNullOrBlank())
        val port = System.getenv("AIRPLAY2_TEST_PORT")?.toIntOrNull() ?: 7000
        val password = System.getenv("AIRPLAY2_TEST_PASSWORD")

        val session = AirPlay2Session(
            host = requireNotNull(host),
            port = port,
            credentials = null,
            password = password,
            onCredentialsCreated = {},
            log = { println(it) },
            transientPairing = true,
        )
        try {
            session.connect(initialVolume = 0f)
            val silentPacket = ByteArray(352 * 2 * 2)
            repeat(375) {
                session.sendFrame(silentPacket)
                Thread.sleep(8L)
            }
        } finally {
            session.stop()
        }
    }

    @Test
    fun quietToneWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("AIRPLAY2_AUDIBLE_TEST") == "true")
        val host = System.getenv("AIRPLAY2_TEST_HOST")
        assumeTrue(!host.isNullOrBlank())
        val port = System.getenv("AIRPLAY2_TEST_PORT")?.toIntOrNull() ?: 7000
        val password = System.getenv("AIRPLAY2_TEST_PASSWORD") ?: "3939"

        val session = AirPlay2Session(
            host = requireNotNull(host),
            port = port,
            credentials = null,
            password = password,
            onCredentialsCreated = {},
            log = { println(it) },
            transientPairing = true,
        )
        try {
            // 20% on Lampan's -30..0 dB curve is -24 dB.
            session.connect(initialVolume = 0.2f)
            repeat(125) { packet ->
                session.sendFrame(quietSinePacket(packet, frequencyHz = 660.0))
                Thread.sleep(8L)
            }
            // Keep the stream alive past its two-second render latency.
            val silence = ByteArray(352 * 2 * 2)
            repeat(375) {
                session.sendFrame(silence)
                Thread.sleep(8L)
            }
        } finally {
            session.stop()
        }
    }

    @Test
    fun lowWhiteNoiseWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("AIRPLAY2_WHITE_NOISE_TEST") == "true")
        val host = System.getenv("AIRPLAY2_TEST_HOST")
        assumeTrue(!host.isNullOrBlank())
        val port = System.getenv("AIRPLAY2_TEST_PORT")?.toIntOrNull() ?: 7000
        val password = System.getenv("AIRPLAY2_TEST_PASSWORD") ?: "3939"

        val session = AirPlay2Session(
            host = requireNotNull(host),
            port = port,
            credentials = null,
            password = password,
            onCredentialsCreated = {},
            log = { println(it) },
            transientPairing = true,
        )
        try {
            // 40% on Lampan's -30..0 dB curve is -18 dB. Samples are also
            // limited to half scale so this stays a conservative noise test.
            session.connect(initialVolume = 0.4f)
            val random = Random(0x1A2B3C4D)
            repeat(188) {
                session.sendFrame(lowWhiteNoisePacket(random))
                Thread.sleep(8L)
            }
            val silence = ByteArray(352 * 2 * 2)
            repeat(375) {
                session.sendFrame(silence)
                Thread.sleep(8L)
            }
        } finally {
            session.stop()
        }
    }

    @Test
    fun synchronizedSilentGroupWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("AIRPLAY2_GROUP_SILENT_TEST") == "true")
        val configuredReceivers = System.getenv("AIRPLAY2_GROUP_RECEIVERS")
        assumeTrue(!configuredReceivers.isNullOrBlank())
        val receivers = requireNotNull(configuredReceivers).split(',').map { configured ->
            val separator = configured.lastIndexOf('=')
            require(separator > 0) { "Use host=password entries in AIRPLAY2_GROUP_RECEIVERS" }
            configured.substring(0, separator) to configured.substring(separator + 1)
        }
        assumeTrue(receivers.size > 1)

        val sessions = receivers.map { (host, password) ->
            AirPlay2Session(
                host = host,
                port = 7000,
                credentials = null,
                password = password,
                onCredentialsCreated = {},
                log = { println("[$host] $it") },
                transientPairing = true,
            )
        }
        try {
            sessions.forEach { session ->
                session.connect(initialVolume = 0f, synchronizeImmediately = false)
            }
            val sharedStartMillis = System.currentTimeMillis() + 250L
            sessions.forEach { it.synchronizeAt(sharedStartMillis) }
            val silentPacket = ByteArray(352 * 2 * 2)
            repeat(375) {
                sessions.forEach { session -> session.sendFrame(silentPacket) }
                Thread.sleep(8L)
            }
        } finally {
            sessions.forEach(AirPlay2Session::stop)
        }
    }

    private fun quietSinePacket(packet: Int, frequencyHz: Double): ByteArray {
        val frames = 352
        val pcm = ByteArray(frames * 2 * 2)
        repeat(frames) { frame ->
            val sampleIndex = packet * frames + frame
            val sample = (
                sin(2.0 * PI * sampleIndex * frequencyHz / 44_100.0) *
                    Short.MAX_VALUE * 0.35
                ).toInt().toShort()
            repeat(2) { channel ->
                val offset = (frame * 2 + channel) * 2
                pcm[offset] = sample.toByte()
                pcm[offset + 1] = (sample.toInt() ushr 8).toByte()
            }
        }
        return pcm
    }

    private fun lowWhiteNoisePacket(random: Random): ByteArray {
        val frames = 352
        val pcm = ByteArray(frames * 2 * 2)
        repeat(frames) { frame ->
            val sample = (random.nextInt(0x1_0000) - 0x8000).div(2).toShort()
            repeat(2) { channel ->
                val offset = (frame * 2 + channel) * 2
                pcm[offset] = sample.toByte()
                pcm[offset + 1] = (sample.toInt() ushr 8).toByte()
            }
        }
        return pcm
    }
}
