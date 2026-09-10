package com.egron.lampan

/** Absolute PCM frame positions; neither slider changes nor joins reset this clock. */
internal class GroupTimeline(val startUnixNanos: Long, val sampleRate: Long = 44_100L) {
    var nextFrame = 0L
        private set
    fun timeAt(frame: Long): Long = startUnixNanos +
        frame / sampleRate * 1_000_000_000L + frame % sampleRate * 1_000_000_000L / sampleRate
    fun advance(frames: Int) {
        require(frames >= 0)
        nextFrame += frames
    }
}
