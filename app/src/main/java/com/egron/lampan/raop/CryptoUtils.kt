package com.egron.lampan.raop

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
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
}
