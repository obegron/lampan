package com.egron.lampan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.egron.lampan.raop.HomeKitPairingCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores receiver pairing identities encrypted by a non-exportable Android Keystore key. */
class AirPlay2CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(receiver: String): HomeKitPairingCredentials? {
        val encoded = preferences.getString(pairingPreferenceKey(receiver), null) ?: return null
        try {
            return AirPlay2CredentialCodec.decode(decrypt(encoded))
        } catch (error: Exception) {
            throw AirPlay2CredentialStoreException(
                "The saved AirPlay 2 pairing could not be unlocked; forget it and pair again",
                error,
            )
        }
    }

    fun save(receiver: String, credentials: HomeKitPairingCredentials) {
        try {
            preferences.edit()
                .putString(
                    pairingPreferenceKey(receiver),
                    encrypt(AirPlay2CredentialCodec.encode(credentials)),
                )
                .apply()
        } catch (error: Exception) {
            throw AirPlay2CredentialStoreException(
                "Could not protect the AirPlay 2 pairing with Android Keystore",
                error,
            )
        }
    }

    fun contains(receiver: String): Boolean = preferences.contains(pairingPreferenceKey(receiver))

    fun remove(receiver: String) {
        preferences.edit().remove(pairingPreferenceKey(receiver)).apply()
    }

    fun loadPassword(receiver: String): String? {
        val encoded = preferences.getString(passwordPreferenceKey(receiver), null) ?: return null
        try {
            return decrypt(encoded).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw AirPlay2CredentialStoreException(
                "The saved AirPlay password could not be unlocked; forget it and enter it again",
                error,
            )
        }
    }

    fun savePassword(receiver: String, password: String) {
        require(password.isNotEmpty()) { "AirPlay password must not be empty" }
        try {
            preferences.edit()
                .putString(
                    passwordPreferenceKey(receiver),
                    encrypt(password.toByteArray(Charsets.UTF_8)),
                )
                .apply()
        } catch (error: Exception) {
            throw AirPlay2CredentialStoreException(
                "Could not protect the AirPlay password with Android Keystore",
                error,
            )
        }
    }

    fun containsPassword(receiver: String): Boolean =
        preferences.contains(passwordPreferenceKey(receiver))

    fun removePassword(receiver: String) {
        preferences.edit().remove(passwordPreferenceKey(receiver)).apply()
    }

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    private fun decrypt(encoded: String): ByteArray {
        val stored = Base64.getDecoder().decode(encoded)
        require(stored.size > GCM_IV_BYTES) { "Stored secret is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, stored, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(stored, GCM_IV_BYTES, stored.size - GCM_IV_BYTES)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun pairingPreferenceKey(receiver: String): String =
        "receiver_${receiverDigest(receiver)}"

    private fun passwordPreferenceKey(receiver: String): String =
        "password_${receiverDigest(receiver)}"

    private fun receiverDigest(receiver: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(receiver.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val PREFERENCES_NAME = "LampanAirPlay2Credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lampan-airplay2-pairing-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

class AirPlay2CredentialStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal object AirPlay2CredentialCodec {
    private const val FORMAT_VERSION = 1
    private const val MAX_FIELD_BYTES = 1_024

    fun encode(credentials: HomeKitPairingCredentials): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(FORMAT_VERSION)
            writeBytes(data, credentials.controllerIdentifier.toByteArray(Charsets.UTF_8))
            writeBytes(data, credentials.controllerPrivateSeed)
            writeBytes(data, credentials.controllerPublicKey)
            writeBytes(data, credentials.accessoryIdentifier)
            writeBytes(data, credentials.accessoryPublicKey)
        }
        return output.toByteArray()
    }

    fun decode(encoded: ByteArray): HomeKitPairingCredentials {
        DataInputStream(ByteArrayInputStream(encoded)).use { data ->
            require(data.readInt() == FORMAT_VERSION) { "Unsupported pairing format" }
            val controllerIdentifier = String(readBytes(data), Charsets.UTF_8)
            val controllerPrivateSeed = readBytes(data)
            val controllerPublicKey = readBytes(data)
            val accessoryIdentifier = readBytes(data)
            val accessoryPublicKey = readBytes(data)
            require(data.available() == 0) { "Unexpected bytes after saved pairing" }
            require(controllerIdentifier.isNotEmpty()) { "Controller identifier is empty" }
            require(controllerPrivateSeed.size == 32) { "Controller private key is invalid" }
            require(controllerPublicKey.size == 32) { "Controller public key is invalid" }
            require(accessoryIdentifier.isNotEmpty()) { "Receiver identifier is empty" }
            require(accessoryPublicKey.size == 32) { "Receiver public key is invalid" }
            return HomeKitPairingCredentials(
                controllerIdentifier,
                controllerPrivateSeed,
                controllerPublicKey,
                accessoryIdentifier,
                accessoryPublicKey,
            )
        }
    }

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        require(value.size <= MAX_FIELD_BYTES) { "Pairing field is too large" }
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val length = input.readInt()
        require(length in 0..MAX_FIELD_BYTES) { "Invalid pairing field length" }
        return ByteArray(length).also(input::readFully)
    }
}
