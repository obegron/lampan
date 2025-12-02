package com.egron.lampan.raop

import java.nio.ByteBuffer

class RtpPacket(
    private val payloadType: Int = 96,
    private val seqNum: Int,
    private val timestamp: Long,
    private val ssrc: Int,
    private val data: ByteArray,
    private val marker: Boolean = false
) {
    fun toByteArray(): ByteArray {
        val size = 12 + data.size
        val buffer = ByteBuffer.allocate(size)

        // Byte 0: V=2, P=0, X=0, CC=0
        buffer.put(0x80.toByte())

        // Byte 1: M=Marker, PT=payloadType
        var b1 = payloadType
        if (marker) {
            b1 = b1 or 0x80
        }
        buffer.put(b1.toByte())

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
