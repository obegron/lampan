package com.egron.lampan.raop

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** RSA key transport and per-packet AES-CBC used by classic RAOP receivers. */
internal class ClassicRaopCrypto(
    private val key: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
    private val iv: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
) {
    init {
        require(key.size == 16 && iv.size == 16)
    }

    fun sdpAttributes(): String {
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(RECEIVER_PUBLIC_KEY)),
            ),
            OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT),
        )
        val base64 = Base64.getEncoder().withoutPadding()
        return "a=rsaaeskey:${base64.encodeToString(rsa.doFinal(key))}\r\n" +
            "a=aesiv:${base64.encodeToString(iv)}\r\n"
    }

    fun encryptRaopAudioPacket(packet: ByteArray): ByteArray {
        require(packet.size >= 12)
        val result = packet.copyOf()
        // Leave the RTP header and any incomplete trailing AES block in cleartext.
        // RAOP restarts CBC with the session IV for every audio packet.
        val length = (packet.size - 12) / 16 * 16
        if (length > 0) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(packet, 12, length).copyInto(result, 12)
        }
        return result
    }

    private companion object {
        // Standard AirPort Express public key, also used by Kodi/libshairplay:
        // https://github.com/xbmc/xbmc/blob/master/xbmc/network/AirTunesServer.cpp
        const val RECEIVER_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA59dE8qLieItsH1WgjrcF" +
            "RKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabW" +
            "JmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/A" +
            "Mj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf" +
            "/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcr" +
            "tij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hE" +
            "ewIDAQAB"
    }
}
