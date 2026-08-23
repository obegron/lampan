package com.egron.lampan.raop

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class HomeKitPairingException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

data class HomeKitPairingCredentials(
    val controllerIdentifier: String,
    val controllerPrivateSeed: ByteArray,
    val controllerPublicKey: ByteArray,
    val accessoryIdentifier: ByteArray,
    val accessoryPublicKey: ByteArray,
)

data class HomeKitSessionKeys(
    val sharedSecret: ByteArray,
    val controlWriteKey: ByteArray,
    val controlReadKey: ByteArray,
    val eventWriteKey: ByteArray,
    val eventReadKey: ByteArray,
)

internal fun deriveHomeKitSessionKeys(sharedSecret: ByteArray): HomeKitSessionKeys =
    HomeKitSessionKeys(
        sharedSecret = sharedSecret.copyOf(),
        controlWriteKey = HomeKitCrypto.hkdf(
            sharedSecret,
            "Control-Salt",
            "Control-Write-Encryption-Key",
        ),
        controlReadKey = HomeKitCrypto.hkdf(
            sharedSecret,
            "Control-Salt",
            "Control-Read-Encryption-Key",
        ),
        eventWriteKey = HomeKitCrypto.hkdf(
            sharedSecret,
            "Events-Salt",
            "Events-Write-Encryption-Key",
        ),
        eventReadKey = HomeKitCrypto.hkdf(
            sharedSecret,
            "Events-Salt",
            "Events-Read-Encryption-Key",
        ),
    )

