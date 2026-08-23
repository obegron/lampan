package com.egron.lampan.raop

/** Maps a receiver-specific RTP counter onto one shared network-time anchor. */
internal class RtpNtpTimeline(
    private val sampleRate: Long = 44_100L,
) {
    private var anchorRtpTimestamp: Long? = null
    private var anchorUnixTimeMillis = 0L

    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
    }

    @Synchronized
    fun reset() {
        anchorRtpTimestamp = null
        anchorUnixTimeMillis = 0L
    }

    @Synchronized
    fun synchronizeAt(rtpTimestamp: Long, unixTimeMillis: Long) {
        anchorRtpTimestamp = rtpTimestamp and RTP_TIMESTAMP_MASK
        anchorUnixTimeMillis = unixTimeMillis
    }

    @Synchronized
    fun unixTimeAt(rtpTimestamp: Long, fallbackUnixTimeMillis: Long): Long {
        val anchor = anchorRtpTimestamp ?: return fallbackUnixTimeMillis
        val current = rtpTimestamp and RTP_TIMESTAMP_MASK
        val elapsedFrames = (current - anchor) and RTP_TIMESTAMP_MASK
        return anchorUnixTimeMillis + elapsedFrames * 1_000L / sampleRate
    }

    private companion object {
        const val RTP_TIMESTAMP_MASK = 0xFFFF_FFFFL
    }
}
