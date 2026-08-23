package com.egron.lampan.raop

import java.io.ByteArrayOutputStream

class HomeKitControlCipherException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * AirPlay 2 encrypted RTSP/event framing used after pair-verify.
 *
 * Each plaintext chunk is at most 1024 bytes and becomes
 * `[2-byte little-endian length][ciphertext][16-byte tag]`. The length prefix
 * is authenticated as AAD. Send and receive directions have independent
 * little-endian 64-bit nonce counters.
 */
class HomeKitControlCipher(
    writeKey: ByteArray,
    readKey: ByteArray,
) {
    private val writeKey = writeKey.copyOf()
    private val readKey = readKey.copyOf()
    private var writeCounter = 0L
    private var readCounter = 0L
    private var pending = ByteArray(0)

    init {
        require(this.writeKey.size == 32) { "Control write key must be 32 bytes" }
        require(this.readKey.size == 32) { "Control read key must be 32 bytes" }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < plaintext.size) {
            val length = minOf(MAX_FRAME_LENGTH, plaintext.size - offset)
            val prefix = byteArrayOf(length.toByte(), (length ushr 8).toByte())
            val chunk = plaintext.copyOfRange(offset, offset + length)
            val encrypted = HomeKitCrypto.encrypt(
                writeKey,
                counterNonce(writeCounter),
                chunk,
                prefix,
            )
            output.write(prefix)
            output.write(encrypted)
            writeCounter++
            offset += length
        }
        return output.toByteArray()
    }

    /**
     * Add bytes received from the socket and return all complete plaintext
     * frames. Incomplete input is retained for the next call.
     */
    @Throws(HomeKitControlCipherException::class)
    fun decrypt(received: ByteArray): ByteArray {
        if (received.isNotEmpty()) {
            pending = HomeKitCrypto.concat(pending, received)
        }
        val output = ByteArrayOutputStream()
        var offset = 0
        while (pending.size - offset >= LENGTH_BYTES) {
            val length = (pending[offset].toInt() and 0xFF) or
                ((pending[offset + 1].toInt() and 0xFF) shl 8)
            if (length > MAX_FRAME_LENGTH) {
                throw HomeKitControlCipherException(
                    "Encrypted control frame declared $length bytes; maximum is $MAX_FRAME_LENGTH",
                )
            }
            val wireLength = LENGTH_BYTES + length + TAG_BYTES
            if (pending.size - offset < wireLength) break

            val prefix = pending.copyOfRange(offset, offset + LENGTH_BYTES)
            val encrypted = pending.copyOfRange(
                offset + LENGTH_BYTES,
                offset + wireLength,
            )
            val plaintext = try {
                HomeKitCrypto.decrypt(
                    readKey,
                    counterNonce(readCounter),
                    encrypted,
                    prefix,
                )
            } catch (error: Exception) {
                throw HomeKitControlCipherException(
                    "Encrypted control frame authentication failed",
                    error,
                )
            }
            output.write(plaintext)
            readCounter++
            offset += wireLength
        }

        if (offset > 0) {
            pending = pending.copyOfRange(offset, pending.size)
        }
        return output.toByteArray()
    }

    private fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        var value = counter
        for (index in 4 until nonce.size) {
            nonce[index] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return nonce
    }

    companion object {
        const val MAX_FRAME_LENGTH = 1024
        private const val LENGTH_BYTES = 2
        private const val TAG_BYTES = 16
    }
}
