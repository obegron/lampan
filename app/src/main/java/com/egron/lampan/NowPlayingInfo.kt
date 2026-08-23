package com.egron.lampan

/** Media-session information that can be mirrored to an AirPlay receiver. */
data class NowPlayingInfo(
    val sourcePackage: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val isPlaying: Boolean = true,
    val artworkJpeg: ByteArray? = null,
)
