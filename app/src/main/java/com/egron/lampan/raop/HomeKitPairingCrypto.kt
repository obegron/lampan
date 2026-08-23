package com.egron.lampan.raop

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class HomeKitSrpProof(
    val clientPublicKey: ByteArray,
    val clientProof: ByteArray,
    val sessionKey: ByteArray,
    val expectedServerProof: ByteArray,
)

/** SRP-6a-3072/SHA-512 client used by HomeKit pair-setup. */
class HomeKitSrpClient internal constructor(
    private val password: String,
    private val privateKey: ByteArray? = null,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val a: BigInteger = privateKey
        ?.let { BigInteger(1, it) }
        ?: BigInteger(256, secureRandom)
    private val publicA: BigInteger
    private var proof: HomeKitSrpProof? = null

    constructor(password: String) : this(password, null, SecureRandom())

    init {
        require(password.isNotEmpty()) { "Pairing password must not be empty" }
        require(a.signum() > 0) { "SRP private key must be positive" }
        publicA = GENERATOR.modPow(a, MODULUS)
    }

    fun publicKey(): ByteArray = HomeKitCrypto.pad(publicA, MODULUS_BYTES)

    fun processChallenge(salt: ByteArray, serverPublicKey: ByteArray): HomeKitSrpProof {
        require(salt.size == 16) { "HomeKit SRP salt must be 16 bytes" }
        require(serverPublicKey.isNotEmpty()) { "Server SRP public key is missing" }

        val serverB = BigInteger(1, serverPublicKey)
        require(serverB.mod(MODULUS) != BigInteger.ZERO) { "Invalid server SRP public key" }

        val paddedA = HomeKitCrypto.pad(publicA, MODULUS_BYTES)
        val paddedB = HomeKitCrypto.pad(serverB, MODULUS_BYTES)
        val multiplierK = BigInteger(
            1,
            HomeKitCrypto.sha512(
                HomeKitCrypto.pad(MODULUS, MODULUS_BYTES),
                HomeKitCrypto.pad(GENERATOR, MODULUS_BYTES),
            ),
        )
        val scramblingU = BigInteger(1, HomeKitCrypto.sha512(paddedA, paddedB))
        require(scramblingU != BigInteger.ZERO) { "Invalid SRP scrambling parameter" }

        val identityHash = HomeKitCrypto.sha512(
            "$IDENTITY:$password".toByteArray(Charsets.UTF_8),
        )
        val privateX = BigInteger(1, HomeKitCrypto.sha512(salt, identityHash))
        val gx = GENERATOR.modPow(privateX, MODULUS)
        val base = serverB
            .subtract(multiplierK.multiply(gx).mod(MODULUS))
            .mod(MODULUS)
        val exponent = a.add(scramblingU.multiply(privateX))
        val sharedS = base.modPow(exponent, MODULUS)
        val sessionKey = HomeKitCrypto.sha512(
            HomeKitCrypto.pad(sharedS, MODULUS_BYTES),
        )

        val hashN = HomeKitCrypto.sha512(HomeKitCrypto.unsigned(MODULUS))
        val hashG = HomeKitCrypto.sha512(HomeKitCrypto.unsigned(GENERATOR))
        val xor = ByteArray(hashN.size) { index ->
            (hashN[index].toInt() xor hashG[index].toInt()).toByte()
        }
        val clientProof = HomeKitCrypto.sha512(
            xor,
            HomeKitCrypto.sha512(IDENTITY.toByteArray(Charsets.UTF_8)),
            salt,
            paddedA,
            paddedB,
            sessionKey,
        )
        val expectedServerProof = HomeKitCrypto.sha512(
            paddedA,
            clientProof,
            sessionKey,
        )

        return HomeKitSrpProof(
            clientPublicKey = paddedA,
            clientProof = clientProof,
            sessionKey = sessionKey,
            expectedServerProof = expectedServerProof,
        ).also { proof = it }
    }

    fun verifyServerProof(serverProof: ByteArray): Boolean {
        val expected = proof?.expectedServerProof ?: return false
        return MessageDigest.isEqual(expected, serverProof)
    }

    companion object {
        const val MODULUS_BYTES = 384
        private const val IDENTITY = "Pair-Setup"

        internal val MODULUS = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
                "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
                "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
                "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
                "49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8" +
                "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C" +
                "180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D" +
                "04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7D" +
                "B3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D226" +
                "1AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFC" +
                "E0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        internal val GENERATOR = BigInteger.valueOf(5)
    }
}