/** HomeKit pair-setup M1-M6, including the transient M1-M4 variant. */
class HomeKitPairSetup internal constructor(
    private val secureRandom: SecureRandom,
    private val controllerIdentifierFactory: () -> String,
) {
    enum class Stage {
        IDLE,
        WAITING_FOR_M2,
        WAITING_FOR_M4,
        WAITING_FOR_M6,
        COMPLETE,
    }

    constructor() : this(
        SecureRandom(),
        { UUID.randomUUID().toString().lowercase() },
    )

    var stage: Stage = Stage.IDLE
        private set

    private var transient = false
    private var srpClient: HomeKitSrpClient? = null
    private var srpSessionKey: ByteArray? = null
    private var pairSetupEncryptionKey: ByteArray? = null
    private var controllerIdentifier: String? = null
    private var controllerPrivateSeed: ByteArray? = null
    private var controllerPublicKey: ByteArray? = null

    fun start(transient: Boolean = false): ByteArray {
        ensureStage(Stage.IDLE)
        this.transient = transient
        stage = Stage.WAITING_FOR_M2
        val fields = mutableListOf(
            HomeKitTlvType.METHOD to byteArrayOf(0x00),
            HomeKitTlvType.STATE to byteArrayOf(0x01),
        )
        if (transient) {
            fields += HomeKitTlvType.FLAGS to byteArrayOf(0x10)
        }
        return HomeKitTlv.encode(fields)
    }

    /** Process M2 and build M3 after the user supplies the receiver's password. */
    @Throws(HomeKitPairingException::class)
    fun continueWithPassword(m2: ByteArray, password: String): ByteArray {
        ensureStage(Stage.WAITING_FOR_M2)
        val fields = decodeMessage(m2, expectedState = 2)
        val salt = fields.required(HomeKitTlvType.SALT, "salt")
        val serverPublicKey = fields.required(HomeKitTlvType.PUBLIC_KEY, "SRP public key")
        if (salt.size != 16) {
            throw HomeKitPairingException("Pair-setup M2 salt was ${salt.size} bytes, expected 16")
        }
        if (serverPublicKey.size != HomeKitSrpClient.MODULUS_BYTES) {
            throw HomeKitPairingException(
                "Pair-setup M2 public key was ${serverPublicKey.size} bytes, expected 384",
            )
        }

        val client = HomeKitSrpClient(password, secureRandom = secureRandom)
        val proof = try {
            client.processChallenge(salt, serverPublicKey)
        } catch (error: IllegalArgumentException) {
            throw HomeKitPairingException("Invalid pair-setup M2 challenge", error)
        }
        srpClient = client
        srpSessionKey = proof.sessionKey
        stage = Stage.WAITING_FOR_M4
        return HomeKitTlv.encode(
            HomeKitTlvType.STATE to byteArrayOf(0x03),
            HomeKitTlvType.PUBLIC_KEY to proof.clientPublicKey,
            HomeKitTlvType.PROOF to proof.clientProof,
        )
    }

    /**
     * Process M4. Returns M5 for normal pairing, or null when transient pairing
     * has completed at M4.
     */
    @Throws(HomeKitPairingException::class)
    fun continueAfterProof(m4: ByteArray): ByteArray? {
        ensureStage(Stage.WAITING_FOR_M4)
        val fields = decodeMessage(m4, expectedState = 4)
        val serverProof = fields.required(HomeKitTlvType.PROOF, "server proof")
        if (serverProof.size != 64 || srpClient?.verifyServerProof(serverProof) != true) {
            throw HomeKitPairingException("Pair-setup M4 server proof did not verify")
        }

        if (transient) {
            stage = Stage.COMPLETE
            return null
        }

        val sessionKey = srpSessionKey
            ?: throw HomeKitPairingException("Missing SRP session key")
        val identifier = controllerIdentifierFactory()
        val (privateSeed, publicKey) = HomeKitCrypto.generateEd25519(secureRandom)
        val encryptionKey = HomeKitCrypto.hkdf(
            sessionKey,
            "Pair-Setup-Encrypt-Salt",
            "Pair-Setup-Encrypt-Info",
        )
        val controllerX = HomeKitCrypto.hkdf(
            sessionKey,
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
        )
        val identifierBytes = identifier.toByteArray(Charsets.UTF_8)
        val signature = HomeKitCrypto.sign(
            privateSeed,
            HomeKitCrypto.concat(controllerX, identifierBytes, publicKey),
        )
        val inner = HomeKitTlv.encode(
            HomeKitTlvType.IDENTIFIER to identifierBytes,
            HomeKitTlvType.PUBLIC_KEY to publicKey,
            HomeKitTlvType.SIGNATURE to signature,
        )

        controllerIdentifier = identifier
        controllerPrivateSeed = privateSeed
        controllerPublicKey = publicKey
        pairSetupEncryptionKey = encryptionKey
        stage = Stage.WAITING_FOR_M6
        return HomeKitTlv.encode(
            HomeKitTlvType.STATE to byteArrayOf(0x05),
            HomeKitTlvType.ENCRYPTED_DATA to HomeKitCrypto.encrypt(
                encryptionKey,
                "PS-Msg05",
                inner,
            ),
        )
    }

    /** Process M6, authenticate the receiver, and return persistable credentials. */
    @Throws(HomeKitPairingException::class)
    fun finish(m6: ByteArray): HomeKitPairingCredentials {
        ensureStage(Stage.WAITING_FOR_M6)
        val fields = decodeMessage(m6, expectedState = 6)
        val encryptedData = fields.required(HomeKitTlvType.ENCRYPTED_DATA, "encrypted data")
        val encryptionKey = pairSetupEncryptionKey
            ?: throw HomeKitPairingException("Missing pair-setup encryption key")
        val decrypted = try {
            HomeKitCrypto.decrypt(encryptionKey, "PS-Msg06", encryptedData)
        } catch (error: Exception) {
            throw HomeKitPairingException("Pair-setup M6 could not be decrypted", error)
        }
        val inner = try {
            HomeKitTlv.decode(decrypted)
        } catch (error: IllegalArgumentException) {
            throw HomeKitPairingException("Pair-setup M6 contained malformed TLV8", error)
        }
        val accessoryIdentifier = inner.required(HomeKitTlvType.IDENTIFIER, "accessory identifier")
        val accessoryPublicKey = inner.required(HomeKitTlvType.PUBLIC_KEY, "accessory public key")
        val accessorySignature = inner.required(HomeKitTlvType.SIGNATURE, "accessory signature")
        if (accessoryPublicKey.size != 32) {
            throw HomeKitPairingException("Accessory Ed25519 public key was not 32 bytes")
        }

        val sessionKey = srpSessionKey
            ?: throw HomeKitPairingException("Missing SRP session key")
        val accessoryX = HomeKitCrypto.hkdf(
            sessionKey,
            "Pair-Setup-Accessory-Sign-Salt",
            "Pair-Setup-Accessory-Sign-Info",
        )
        val signedMaterial = HomeKitCrypto.concat(
            accessoryX,
            accessoryIdentifier,
            accessoryPublicKey,
        )
        if (!HomeKitCrypto.verify(accessoryPublicKey, signedMaterial, accessorySignature)) {
            throw HomeKitPairingException("Pair-setup M6 accessory signature did not verify")
        }

        val credentials = HomeKitPairingCredentials(
            controllerIdentifier = controllerIdentifier
                ?: throw HomeKitPairingException("Missing controller identifier"),
            controllerPrivateSeed = controllerPrivateSeed
                ?: throw HomeKitPairingException("Missing controller private key"),
            controllerPublicKey = controllerPublicKey
                ?: throw HomeKitPairingException("Missing controller public key"),
            accessoryIdentifier = accessoryIdentifier,
            accessoryPublicKey = accessoryPublicKey,
        )
        stage = Stage.COMPLETE
        return credentials
    }

    fun transientSessionKey(): ByteArray? =
        if (transient && stage == Stage.COMPLETE) srpSessionKey?.copyOf() else null

    private fun ensureStage(expected: Stage) {
        check(stage == expected) { "Expected pair-setup stage $expected, was $stage" }
    }
}

