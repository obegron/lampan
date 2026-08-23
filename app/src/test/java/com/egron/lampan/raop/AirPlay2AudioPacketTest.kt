package com.egron.lampan.raop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlay2AudioPacketTest {
    @Test
    fun encryptedPacketCarriesAuthenticatedRtpContextAndTrailingNonce() {
        val key = ByteArray(32) { it.toByte() }
        val payload = ByteArray(37) { (it * 3).toByte() }
        val packet = AirPlay2AudioPacketCipher(key).encode(
            sequence = 0x1234,
            timestamp = 0x0102_0304,
            ssrc = 0x1122_3344,
            alacPayload = payload,
            marker = true,
        )

        assertEquals(12 + payload.size + 16 + 8, packet.size)
        assertEquals(0x80, packet[0].toInt() and 0xFF)
        assertEquals(0xE0, packet[1].toInt() and 0xFF)
        assertEquals(0x12, packet[2].toInt() and 0xFF)
        assertEquals(0x34, packet[3].toInt() and 0xFF)

        val nonceSuffix = packet.copyOfRange(packet.size - 8, packet.size)
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0, 0, 0, 0, 0, 0),
            nonceSuffix,
        )
        val nonce = ByteArray(12).also {
            nonceSuffix.copyInto(it, destinationOffset = 4)
        }
        val encrypted = packet.copyOfRange(12, packet.size - 8)
        val aad = packet.copyOfRange(4, 12)
        assertArrayEquals(payload, HomeKitCrypto.decrypt(key, nonce, encrypted, aad))
    }

    @Test
    fun fixedAirPlay2AlacFrameContainsBigEndianSilentPcm() {
        val encoded = AlacEncoder().encodeAirPlay2(ByteArray(352 * 2 * 2))

        // 23 header bits + 11264 PCM bits + 3 end bits, rounded to bytes.
        assertEquals(1_412, encoded.size)
        assertEquals(0x20, encoded[0].toInt() and 0xFF)
        assertEquals(0x00, encoded[1].toInt() and 0xFF)
        assertEquals(0x02, encoded[2].toInt() and 0xFF)
        assertEquals(0xC0, encoded[encoded.lastIndex].toInt() and 0xFF)
    }

    @Test
    fun initialMetadataIsACompleteDmapItem() {
        val metadata = buildAirPlayDmapMetadata(
            title = "Lampan",
            artist = "Android",
            album = "Capture",
        )

        assertEquals("mlit", metadata.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(metadata.size - 8, metadata.readBigEndianInt(4))
        val text = metadata.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("mikd"))
        assertTrue(text.contains("minm\u0000\u0000\u0000\u0006Lampan"))
        assertTrue(text.contains("asar\u0000\u0000\u0000\u0007Android"))
        assertTrue(text.contains("asal\u0000\u0000\u0000\u0007Capture"))
        assertTrue(text.contains("astn\u0000\u0000\u0000\u0002\u0000\u0001"))
    }

    @Test
    fun playbackProgressUsesTheCurrentStreamTimeline() {
        assertEquals(
            "99000/100000/109000",
            buildAirPlayProgress(
                rtpTimestamp = 100_000L,
                positionMs = 1_000L,
                durationMs = 10_000L,
                sampleRate = 1_000L,
            ),
        )
    }

    @Test
    fun playbackProgressWrapsAtTheRtpCounterBoundary() {
        assertEquals(
            "4294967196/100/900",
            buildAirPlayProgress(
                rtpTimestamp = 100L,
                positionMs = 200L,
                durationMs = 1_000L,
                sampleRate = 1_000L,
            ),
        )
    }

    private fun ByteArray.readBigEndianInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}
