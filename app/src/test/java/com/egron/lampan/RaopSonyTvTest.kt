package com.egron.lampan

import com.egron.lampan.raop.HomeKitPairSetup
import com.egron.lampan.raop.HomeKitTlv
import com.egron.lampan.raop.HomeKitTlvType
import com.egron.lampan.raop.AirPlay2Session
import com.egron.lampan.raop.RtspClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Explicitly guarded Sony AirPlay 2 checkpoints.
 *
 * They are skipped during normal builds and neither registers a controller.
 * The audio test uses transient pairing and sends only zero-valued PCM with
 * receiver volume muted.
 */
class RaopSonyTvTest {
    @Test
    fun testInfoAndPairSetupProofWhenPasswordProvided() {
        val password = System.getenv("SONY_AIRPLAY_PASSWORD")
            ?: System.getenv("SONY_AIRPLAY_PIN")
        assumeTrue(
            "Set SONY_AIRPLAY_PASSWORD to run the manual Sony test",
            !password.isNullOrBlank(),
        )

        val targetIp = "192.168.0.12"
        val targetPort = 7000
        val clientInstance = ByteArray(8)
            .also(SecureRandom()::nextBytes)
            .joinToString("") { "%02X".format(it) }
        val client = RtspClient(targetIp, targetPort) { message -> println(message) }

        try {
            client.connect()
            val commonHeaders = mapOf(
                "User-Agent" to "AirPlay/550.10",
                "X-Apple-HKP" to "3",
                "DACP-ID" to clientInstance,
                "Client-Instance" to clientInstance,
                "X-Apple-Client-Name" to "Lampan",
            )

            val info = client.sendRequest("GET", "/info", commonHeaders)
            assertEquals(200, info.code)
            assertEquals(
                "application/x-apple-binary-plist",
                info.headers["Content-Type"],
            )

            // /pair-pin-start is intentionally not sent here. It changes the
            // television UI and should be triggered by the real pairing flow
            // immediately before asking the user for the displayed PIN.
            val pairSetup = HomeKitPairSetup()
            val m2 = client.sendRequest(
                "POST",
                "/pair-setup",
                commonHeaders + ("Content-Type" to "application/octet-stream"),
                rawBody = pairSetup.start(),
            )
            assertEquals(200, m2.code)
            assertEquals("application/octet-stream", m2.headers["Content-Type"])

            val fields = HomeKitTlv.decode(m2.rawBody)
            assertEquals(2, fields[HomeKitTlvType.STATE]?.single()?.toInt())
            assertEquals(16, fields[HomeKitTlvType.SALT]?.size)
            assertEquals(384, fields[HomeKitTlvType.PUBLIC_KEY]?.size)
            assertNotNull(fields[HomeKitTlvType.PUBLIC_KEY])

            val m3 = pairSetup.continueWithPassword(m2.rawBody, requireNotNull(password))
            val m4 = client.sendRequest(
                "POST",
                "/pair-setup",
                commonHeaders + ("Content-Type" to "application/octet-stream"),
                rawBody = m3,
            )
            assertEquals(200, m4.code)

            // Build M5 to prove that M4 authenticated correctly, but never send
            // it. Sending M5 would register a controller on the television.
            assertNotNull(pairSetup.continueAfterProof(m4.rawBody))
            assertEquals(HomeKitPairSetup.Stage.WAITING_FOR_M6, pairSetup.stage)
        } finally {
            client.close()
        }
    }

    @Test
    fun testNativeAirPlay2WithSilentPcmWhenExplicitlyEnabled() {
        assumeTrue(
            "Set SONY_AIRPLAY2_SILENT_TEST=true to run the silent stream test",
            System.getenv("SONY_AIRPLAY2_SILENT_TEST") == "true",
        )
        val password = System.getenv("SONY_AIRPLAY_PASSWORD")
            ?: System.getenv("SONY_AIRPLAY_PIN")
        assumeTrue(
            "Set SONY_AIRPLAY_PASSWORD to run the silent stream test",
            !password.isNullOrBlank(),
        )

        streamSilentPcm(requireNotNull(password))
    }

    private fun streamSilentPcm(password: String) {
        val session = AirPlay2Session(
            host = "192.168.0.12",
            port = 7000,
            credentials = null,
            password = password,
            onCredentialsCreated = {},
            log = { println(it) },
        )
        try {
            session.connect(initialVolume = 0f)
            val silentPacket = ByteArray(352 * 2 * 2)
            repeat(SILENT_PACKET_COUNT) {
                session.sendFrame(silentPacket)
                Thread.sleep(SILENT_PACKET_DELAY_MS)
            }
        } finally {
            session.stop()
        }
    }

    private companion object {
        const val SILENT_PACKET_COUNT = 375
        const val SILENT_PACKET_DELAY_MS = 8L
    }
}
