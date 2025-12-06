package com.egron.lampan.raop

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // Apple 2048-bit SRP Group parameters
    // N (Modulus)
    private val SRP_N_HEX = "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF5223750353A16853027E10249760AED7E72571FB6B342F2D1B71032E930F639684F2DF4840F0B08438D13C69D83AAD4BAD9953C3242158BF863804F4883219D8DD0979710A01523713DB893"
    private val SRP_N = BigInteger(SRP_N_HEX, 16)
    
    // g (Generator)
    private val SRP_G = BigInteger.valueOf(2)
    
    // k (Multiplier) = H(N, PAD(g))
    private val SRP_K_SHA512: BigInteger by lazy {
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(SRP_N.toByteArray().stripLeadingZero())
        digest.update(pad(SRP_G.toByteArray().stripLeadingZero(), SRP_N.toByteArray().stripLeadingZero().size))
        BigInteger(1, digest.digest())
    }

    fun hkdfSha512(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA512Digest())
        hkdf.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(outputLength)
        hkdf.generateBytes(output, 0, outputLength)
        return output
    }

    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv) // 128-bit auth tag
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(data)
    }
    
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, encryptedData: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(encryptedData)
    }

    // SRP Implementation
    class SrpClient(val username: String, val pin: String) {
        private val random = SecureRandom()
        private val digest = MessageDigest.getInstance("SHA-512")
        
        // a (ephemeral private key)
        private val a = BigInteger(256, random) // 32 bytes random
        
        // A (ephemeral public key) = g^a % N
        val A: BigInteger = SRP_G.modPow(a, SRP_N)
        
        var sessionKey: ByteArray? = null
        var sharedSecret: ByteArray? = null

        fun getClientPublicKey(): ByteArray {
            return A.toByteArray().stripLeadingZero()
        }

        fun computeSession(saltBytes: ByteArray, serverPublicKeyBBytes: ByteArray): ByteArray {
            val u = computeU(A, BigInteger(1, serverPublicKeyBBytes))
            val x = computeX(saltBytes, username, pin)
            
            // S = (B - k * g^x)^(a + u * x) % N
            val B = BigInteger(1, serverPublicKeyBBytes)
            val k = SRP_K_SHA512
            val gModPowX = SRP_G.modPow(x, SRP_N)
            val kgx = k.multiply(gModPowX).mod(SRP_N)
            var diff = B.subtract(kgx)
            if (diff < BigInteger.ZERO) diff = diff.add(SRP_N)
            
            val exp = u.multiply(x).add(a)
            val S = diff.modPow(exp, SRP_N)
            
            sharedSecret = S.toByteArray().stripLeadingZero()
            
            // K = H(S)
            digest.reset()
            val K = digest.digest(sharedSecret!!)
            sessionKey = K
            return K
        }
        
        // Compute M1 (Client Proof)
        fun computeM1(saltBytes: ByteArray, serverPublicKeyBBytes: ByteArray): ByteArray {
            // M1 = H(H(N) xor H(g), H(I), s, A, B, K)
            val H_N = sha512(SRP_N.toByteArray().stripLeadingZero())
            val H_g = sha512(SRP_G.toByteArray().stripLeadingZero())
            val H_N_xor_H_g = xor(H_N, H_g)
            val H_I = sha512(username.toByteArray())
            
            val h = MessageDigest.getInstance("SHA-512")
            h.update(H_N_xor_H_g)
            h.update(H_I)
            h.update(saltBytes)
            h.update(A.toByteArray().stripLeadingZero())
            h.update(serverPublicKeyBBytes)
            h.update(sessionKey!!)
            return h.digest()
        }
        
        private fun computeU(A: BigInteger, B: BigInteger): BigInteger {
            val h = MessageDigest.getInstance("SHA-512")
            // u = H(A, B)
            // Pad A and B to 256 bytes (2048 bits)? AirPlay sometimes does.
            // Let's assume standard SRP6a: H(PAD(A) | PAD(B))
            val N_len = SRP_N.toByteArray().stripLeadingZero().size
            h.update(pad(A.toByteArray().stripLeadingZero(), N_len))
            h.update(pad(B.toByteArray().stripLeadingZero(), N_len))
            return BigInteger(1, h.digest())
        }
        
        private fun computeX(salt: ByteArray, username: String, pin: String): BigInteger {
            val h = MessageDigest.getInstance("SHA-512")
            // x = H(s, H(I | ":" | P))
            h.update(username.toByteArray())
            h.update(":".toByteArray())
            h.update(pin.toByteArray())
            val hashIP = h.digest()
            
            h.reset()
            h.update(salt)
            h.update(hashIP)
            return BigInteger(1, h.digest())
        }
        
        private fun sha512(data: ByteArray): ByteArray {
            val h = MessageDigest.getInstance("SHA-512")
            return h.digest(data)
        }
        
        private fun xor(a: ByteArray, b: ByteArray): ByteArray {
            val res = ByteArray(a.size)
            for (i in a.indices) {
                res[i] = (a[i].toInt() xor b[i].toInt()).toByte()
            }
            return res
        }
        
        fun verifyM2(M2: ByteArray, A: ByteArray, M1: ByteArray, K: ByteArray): Boolean {
             val h = MessageDigest.getInstance("SHA-512")
             // M2 = H(A, M1, K)
             h.update(A)
             h.update(M1)
             h.update(K)
             val computedM2 = h.digest()
             return Arrays.equals(M2, computedM2)
        }
    }
    
    private fun pad(data: ByteArray, length: Int): ByteArray {
        if (data.size >= length) return data
        val padded = ByteArray(length)
        System.arraycopy(data, 0, padded, length - data.size, data.size)
        return padded
    }
    
    private fun ByteArray.stripLeadingZero(): ByteArray {
        if (this.size > 1 && this[0] == 0.toByte()) {
            return this.copyOfRange(1, this.size)
        }
        return this
    }
}