internal data class HomeKitX25519KeyPair(
    val privateKey: X25519PrivateKeyParameters,
    val publicKey: ByteArray,
)

internal object HomeKitCrypto {
    fun sha512(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        chunks.forEach(digest::update)
        return digest.digest()
    }

    fun hkdf(inputKeyMaterial: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(
            HKDFParameters(
                inputKeyMaterial,
                salt.toByteArray(Charsets.UTF_8),
                info.toByteArray(Charsets.UTF_8),
            ),
        )
        return ByteArray(length).also { generator.generateBytes(it, 0, it.size) }
    }

    fun randomBytes(size: Int, secureRandom: SecureRandom): ByteArray =
        ByteArray(size).also(secureRandom::nextBytes)

    fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    fun pad(value: BigInteger, length: Int): ByteArray {
        val bytes = unsigned(value)
        require(bytes.size <= length) { "Integer does not fit in $length bytes" }
        return ByteArray(length).also {
            System.arraycopy(bytes, 0, it, length - bytes.size, bytes.size)
        }
    }

    fun encrypt(key: ByteArray, nonceLabel: String, plaintext: ByteArray): ByteArray =
        processAead(
            encrypting = true,
            key = key,
            nonce = labelNonce(nonceLabel),
            input = plaintext,
            additionalData = ByteArray(0),
        )

    @Throws(InvalidCipherTextException::class)
    fun decrypt(key: ByteArray, nonceLabel: String, ciphertext: ByteArray): ByteArray =
        processAead(
            encrypting = false,
            key = key,
            nonce = labelNonce(nonceLabel),
            input = ciphertext,
            additionalData = ByteArray(0),
        )

    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        additionalData: ByteArray,
    ): ByteArray = processAead(true, key, nonce, plaintext, additionalData)

    @Throws(InvalidCipherTextException::class)
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        additionalData: ByteArray,
    ): ByteArray = processAead(false, key, nonce, ciphertext, additionalData)

    private fun labelNonce(nonceLabel: String): ByteArray {
        val label = nonceLabel.toByteArray(Charsets.US_ASCII)
        require(label.size == 8) { "HomeKit nonce label must be eight ASCII bytes" }
        return ByteArray(12).also { System.arraycopy(label, 0, it, 4, label.size) }
    }

    private fun processAead(
        encrypting: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        additionalData: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20-Poly1305 nonce must be 12 bytes" }

        val cipher = ChaCha20Poly1305()
        cipher.init(encrypting, AEADParameters(KeyParameter(key), 128, nonce, additionalData))
        val output = ByteArray(cipher.getOutputSize(input.size))
        var written = cipher.processBytes(input, 0, input.size, output, 0)
        written += cipher.doFinal(output, written)
        return output.copyOf(written)
    }

    fun generateEd25519(secureRandom: SecureRandom): Pair<ByteArray, ByteArray> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(KeyGenerationParameters(secureRandom, 256))
        val pair = generator.generateKeyPair()
        val privateKey = pair.private as Ed25519PrivateKeyParameters
        val publicKey = pair.public as Ed25519PublicKeyParameters
        return privateKey.encoded to publicKey.encoded
    }

    fun ed25519PublicKey(privateSeed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(privateSeed, 0).generatePublicKey().encoded

    fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    fun generateX25519(secureRandom: SecureRandom): HomeKitX25519KeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(KeyGenerationParameters(secureRandom, 255))
        val pair = generator.generateKeyPair()
        val privateKey = pair.private as X25519PrivateKeyParameters
        val publicKey = pair.public as X25519PublicKeyParameters
        return HomeKitX25519KeyPair(privateKey, publicKey.encoded)
    }

    fun x25519SharedSecret(
        privateKey: X25519PrivateKeyParameters,
        peerPublicKey: ByteArray,
    ): ByteArray {
        require(peerPublicKey.size == 32) { "X25519 public key must be 32 bytes" }
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        return ByteArray(agreement.agreementSize).also {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), it, 0)
        }
    }

    fun concat(vararg values: ByteArray): ByteArray {
        val output = ByteArray(values.sumOf { it.size })
        var offset = 0
        for (value in values) {
            System.arraycopy(value, 0, output, offset, value.size)
            offset += value.size
        }
        return output
    }
}
