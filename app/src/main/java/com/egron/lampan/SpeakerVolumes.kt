package com.egron.lampan

import com.egron.lampan.raop.AirPlayDevice

/** Keep unclipped offsets so reaching a limit does not permanently change the balance. */
internal data class SpeakerVolumes(
    val group: Int = 50,
    val offsets: Map<String, Int> = emptyMap(),
) {
    val levels: Map<String, Int> get() = offsets.mapValues { (_, offset) ->
        if (group == 0) 0 else (group + offset).coerceIn(0, 100)
    }

    fun adjustGroup(percent: Int) = copy(group = percent.coerceIn(0, 100))

    fun adjustSpeaker(address: String, percent: Int): SpeakerVolumes {
        if (address !in offsets) return this
        if (offsets.size == 1 || group == 0) return from(levels + (address to percent.coerceIn(0, 100)))
        val nextGroup = maxOf(group, percent.coerceIn(0, 100))
        return SpeakerVolumes(nextGroup,
            offsets.mapValues { it.value + group - nextGroup } +
                (address to (percent.coerceIn(0, 100) - nextGroup)))
    }

    fun add(address: String, percent: Int): SpeakerVolumes {
        // A speaker joining a muted group must also start low when volume is raised.
        if (group == 0) return copy(offsets = offsets + (address to 0))
        val nextGroup = maxOf(group, percent.coerceIn(0, 100))
        return SpeakerVolumes(nextGroup,
            offsets.mapValues { it.value + group - nextGroup } +
                (address to (percent.coerceIn(0, 100) - nextGroup)))
    }

    fun remove(address: String): SpeakerVolumes {
        val remaining = offsets - address
        return if (remaining.size <= 1) from(levels - address) else copy(offsets = remaining)
    }

    companion object {
        fun from(levels: Map<String, Int>): SpeakerVolumes {
            val bounded = levels.mapValues { it.value.coerceIn(0, 100) }
            val group = bounded.values.maxOrNull() ?: 50
            return SpeakerVolumes(group, bounded.mapValues { it.value - group })
        }
    }
}

internal fun receiverVolumeKey(device: AirPlayDevice): String =
    device.receiverId?.takeIf(String::isNotBlank)?.uppercase() ?: device.ip
