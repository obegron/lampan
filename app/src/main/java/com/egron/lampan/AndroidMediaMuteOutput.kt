package com.egron.lampan

import android.media.AudioManager

internal class AndroidMediaMuteOutput(private val audio: AudioManager) : MediaMuteOutput {
    override val isFixed: Boolean get() = audio.isVolumeFixed
    override val isMuted: Boolean get() = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun changeMute(muted: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0,
        )
    }
}
