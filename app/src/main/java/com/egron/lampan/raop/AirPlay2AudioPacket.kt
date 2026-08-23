package com.egron.lampan.raop

import java.nio.ByteBuffer

/** Builds one native AirPlay 2 realtime RTP packet with authenticated audio. */
internal class AirPlay2AudioPacketCipher(audioKey: ByteArray) {
    private val audioKey = audioKey.copyOf()

    init {
        require(this.audioKey.size == 32) { "AirPlay 2 audio key must be 32 bytes" }
    }

    fun encode(
        sequence: Int,
        timestamp: Long,
        ssrc: Int,
        alacPayload: ByteArray,
        marker: Boolean,
    ): ByteArray {
        require(sequence in 0..0xFFFF) { "RTP sequence must fit in 16 bits" }
        val header = ByteBuffer.allocate(RTP_HEADER_BYTES).apply {
            put(0x80.toByte())
            put((PAYLOAD_TYPE or if (marker) 0x80 else 0).toByte())
            putShort(sequence.toShort())
            putInt(timestamp.toInt())
            putInt(ssrc)
        }.array()

        // AirPlay transmits the low eight nonce bytes after the tag. For the
        // realtime stream the sequence number occupies nonce bytes 4..5 in
        // little-endian order; timestamp + SSRC authenticate the RTP context.
        val nonce = ByteArray(12)
        nonce[4] = sequence.toByte()
        nonce[5] = (sequence ushr 8).toByte()
        val aad = header.copyOfRange(4, 12)
        val encrypted = HomeKitCrypto.encrypt(audioKey, nonce, alacPayload, aad)
        return HomeKitCrypto.concat(
            header,
            encrypted,
            nonce.copyOfRange(4, 12),
        )
    }

    private companion object {
        const val RTP_HEADER_BYTES = 12
        const val PAYLOAD_TYPE = 96
    }
}