/** HomeKit pair-verify M1-M4 using credentials produced by [HomeKitPairSetup]. */
class HomeKitPairVerify internal constructor(
    private val credentials: HomeKitPairingCredentials,
    secureRandom: SecureRandom,
) {
    enum class Stage {
        IDLE,
        WAITING_FOR_M2,
        WAITING_FOR_M4,
        COMPLETE,
    }

    constructor(credentials: HomeKitPairingCredentials) : this(credentials, SecureRandom())

    private val ephemeral = HomeKitCrypto.generateX25519(secureRandom)
    private var sessionKeys: HomeKitSessionKeys? = null

    var stage: Stage = Stage.IDLE
        private set

    fun start(): ByteArray {
        ensureStage(Stage.IDLE)
        stage = Stage.WAITING_FOR_M2
        return HomeKitTlv.encode(
            HomeKitTlvType.STATE to byteArrayOf(0x01),
            HomeKitTlvType.PUBLIC_KEY to ephemeral.publicKey,
        )
    }

    @Throws(HomeKitPairingException::class)
    fun continueWithAccessory(m2: ByteArray): ByteArray {
        ensureStage(Stage.WAITING_FOR_M2)
        val fields = decodeMessage(m2, expectedState = 2)
        val accessorySessionPublicKey = fields.required(
            HomeKitTlvType.PUBLIC_KEY,
            "accessory session public key",
        )
        val encryptedData = fields.required(HomeKitTlvType.ENCRYPTED_DATA, "encrypted data")
        val sharedSecret = try {
            HomeKitCrypto.x25519SharedSecret(ephemeral.privateKey, accessorySessionPublicKey)
        } catch (error: IllegalArgumentException) {
            throw HomeKitPairingException("Invalid pair-verify M2 public key", error)
        }
        val verifyKey = HomeKitCrypto.hkdf(
            sharedSecret,
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
        )
        val decrypted = try {
            HomeKitCrypto.decrypt(verifyKey, "PV-Msg02", encryptedData)
        } catch (error: Exception) {
            throw HomeKitPairingException("Pair-verify M2 could not be decrypted", error)
        }
        val inner = try {
            HomeKitTlv.decode(decrypted)
        } catch (error: IllegalArgumentException) {
            throw HomeKitPairingException("Pair-verify M2 contained malformed TLV8", error)
        }
        val accessoryIdentifier = inner.required(
            HomeKitTlvType.IDENTIFIER,
            "accessory identifier",
        )
        val accessorySignature = inner.required(HomeKitTlvType.SIGNATURE, "accessory signature")
        if (!MessageDigest.isEqual(accessoryIdentifier, credentials.accessoryIdentifier)) {
            throw HomeKitPairingException("Pair-verify accessory identifier changed")
        }
        val accessoryMaterial = HomeKitCrypto.concat(
            accessorySessionPublicKey,
            accessoryIdentifier,
            ephemeral.publicKey,
        )
        if (!HomeKitCrypto.verify(
                credentials.accessoryPublicKey,
                accessoryMaterial,
                accessorySignature,
            )
        ) {
            throw HomeKitPairingException("Pair-verify accessory signature did not verify")
        }

        val controllerIdentifier = credentials.controllerIdentifier.toByteArray(Charsets.UTF_8)
        val controllerMaterial = HomeKitCrypto.concat(
            ephemeral.publicKey,
            controllerIdentifier,
            accessorySessionPublicKey,
        )
        val controllerSignature = HomeKitCrypto.sign(
            credentials.controllerPrivateSeed,
            controllerMaterial,
        )
        val responseInner = HomeKitTlv.encode(
            HomeKitTlvType.IDENTIFIER to controllerIdentifier,
            HomeKitTlvType.SIGNATURE to controllerSignature,
        )

        sessionKeys = deriveHomeKitSessionKeys(sharedSecret)
        stage = Stage.WAITING_FOR_M4
        return HomeKitTlv.encode(
            HomeKitTlvType.STATE to byteArrayOf(0x03),
            HomeKitTlvType.ENCRYPTED_DATA to HomeKitCrypto.encrypt(
                verifyKey,
                "PV-Msg03",
                responseInner,
            ),
        )
    }

    @Throws(HomeKitPairingException::class)
    fun finish(m4: ByteArray): HomeKitSessionKeys {
        ensureStage(Stage.WAITING_FOR_M4)
        // Some AirPlay receivers acknowledge M3 with an empty HTTP 200 body;
        // others return the canonical TLV8 State=4 response.
        if (m4.isNotEmpty()) {
            decodeMessage(m4, expectedState = 4)
        }
        stage = Stage.COMPLETE
        return sessionKeys ?: throw HomeKitPairingException("Missing pair-verify session keys")
    }

    private fun ensureStage(expected: Stage) {
        check(stage == expected) { "Expected pair-verify stage $expected, was $stage" }
    }
}

@Throws(HomeKitPairingException::class)
private fun decodeMessage(data: ByteArray, expectedState: Int): Map<Int, ByteArray> {
    val fields = try {
        HomeKitTlv.decode(data)
    } catch (error: IllegalArgumentException) {
        throw HomeKitPairingException("Malformed HomeKit TLV8", error)
    }
    fields[HomeKitTlvType.ERROR]?.let { error ->
        val code = error.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        throw HomeKitPairingException("Receiver rejected pairing with error $code")
    }
    val state = fields[HomeKitTlvType.STATE]
    if (state?.size != 1 || (state[0].toInt() and 0xFF) != expectedState) {
        throw HomeKitPairingException("Expected pairing state $expectedState")
    }
    return fields
}

@Throws(HomeKitPairingException::class)
private fun Map<Int, ByteArray>.required(type: Int, name: String): ByteArray =
    this[type] ?: throw HomeKitPairingException("Pairing response was missing $name")
