package com.egron.lampan.raop

/** Maps a receiver-specific RTP counter onto one shared network-time anchor. */
internal class RtpNtpTimeline(
    private val sampleRate: Long = 44_100L,
) {
    private var anchorRtpTimestamp: Long? = null
    private var anchorUnixTimeNanos = 0L

    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
    }

    @Synchronized
    fun reset() {
        anchorRtpTimestamp = null
        anchorUnixTimeNanos = 0L
    }

    @Synchronized
    fun synchronizeAt(rtpTimestamp: Long, unixTimeMillis: Long) {
        anchorRtpTimestamp = rtpTimestamp and RTP_TIMESTAMP_MASK
        anchorUnixTimeNanos = unixTimeMillis * 1_000_000L
    }

    @Synchronized
    fun synchronizeAtNanos(rtpTimestamp: Long, unixTimeNanos: Long) {
        anchorRtpTimestamp = rtpTimestamp and RTP_TIMESTAMP_MASK
        anchorUnixTimeNanos = unixTimeNanos
    }

    @Synchronized
    fun shiftByMillis(deltaMillis: Int) {
        check(anchorRtpTimestamp != null) { "Timeline is not anchored" }
        anchorUnixTimeNanos += deltaMillis * 1_000_000L
    }

    @Synchronized
    fun unixTimeAt(rtpTimestamp: Long, fallbackUnixTimeMillis: Long): Long =
        unixNanosAt(rtpTimestamp, fallbackUnixTimeMillis * 1_000_000L) / 1_000_000L

    @Synchronized
    fun unixNanosAt(rtpTimestamp: Long, fallbackUnixNanos: Long): Long {
        val anchor = anchorRtpTimestamp ?: return fallbackUnixNanos
        val elapsedFrames = ((rtpTimestamp and RTP_TIMESTAMP_MASK) - anchor) and RTP_TIMESTAMP_MASK
        return anchorUnixTimeNanos + elapsedFrames * 1_000_000_000L / sampleRate
    }

    private companion object {
        const val RTP_TIMESTAMP_MASK = 0xFFFF_FFFFL
    }
}
