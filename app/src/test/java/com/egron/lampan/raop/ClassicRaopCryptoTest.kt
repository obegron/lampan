package com.egron.lampan.raop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicRaopCryptoTest {
    @Test
    fun encryptsCompletePayloadBlocksAndRestartsIvForEachPacket() {
        // NIST SP 800-38A AES-128 CBC vector, with an RTP header and partial tail.
        val crypto = ClassicRaopCrypto(
            hex("2b7e151628aed2a6abf7158809cf4f3c"),
            hex("000102030405060708090a0b0c0d0e0f"),
        )
        val header = ByteArray(12) { it.toByte() }
        val tail = byteArrayOf(1, 2, 3)
        val packet = header + hex("6bc1bee22e409f96e93d7e117393172a") + tail
        val original = packet.copyOf()
        val expected = header + hex("7649abac8119b246cee98e9b12e9197d") + tail
        assertArrayEquals(expected, crypto.encryptRaopAudioPacket(packet))
        assertArrayEquals(expected, crypto.encryptRaopAudioPacket(packet))
        assertArrayEquals(original, packet)
        assertArrayEquals(header + tail, crypto.encryptRaopAudioPacket(header + tail))
    }

    @Test
    fun announcesRsa2048WrappedKeyAndUnpaddedIv() {
        val iv = ByteArray(16) { it.toByte() }
        val attributes = ClassicRaopCrypto(ByteArray(16), iv).sdpAttributes()
            .trim().split("\r\n")
        assertEquals(2, attributes.size)
        assertEquals(256, java.util.Base64.getDecoder().decode(attributes[0].removePrefix("a=rsaaeskey:")).size)
        assertEquals("a=aesiv:AAECAwQFBgcICQoLDA0ODw", attributes[1])
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
