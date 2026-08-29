package com.egron.lampan

import android.content.Context
import android.content.SharedPreferences
import com.egron.lampan.raop.AirPlayDevice
import com.egron.lampan.raop.AirPlayProtocol
import java.security.MessageDigest
import java.util.Base64

class PreferencesManager(context: Context) {
    private val PREFS_NAME = "LampanPrefs"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveIpForSsid(ssid: String, ip: String) {
        prefs.edit().putString("IP_$ssid", ip).apply()
    }

    fun getIpForSsid(ssid: String): String {
        return prefs.getString("IP_$ssid", "") ?: ""
    }
    
    fun saveLastUsedIp(ip: String) {
        prefs.edit().putString("LAST_IP", ip).apply()
    }
    
    fun getLastUsedIp(): String {
        return prefs.getString("LAST_IP", "") ?: ""
    }

    fun saveVolume(volume: Float) {
        prefs.edit().putFloat(LAST_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    fun getVolume(): Float =
        prefs.getFloat(LAST_VOLUME, DEFAULT_VOLUME).coerceIn(0f, 1f)

    fun saveDebugInformationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SHOW_DEBUG_INFORMATION, enabled).apply()
    }

    fun isDebugInformationEnabled(): Boolean =
        prefs.getBoolean(SHOW_DEBUG_INFORMATION, false)

