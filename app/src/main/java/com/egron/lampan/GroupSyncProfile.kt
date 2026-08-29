package com.egron.lampan

import com.egron.lampan.raop.AirPlayDevice

internal const val MAX_RECEIVER_DELAY_MS = 500
internal const val RECEIVER_DELAY_STEP_MS = 5

/** User-tuned acoustic delays for one exact set of receiver routes. */
internal data class GroupSyncProfile(
    val referenceKey: String,
    val delaysMs: Map<String, Int>,
) {
    fun normalized(receiverKeys: Collection<String>): GroupSyncProfile {
        val keys = receiverKeys.distinct().sorted()
        require(keys.isNotEmpty()) { "A sync profile needs at least one receiver" }
        val reference = referenceKey.takeIf { it in keys } ?: keys.first()
        return GroupSyncProfile(
            referenceKey = reference,
            delaysMs = keys.associateWith { key ->
                if (key == reference) {
                    0
                } else {
                    delaysMs[key].orZero().coerceIn(0, MAX_RECEIVER_DELAY_MS)
                }
            },
        )
    }

    fun selectReference(receiverKeys: Collection<String>, receiverKey: String): GroupSyncProfile {
        require(receiverKey in receiverKeys) { "Reference must belong to the group" }
        // A new reference changes which physical receiver is treated as the slow path.
        // Reset the old corrections so the user can tune the group from that baseline.
        return GroupSyncProfile(
            referenceKey = receiverKey,
            delaysMs = receiverKeys.distinct().associateWith { 0 },
        ).normalized(receiverKeys)
    }

    fun withDelay(
        receiverKeys: Collection<String>,
        receiverKey: String,
        delayMs: Int,
    ): GroupSyncProfile {
        require(receiverKey in receiverKeys) { "Receiver must belong to the group" }
        return copy(
            delaysMs = delaysMs + (
                receiverKey to if (receiverKey == referenceKey) {
                    0
                } else {
                    delayMs.coerceIn(0, MAX_RECEIVER_DELAY_MS)
                }
            ),
        ).normalized(receiverKeys)
    }
}

internal fun receiverSyncKey(device: AirPlayDevice): String {
    val identity = device.receiverId?.takeIf(String::isNotBlank) ?: device.ip
    return "$identity|${device.preferredProtocol.name}"
}

internal fun groupSyncIdentity(receiverKeys: Collection<String>): String =
    receiverKeys.distinct().sorted().joinToString("\n")

private fun Int?.orZero(): Int = this ?: 0
