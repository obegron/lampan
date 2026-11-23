package com.egron.lampan

/** Own only the mute we applied; never overwrite the user's media volume index. */
internal class PhoneMediaMute(private val media: MediaMuteOutput) {
    private var ownsMute = false

    fun apply() {
        if (ownsMute || media.isFixed || media.isMuted) return
        media.changeMute(true)
        ownsMute = media.isMuted
    }

    fun restore() {
        if (!ownsMute) return
        // If the user raised media volume, leave that choice intact.
        if (media.isMuted) media.changeMute(false)
        ownsMute = false
    }
}

internal interface MediaMuteOutput {
    val isFixed: Boolean
    val isMuted: Boolean
    fun changeMute(muted: Boolean)
}