    fun saveNowPlayingInformationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(NOW_PLAYING_INFORMATION_ENABLED, enabled).apply()
    }

    fun isNowPlayingInformationEnabled(): Boolean =
        prefs.getBoolean(NOW_PLAYING_INFORMATION_ENABLED, true)

    fun saveDarkThemeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(DARK_THEME_ENABLED, enabled).apply()
    }

    fun isDarkThemeEnabled(): Boolean = prefs.getBoolean(DARK_THEME_ENABLED, true)

    internal fun getGroupSyncProfile(receiverKeys: Collection<String>): GroupSyncProfile {
        val keys = receiverKeys.distinct().sorted()
        require(keys.size > 1) { "Group timing requires at least two receivers" }
        val prefix = groupSyncPrefix(keys)
        val reference = prefs.getString("${prefix}_reference", null)
            ?.takeIf { it in keys }
            ?: keys.first()
        return GroupSyncProfile(
            referenceKey = reference,
            delaysMs = keys.associateWith { receiverKey ->
                prefs.getInt("${prefix}_delay_${preferenceDigest(receiverKey)}", 0)
            },
        ).normalized(keys)
    }

    internal fun saveGroupSyncProfile(
        receiverKeys: Collection<String>,
        profile: GroupSyncProfile,
    ) {
        val keys = receiverKeys.distinct().sorted()
        require(keys.size > 1) { "Group timing requires at least two receivers" }
        val normalized = profile.normalized(keys)
        val prefix = groupSyncPrefix(keys)
        prefs.edit().apply {
            putString("${prefix}_reference", normalized.referenceKey)
            keys.forEach { receiverKey ->
                putInt(
                    "${prefix}_delay_${preferenceDigest(receiverKey)}",
                    normalized.delaysMs[receiverKey] ?: 0,
                )
            }
        }.apply()
    }

    fun saveAirPlayCapabilities(device: AirPlayDevice, networkName: String? = null) {
        val receivers = listOfNotNull(device.airPlay1Port, device.airPlay2Port)
            .map { port -> "${device.ip}:$port" }
        // Discovery returns fresh capability records. Keep an explicit user
        // choice when the same device is scanned again.
        val protocolPreference = device.protocolPreference ?: receivers
            .asSequence()
            .mapNotNull(::getAirPlayCapabilities)
            .mapNotNull(AirPlayDevice::protocolPreference)
            .firstOrNull()
        receivers.forEach { receiver ->
            val prefix = capabilityPrefix(receiver)
            prefs.edit()
                .putString("${prefix}_name", device.name)
                .putString("${prefix}_ip", device.ip)
                .putString("${prefix}_receiver_id", device.receiverId)
                .putString("${prefix}_protocol_preference", protocolPreference?.name)
                .putInt("${prefix}_airplay1_port", device.airPlay1Port ?: NO_PORT)
                .putInt("${prefix}_airplay2_port", device.airPlay2Port ?: NO_PORT)
                .putInt(
                    "${prefix}_airplay2_password",
                    when (device.airPlay2RequiresPassword) {
                        true -> PASSWORD_REQUIRED
                        false -> PASSWORD_NOT_REQUIRED
                        null -> PASSWORD_UNKNOWN
                    },
                )
                .apply()
        }
        val knownReceivers = prefs.getStringSet(KNOWN_RECEIVERS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { addAll(receivers) }
        prefs.edit().putStringSet(KNOWN_RECEIVERS, knownReceivers).apply()
        if (networkName.isUsableNetworkName()) {
            val networkKey = networkReceiversKey(requireNotNull(networkName))
            val networkReceivers = prefs.getStringSet(networkKey, emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { addAll(receivers) }
            prefs.edit().putStringSet(networkKey, networkReceivers).apply()
        }
    }

    fun getAirPlayCapabilities(receiver: String): AirPlayDevice? {
        val prefix = capabilityPrefix(receiver)
        val ip = prefs.getString("${prefix}_ip", null) ?: return null
        val airPlay1Port = prefs.getInt("${prefix}_airplay1_port", NO_PORT)
            .takeIf { it != NO_PORT }
        val airPlay2Port = prefs.getInt("${prefix}_airplay2_port", NO_PORT)
            .takeIf { it != NO_PORT }
        if (airPlay1Port == null && airPlay2Port == null) return null
        return AirPlayDevice(
            name = prefs.getString("${prefix}_name", null) ?: ip,
            ip = ip,
            airPlay1Port = airPlay1Port,
            airPlay2Port = airPlay2Port,
            airPlay2RequiresPassword = when (
                prefs.getInt("${prefix}_airplay2_password", PASSWORD_UNKNOWN)
            ) {
                PASSWORD_REQUIRED -> true
                PASSWORD_NOT_REQUIRED -> false
                else -> null
            },
            receiverId = prefs.getString("${prefix}_receiver_id", null),
            protocolPreference = prefs.getString("${prefix}_protocol_preference", null)
                ?.let { value ->
                    runCatching { AirPlayProtocol.valueOf(value) }.getOrNull()
                },
        )
    }

    fun getKnownAirPlayDevices(networkName: String? = null): List<AirPlayDevice> {
        val receiverAddresses = if (networkName.isUsableNetworkName()) {
            networkReceiverAddresses(requireNotNull(networkName))
        } else {
            prefs.getStringSet(KNOWN_RECEIVERS, emptySet()).orEmpty()
        }
        return receiverAddresses
            .mapNotNull(::getAirPlayCapabilities)
            .distinctBy { it.ip }
            .sortedBy { it.name.lowercase() }
    }

    private fun networkReceiverAddresses(networkName: String): Set<String> {
        val networkKey = networkReceiversKey(networkName)
        prefs.getStringSet(networkKey, null)?.let { return it.toSet() }

        // Older Lampan versions remembered one last address per SSID but kept
        // the device list global. Seed this network with that receiver and its
        // alternate AirPlay port. A scan will add any other devices once.
        val legacyAddress = getIpForSsid(networkName)
        val legacyHost = legacyAddress.substringBefore(':').trim()
        val migrated = prefs.getStringSet(KNOWN_RECEIVERS, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { address ->
                address == legacyAddress || (
                    legacyHost.isNotEmpty() &&
                        getAirPlayCapabilities(address)?.ip == legacyHost
                    )
            }
        prefs.edit().putStringSet(networkKey, migrated).apply()
        return migrated
    }

    private fun capabilityPrefix(receiver: String): String {
        return "AIRPLAY_CAP_${preferenceDigest(receiver)}"
    }

    private fun groupSyncPrefix(receiverKeys: Collection<String>): String =
        "GROUP_SYNC_${preferenceDigest(groupSyncIdentity(receiverKeys))}"

    private fun networkReceiversKey(networkName: String): String =
        "NETWORK_RECEIVERS_${preferenceDigest(networkName)}"

    private fun preferenceDigest(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val KNOWN_RECEIVERS = "KNOWN_AIRPLAY_RECEIVERS"
        const val LAST_VOLUME = "LAST_VOLUME"
        const val SHOW_DEBUG_INFORMATION = "SHOW_DEBUG_INFORMATION"
        const val NOW_PLAYING_INFORMATION_ENABLED = "NOW_PLAYING_INFORMATION_ENABLED"
        const val DARK_THEME_ENABLED = "DARK_THEME_ENABLED"
        const val DEFAULT_VOLUME = 0.5f
        const val NO_PORT = -1
        const val PASSWORD_UNKNOWN = -1
        const val PASSWORD_NOT_REQUIRED = 0
        const val PASSWORD_REQUIRED = 1
    }
}

internal fun String?.isUsableNetworkName(): Boolean =
    !this.isNullOrBlank() && this != "<unknown ssid>"
