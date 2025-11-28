package com.egron.lampan.raop

import java.nio.ByteBuffer

class RtpPacket(
    private val payloadType: Int = 96,
    private val seqNum: Int,
    private val timestamp: Long,
    private val ssrc: Int,
    private val data: ByteArray
) {
    fun toByteArray(): ByteArray {
        val size = 12 + data.size
        val buffer = ByteBuffer.allocate(size)

        // Byte 0: V=2, P=0, X=0, CC=0
        buffer.put(0x80.toByte())

        // Byte 1: M=0 (usually), PT=payloadType
        // Marker bit is typically 1 for the first packet of a talkspurt, 0 otherwise.
        // For continuous audio, it's usually 0? AirPlay might use 1 for Sync? 
        // Let's stick to 0x60 (96) | 0x80 if M=1.
        buffer.put(payloadType.toByte())

        // Bytes 2-3: Sequence Number
        buffer.putShort(seqNum.toShort())

        // Bytes 4-7: Timestamp
        buffer.putInt(timestamp.toInt())

        // Bytes 8-11: SSRC
        buffer.putInt(ssrc)

        // Payload
        buffer.put(data)

        return buffer.array()
    }
}
