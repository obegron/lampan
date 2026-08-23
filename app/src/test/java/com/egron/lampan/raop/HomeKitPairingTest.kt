package com.egron.lampan.raop

import java.math.BigInteger
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKitPairingTest {
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val pin = "1234"

    @Test
    fun tlv8RoundTripJoinsFragmentedSrpPublicKey() {
        val publicKey = ByteArray(HomeKitSrpClient.MODULUS_BYTES) { (it * 37).toByte() }
        val encoded = HomeKitTlv.encode(
            HomeKitTlvType.STATE to byteArrayOf(0x02),
            HomeKitTlvType.SALT to salt,
            HomeKitTlvType.PUBLIC_KEY to publicKey,
        )

        // Sony M2: State (3) + Salt (18) + PublicKey (255+129 with two headers each).
        assertEquals(409, encoded.size)
        assertEquals(0x03, encoded[21].toInt() and 0xFF)
        assertEquals(0xFF, encoded[22].toInt() and 0xFF)
        assertEquals(0x03, encoded[278].toInt() and 0xFF)
        assertEquals(129, encoded[279].toInt() and 0xFF)

        val decoded = HomeKitTlv.decode(encoded)
        assertArrayEquals(byteArrayOf(0x02), decoded[HomeKitTlvType.STATE])
        assertArrayEquals(salt, decoded[HomeKitTlvType.SALT])
        assertArrayEquals(publicKey, decoded[HomeKitTlvType.PUBLIC_KEY])
    }

    @Test
    fun tlv8RejectsTruncatedValues() {
        val malformed = byteArrayOf(HomeKitTlvType.PUBLIC_KEY.toByte(), 3, 1, 2)
        assertThrows(IllegalArgumentException::class.java) {
            HomeKitTlv.decode(malformed)
        }
    }

    @Test
    fun controlCipherFramesLargeMessagesAndAcceptsFragmentedSocketReads() {
        val senderWrite = ByteArray(32) { (it + 11).toByte() }
        val senderRead = ByteArray(32) { (it + 73).toByte() }
        val sender = HomeKitControlCipher(senderWrite, senderRead)
        val receiver = HomeKitControlCipher(senderRead, senderWrite)
        val plaintext = ByteArray(2_500) { (it * 13).toByte() }

        val encrypted = sender.encrypt(plaintext)
        // 2500 bytes split over three frames, each with a 2-byte prefix and 16-byte tag.
        assertEquals(2_554, encrypted.size)

        val decoded = ArrayList<Byte>()
        val fragments = listOf(
            encrypted.copyOfRange(0, 1),
            encrypted.copyOfRange(1, 37),
            encrypted.copyOfRange(37, 1_300),
            encrypted.copyOfRange(1_300, encrypted.size),
        )
        for (fragment in fragments) {
            receiver.decrypt(fragment).forEach(decoded::add)
        }
        assertArrayEquals(plaintext, decoded.toByteArray())

        val reply = "RTSP/1.0 200 OK\r\n\r\n".toByteArray()
        assertArrayEquals(reply, sender.decrypt(receiver.encrypt(reply)))
    }

    @Test
    fun controlCipherRejectsTamperedAuthenticationTag() {
        val writeKey = ByteArray(32) { (it + 1).toByte() }
        val readKey = ByteArray(32) { (it + 2).toByte() }
        val sender = HomeKitControlCipher(writeKey, readKey)
        val receiver = HomeKitControlCipher(readKey, writeKey)
        val encrypted = sender.encrypt("GET /info RTSP/1.0\r\n\r\n".toByteArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()

        assertThrows(HomeKitControlCipherException::class.java) {
            receiver.decrypt(encrypted)
        }
    }

    @Test
    fun normalPairSetupCompletesM1ThroughM6AndAuthenticatesAccessory() {
        val server = MockHomeKitSrpServer(pin, salt, byteArrayOf(7, 6, 5, 4, 3, 2, 1))
        val setup = HomeKitPairSetup(SecureRandom(), { "lampan-controller" })

        val m1 = HomeKitTlv.decode(setup.start())
        assertArrayEquals(byteArrayOf(0x00), m1[HomeKitTlvType.METHOD])
        assertArrayEquals(byteArrayOf(0x01), m1[HomeKitTlvType.STATE])
        assertEquals(HomeKitPairSetup.Stage.WAITING_FOR_M2, setup.stage)

        val m2 = HomeKitTlv.encode(
            HomeKitTlvType.STATE to byteArrayOf(0x02),
            HomeKitTlvType.SALT to salt,
            HomeKitTlvType.PUBLIC_KEY to server.publicKey,
        )
        val m3 = HomeKitTlv.decode(setup.continueWithPassword(m2, pin))
        val clientPublicKey = requireNotNull(m3[HomeKitTlvType.PUBLIC_KEY])
        val clientProof = requireNotNull(m3[HomeKitTlvType.PROOF])
        assertEquals(384, clientPublicKey.size)
        assertEquals(64, clientProof.size)
        val serverProof = server.acceptClientProof(clientPublicKey, clientProof)

        val m5Bytes = setup.continueAfterProof(
            HomeKitTlv.encode(
                HomeKitTlvType.STATE to byteArrayOf(0x04),
                HomeKitTlvType.PROOF to serverProof,
            ),
        )
        assertNotNull(m5Bytes)
        val m5 = HomeKitTlv.decode(requireNotNull(m5Bytes))
        val setupKey = HomeKitCrypto.hkdf(
            server.sessionKey,
            "Pair-Setup-Encrypt-Salt",
            "Pair-Setup-Encrypt-Info",
        )
        val controllerInner = HomeKitTlv.decode(
            HomeKitCrypto.decrypt(
                setupKey,
                "PS-Msg05",
                requireNotNull(m5[HomeKitTlvType.ENCRYPTED_DATA]),
            ),
        )
        assertEquals(
            "lampan-controller",
            String(requireNotNull(controllerInner[HomeKitTlvType.IDENTIFIER]), Charsets.UTF_8),
        )
        assertEquals(32, requireNotNull(controllerInner[HomeKitTlvType.PUBLIC_KEY]).size)
        assertEquals(64, requireNotNull(controllerInner[HomeKitTlvType.SIGNATURE]).size)

        val accessoryIdentifier = "sony-test-accessory".toByteArray()
        val (accessoryPrivateSeed, accessoryPublicKey) = HomeKitCrypto.generateEd25519(
            SecureRandom(),
        )
        val accessoryX = HomeKitCrypto.hkdf(
            server.sessionKey,
            "Pair-Setup-Accessory-Sign-Salt",
            "Pair-Setup-Accessory-Sign-Info",
        )
        val accessorySignature = HomeKitCrypto.sign(
            accessoryPrivateSeed,
            HomeKitCrypto.concat(accessoryX, accessoryIdentifier, accessoryPublicKey),
        )
        val accessoryInner = HomeKitTlv.encode(
            HomeKitTlvType.IDENTIFIER to accessoryIdentifier,
            HomeKitTlvType.PUBLIC_KEY to accessoryPublicKey,
            HomeKitTlvType.SIGNATURE to accessorySignature,
        )
        val credentials = setup.finish(
            HomeKitTlv.encode(
                HomeKitTlvType.STATE to byteArrayOf(0x06),
                HomeKitTlvType.ENCRYPTED_DATA to HomeKitCrypto.encrypt(
                    setupKey,
                    "PS-Msg06",
                    accessoryInner,
                ),
            ),
        )

        assertEquals(HomeKitPairSetup.Stage.COMPLETE, setup.stage)
        assertEquals("lampan-controller", credentials.controllerIdentifier)
        assertArrayEquals(accessoryIdentifier, credentials.accessoryIdentifier)
        assertArrayEquals(accessoryPublicKey, credentials.accessoryPublicKey)
    }

    @Test
    fun transientPairSetupStopsAtM4AndExposesSessionKey() {
        val server = MockHomeKitSrpServer(pin, salt, byteArrayOf(1, 3, 3, 7))
        val setup = HomeKitPairSetup(SecureRandom(), { "unused" })
        val m1 = HomeKitTlv.decode(setup.start(transient = true))
        assertArrayEquals(byteArrayOf(0x10), m1[HomeKitTlvType.FLAGS])

        val m3 = HomeKitTlv.decode(
            setup.continueWithPassword(
                HomeKitTlv.encode(
                    HomeKitTlvType.STATE to byteArrayOf(0x02),
                    HomeKitTlvType.SALT to salt,
                    HomeKitTlvType.PUBLIC_KEY to server.publicKey,
                ),
                pin,
            ),
        )
        val serverProof = server.acceptClientProof(
            requireNotNull(m3[HomeKitTlvType.PUBLIC_KEY]),
            requireNotNull(m3[HomeKitTlvType.PROOF]),
        )
        val m5 = setup.continueAfterProof(
            HomeKitTlv.encode(
                HomeKitTlvType.STATE to byteArrayOf(0x04),
                HomeKitTlvType.PROOF to serverProof,
            ),
        )

        assertNull(m5)
        assertEquals(HomeKitPairSetup.Stage.COMPLETE, setup.stage)
        assertArrayEquals(server.sessionKey, setup.transientSessionKey())
    }

    @Test
    fun pairSetupRejectsAnInvalidServerProof() {
        val server = MockHomeKitSrpServer(pin, salt, byteArrayOf(9, 8, 7, 6))
        val setup = HomeKitPairSetup(SecureRandom(), { "controller" })
        setup.start()
        setup.continueWithPassword(
            HomeKitTlv.encode(
                HomeKitTlvType.STATE to byteArrayOf(0x02),
                HomeKitTlvType.SALT to salt,
                HomeKitTlvType.PUBLIC_KEY to server.publicKey,
            ),
            pin,
        )

        assertThrows(HomeKitPairingException::class.java) {
            setup.continueAfterProof(
                HomeKitTlv.encode(
                    HomeKitTlvType.STATE to byteArrayOf(0x04),
                    HomeKitTlvType.PROOF to ByteArray(64),
                ),
            )
        }
    }

    @Test
    fun pairVerifyAuthenticatesBothSidesAndDerivesControlKeys() {
        val (controllerPrivateSeed, controllerPublicKey) = HomeKitCrypto.generateEd25519(
            SecureRandom(),
        )
        val (accessoryPrivateSeed, accessoryPublicKey) = HomeKitCrypto.generateEd25519(
            SecureRandom(),
        )
        val accessoryIdentifier = "sony-test-accessory".toByteArray()
        val credentials = HomeKitPairingCredentials(
            controllerIdentifier = "lampan-controller",
            controllerPrivateSeed = controllerPrivateSeed,
            controllerPublicKey = controllerPublicKey,
            accessoryIdentifier = accessoryIdentifier,
            accessoryPublicKey = accessoryPublicKey,
        )
        val verify = HomeKitPairVerify(credentials, SecureRandom())
        val m1 = HomeKitTlv.decode(verify.start())
        val controllerSessionPublicKey = requireNotNull(m1[HomeKitTlvType.PUBLIC_KEY])

        val accessorySessionKeys = HomeKitCrypto.generateX25519(SecureRandom())
        val sharedSecret = HomeKitCrypto.x25519SharedSecret(
            accessorySessionKeys.privateKey,
            controllerSessionPublicKey,
        )
        val verifyKey = HomeKitCrypto.hkdf(
            sharedSecret,
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
        )
        val accessorySignature = HomeKitCrypto.sign(
            accessoryPrivateSeed,
            HomeKitCrypto.concat(
                accessorySessionKeys.publicKey,
                accessoryIdentifier,
                controllerSessionPublicKey,
            ),
        )
        val accessoryInner = HomeKitTlv.encode(
            HomeKitTlvType.IDENTIFIER to accessoryIdentifier,
            HomeKitTlvType.SIGNATURE to accessorySignature,
        )
        val m3 = HomeKitTlv.decode(
            verify.continueWithAccessory(
                HomeKitTlv.encode(
                    HomeKitTlvType.STATE to byteArrayOf(0x02),
                    HomeKitTlvType.PUBLIC_KEY to accessorySessionKeys.publicKey,
                    HomeKitTlvType.ENCRYPTED_DATA to HomeKitCrypto.encrypt(
                        verifyKey,
                        "PV-Msg02",
                        accessoryInner,
                    ),
                ),
            ),
        )

        val controllerInner = HomeKitTlv.decode(
            HomeKitCrypto.decrypt(
                verifyKey,
                "PV-Msg03",
                requireNotNull(m3[HomeKitTlvType.ENCRYPTED_DATA]),
            ),
        )
        val returnedIdentifier = requireNotNull(controllerInner[HomeKitTlvType.IDENTIFIER])
        val returnedSignature = requireNotNull(controllerInner[HomeKitTlvType.SIGNATURE])
        assertArrayEquals(credentials.controllerIdentifier.toByteArray(), returnedIdentifier)
        assertTrue(
            HomeKitCrypto.verify(
                controllerPublicKey,
                HomeKitCrypto.concat(
                    controllerSessionPublicKey,
                    returnedIdentifier,
                    accessorySessionKeys.publicKey,
                ),
                returnedSignature,
            ),
        )

        val keys = verify.finish(
            HomeKitTlv.encode(HomeKitTlvType.STATE to byteArrayOf(0x04)),
        )
        assertEquals(HomeKitPairVerify.Stage.COMPLETE, verify.stage)
        assertArrayEquals(sharedSecret, keys.sharedSecret)
        assertArrayEquals(
            HomeKitCrypto.hkdf(
                sharedSecret,
                "Control-Salt",
                "Control-Write-Encryption-Key",
            ),
            keys.controlWriteKey,
        )
        assertEquals(32, keys.controlReadKey.size)
        assertEquals(32, keys.eventWriteKey.size)
        assertEquals(32, keys.eventReadKey.size)
    }

    @Test
    fun pairVerifyAcceptsAnEmptySuccessfulM4Body() {
        val (controllerPrivateSeed, controllerPublicKey) = HomeKitCrypto.generateEd25519(
            SecureRandom(),
        )
        val (accessoryPrivateSeed, accessoryPublicKey) = HomeKitCrypto.generateEd25519(
            SecureRandom(),
        )
        val accessoryIdentifier = "receiver".toByteArray()
        val credentials = HomeKitPairingCredentials(
            "controller",
            controllerPrivateSeed,
            controllerPublicKey,
            accessoryIdentifier,
            accessoryPublicKey,
        )
        val verify = HomeKitPairVerify(credentials, SecureRandom())
        val controllerSessionPublicKey = requireNotNull(
            HomeKitTlv.decode(verify.start())[HomeKitTlvType.PUBLIC_KEY],
        )
        val accessorySessionKeys = HomeKitCrypto.generateX25519(SecureRandom())
        val sharedSecret = HomeKitCrypto.x25519SharedSecret(
            accessorySessionKeys.privateKey,
            controllerSessionPublicKey,
        )
        val verifyKey = HomeKitCrypto.hkdf(
            sharedSecret,
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
        )
        val signature = HomeKitCrypto.sign(
            accessoryPrivateSeed,
            HomeKitCrypto.concat(
                accessorySessionKeys.publicKey,
                accessoryIdentifier,
                controllerSessionPublicKey,
            ),
        )
        verify.continueWithAccessory(
            HomeKitTlv.encode(
                HomeKitTlvType.STATE to byteArrayOf(0x02),
                HomeKitTlvType.PUBLIC_KEY to accessorySessionKeys.publicKey,
                HomeKitTlvType.ENCRYPTED_DATA to HomeKitCrypto.encrypt(
                    verifyKey,
                    "PV-Msg02",
                    HomeKitTlv.encode(
                        HomeKitTlvType.IDENTIFIER to accessoryIdentifier,
                        HomeKitTlvType.SIGNATURE to signature,
                    ),
                ),
            ),
        )

        assertArrayEquals(sharedSecret, verify.finish(ByteArray(0)).sharedSecret)
        assertEquals(HomeKitPairVerify.Stage.COMPLETE, verify.stage)
    }

    private class MockHomeKitSrpServer(
        password: String,
        private val salt: ByteArray,
        privateKey: ByteArray,
    ) {
        private val n = HomeKitSrpClient.MODULUS
        private val g = HomeKitSrpClient.GENERATOR
        private val b = BigInteger(1, privateKey)
        private val verifier: BigInteger
        private val serverB: BigInteger

        lateinit var sessionKey: ByteArray
            private set

        val publicKey: ByteArray
            get() = HomeKitCrypto.pad(serverB, HomeKitSrpClient.MODULUS_BYTES)

        init {
            val identityHash = HomeKitCrypto.sha512("Pair-Setup:$password".toByteArray())
            val privateX = BigInteger(1, HomeKitCrypto.sha512(salt, identityHash))
            verifier = g.modPow(privateX, n)
            val multiplier = BigInteger(
                1,
                HomeKitCrypto.sha512(
                    HomeKitCrypto.pad(n, HomeKitSrpClient.MODULUS_BYTES),
                    HomeKitCrypto.pad(g, HomeKitSrpClient.MODULUS_BYTES),
                ),
            )
            serverB = multiplier.multiply(verifier)
                .add(g.modPow(b, n))
                .mod(n)
        }

        fun acceptClientProof(clientPublicKey: ByteArray, clientProof: ByteArray): ByteArray {
            val clientA = BigInteger(1, clientPublicKey)
            require(clientA.mod(n) != BigInteger.ZERO)
            val paddedA = HomeKitCrypto.pad(clientA, HomeKitSrpClient.MODULUS_BYTES)
            val paddedB = HomeKitCrypto.pad(serverB, HomeKitSrpClient.MODULUS_BYTES)
            val scrambling = BigInteger(1, HomeKitCrypto.sha512(paddedA, paddedB))
            val sharedS = clientA
                .multiply(verifier.modPow(scrambling, n))
                .mod(n)
                .modPow(b, n)
            sessionKey = HomeKitCrypto.sha512(
                HomeKitCrypto.pad(sharedS, HomeKitSrpClient.MODULUS_BYTES),
            )

            val hashN = HomeKitCrypto.sha512(HomeKitCrypto.unsigned(n))
            val hashG = HomeKitCrypto.sha512(HomeKitCrypto.unsigned(g))
            val xor = ByteArray(hashN.size) {
                (hashN[it].toInt() xor hashG[it].toInt()).toByte()
            }
            val expectedProof = HomeKitCrypto.sha512(
                xor,
                HomeKitCrypto.sha512("Pair-Setup".toByteArray()),
                salt,
                paddedA,
                paddedB,
                sessionKey,
            )
            require(expectedProof.contentEquals(clientProof))
            return HomeKitCrypto.sha512(paddedA, expectedProof, sessionKey)
        }
    }
}
