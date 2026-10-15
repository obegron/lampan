package com.egron.lampan.raop

/** One wall/monotonic conversion shared by all receivers, including NTP replies. */
internal class NetworkClock(
    private val unixAnchorNanos: Long = System.currentTimeMillis() * 1_000_000L,
    private val monotonicAnchorNanos: Long = System.nanoTime(),
    private val monotonicNow: () -> Long = System::nanoTime,
) {
    fun unixNanos(): Long = unixAt(monotonicNow())
    fun unixAt(monotonicNanos: Long): Long = unixAnchorNanos + monotonicNanos - monotonicAnchorNanos
    fun monotonicAt(unixNanos: Long): Long = monotonicAnchorNanos + unixNanos - unixAnchorNanos
}

internal fun ntpFromUnixNanos(nanos: Long): Long {
    val seconds = Math.floorDiv(nanos, 1_000_000_000L) + 2_208_988_800L
    val fraction = Math.floorMod(nanos, 1_000_000_000L) * 4_294_967_296L / 1_000_000_000L
    return (seconds shl 32) or fraction
}
