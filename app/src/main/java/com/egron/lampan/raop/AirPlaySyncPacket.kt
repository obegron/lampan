package com.egron.lampan.raop

import java.nio.ByteBuffer

/** Shared realtime NTP sync format for AirPlay 1 and AirPlay 2. */
internal object AirPlaySyncPacket {
    const val MIN_LATENCY_FRAMES = 11_025
    const val RENDER_LATENCY_FRAMES = 88_200

    fun encode(first: Boolean, rtpTimestamp: Long, ntpTimestamp: Long): ByteArray =
        ByteBuffer.allocate(20).apply {
            put(if (first) 0x90.toByte() else 0x80.toByte())
            put(0xD4.toByte())
            putShort(7)
            // The receiver derives playback latency from the difference between
            // these RTP fields. A shared NTP anchor alone does not align sound
            // when the two protocols request different latencies.
            putInt((rtpTimestamp - RENDER_LATENCY_FRAMES).toInt())
            putLong(ntpTimestamp)
            putInt(rtpTimestamp.toInt())
        }.array()
}
