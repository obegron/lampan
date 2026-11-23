package com.egron.lampan.raop

/** Whether the optional AirPlay 1 auth-setup returned a key to process. */
internal fun shouldUseRaopAuthSetupKey(response: RtspResponse): Boolean {
    return when {
        // Older receivers may explicitly reject this optional endpoint.
        response.code in setOf(404, 405, 501) -> false
        response.code != 200 -> error("AUTH-SETUP failed: ${response.code}")
        // ShairportQt acknowledges unimplemented requests with an empty 200.
        response.rawBody.isEmpty() -> false
        else -> true // Keep strict key parsing for nonempty replies.
    }
}
