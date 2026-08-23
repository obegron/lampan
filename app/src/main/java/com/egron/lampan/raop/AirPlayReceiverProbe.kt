package com.egron.lampan.raop

data class AirPlayReceiverInfo(
    val receiverId: String?,
    val receiverIds: Set<String>,
    val name: String?,
    val model: String?,
)

/** Quietly verifies that an endpoint answers AirPlay GET /info. */
class AirPlayReceiverProbe(
    private val host: String,
    private val port: Int,
) {
    fun getInfo(): AirPlayReceiverInfo {
        val client = RtspClient(
            host = host,
            port = port,
            logBinaryBodies = false,
            logCallback = {},
        )
        try {
            client.connect()
            val response = client.sendRequest(
                method = "GET",
                url = "/info",
                headers = mapOf(
                    "User-Agent" to "AirPlay/550.10",
                    "X-Apple-Client-Name" to "Lampan",
                ),
                logExchange = false,
            )
            if (response.code !in 200..299) {
                throw AirPlay2ConnectionException("GET /info returned RTSP ${response.code}")
            }
            if (response.rawBody.isEmpty()) {
                return AirPlayReceiverInfo(
                    receiverId = null,
                    receiverIds = emptySet(),
                    name = null,
                    model = null,
                )
            }
            val info = try {
                client.parseBinaryPlist(response.rawBody)
            } catch (error: Exception) {
                throw AirPlay2ConnectionException("GET /info returned an invalid plist", error)
            }
            // AirPlay receivers may expose both a hardware-style deviceID and a
            // pairing identity (pi). Discovery TXT records are not consistent
            // about which one they advertise, so retain both as valid aliases.
            val deviceId = info.stringValue("deviceID")
                ?.let(::normalizeAirPlayReceiverId)
            val pairingId = info.stringValue("pi")
                ?.let(::normalizeAirPlayReceiverId)
            return AirPlayReceiverInfo(
                receiverId = deviceId ?: pairingId,
                receiverIds = setOfNotNull(deviceId, pairingId),
                name = info.stringValue("name"),
                model = info.stringValue("model"),
            )
        } finally {
            client.close()
        }
    }
}

private fun com.dd.plist.NSDictionary.stringValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.toString()

internal fun normalizeAirPlayReceiverId(value: String): String {
    val compact = value.filterNot { it == ':' || it == '-' }
    return if (
        compact.length == 12 &&
        compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    ) {
        compact.chunked(2).joinToString(":").uppercase()
    } else {
        value.trim().uppercase()
    }
}
