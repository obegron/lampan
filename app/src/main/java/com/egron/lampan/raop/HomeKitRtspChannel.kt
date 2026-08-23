package com.egron.lampan.raop

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Message-oriented adapter around HomeKit's framed encrypted byte stream. */
internal class HomeKitRtspChannel(
    private val cipher: HomeKitControlCipher,
) {
    fun encrypt(message: ByteArray): ByteArray = cipher.encrypt(message)

    fun readMessage(input: InputStream): ByteArray {
        val plaintext = ByteArrayOutputStream()
        var expectedSize: Int? = null
        while (expectedSize == null || plaintext.size() < expectedSize) {
            val prefix = readExactly(input, 2)
            val length = (prefix[0].toInt() and 0xFF) or
                ((prefix[1].toInt() and 0xFF) shl 8)
            require(length <= HomeKitControlCipher.MAX_FRAME_LENGTH) {
                "Encrypted RTSP frame declared $length bytes"
            }
            val encrypted = readExactly(input, length + CONTROL_TAG_BYTES)
            plaintext.write(cipher.decrypt(prefix + encrypted))
            if (expectedSize == null) {
                expectedSize = completeMessageSize(plaintext.toByteArray())
            }
        }
        return plaintext.toByteArray()
    }

    private fun completeMessageSize(data: ByteArray): Int? {
        val headerEnd = data.indexOf(HEADER_END)
        if (headerEnd < 0) return null
        val headerText = String(data, 0, headerEnd, StandardCharsets.ISO_8859_1)
        val contentLength = headerText.lineSequence()
            .firstOrNull { it.substringBefore(':').equals("Content-Length", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        return headerEnd + HEADER_END.size + contentLength
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw Exception("Connection closed during encrypted RTSP frame")
            offset += count
        }
        return result
    }

    private companion object {
        val HEADER_END = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        const val CONTROL_TAG_BYTES = 16
    }
}
