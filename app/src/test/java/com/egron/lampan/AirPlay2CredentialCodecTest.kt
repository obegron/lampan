package com.egron.lampan

import com.egron.lampan.raop.HomeKitPairingCredentials
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlay2CredentialCodecTest {
    @Test
    fun pairingCredentialsRoundTripWithoutLosingBinaryFields() {
        val credentials = HomeKitPairingCredentials(
            controllerIdentifier = "lampan-controller",
            controllerPrivateSeed = ByteArray(32) { (it + 1).toByte() },
            controllerPublicKey = ByteArray(32) { (it + 41).toByte() },
            accessoryIdentifier = byteArrayOf(0, 1, 2, 0x7F, 0x80.toByte(), 0xFF.toByte()),
            accessoryPublicKey = ByteArray(32) { (it + 81).toByte() },
        )

        val decoded = AirPlay2CredentialCodec.decode(AirPlay2CredentialCodec.encode(credentials))

        assertEquals(credentials.controllerIdentifier, decoded.controllerIdentifier)
        assertArrayEquals(credentials.controllerPrivateSeed, decoded.controllerPrivateSeed)
        assertArrayEquals(credentials.controllerPublicKey, decoded.controllerPublicKey)
        assertArrayEquals(credentials.accessoryIdentifier, decoded.accessoryIdentifier)
        assertArrayEquals(credentials.accessoryPublicKey, decoded.accessoryPublicKey)
    }

    @Test
    fun pairingCredentialsRejectUnknownStorageVersion() {
        val encoded = ByteArray(4).also { it[3] = 2 }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlay2CredentialCodec.decode(encoded)
        }
    }
}
