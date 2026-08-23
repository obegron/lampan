package com.egron.lampan.raop

import java.io.Closeable
import java.security.SecureRandom

data class AirPlay2ConnectResult(
    val connection: AirPlay2Connection,
    val newlyPaired: Boolean,
)

/** A verified, encrypted AirPlay 2 control connection. */
class AirPlay2Connection internal constructor(
    internal val client: RtspClient,
    internal val sessionKeys: HomeKitSessionKeys,
    internal val headers: Map<String, String>,
) : Closeable {
    override fun close() = client.close()
}

/**
 * Establishes the authenticated AirPlay 2 control channel.
 *
 * First use performs HomeKit pair-setup M1-M6 with the receiver password and
 * returns credentials through [onCredentialsCreated]. Later connections use
 * those credentials directly for pair-verify. The final encrypted GET /info
 * validates the control channel before audio SETUP begins.
 */
class AirPlay2Client(
    private val host: String,
    private val port: Int = 7000,
    private val log: (String) -> Unit = {},
) {
    fun connect(
        credentials: HomeKitPairingCredentials?,
        password: String?,
        onCredentialsCreated: (HomeKitPairingCredentials) -> Unit = {},
    ): AirPlay2ConnectResult {
        val identity = SessionIdentity.create()
        val headers = identity.commonHeaders()
        val client = RtspClient(
            host = host,
            port = port,
            logCallback = { message -> log("[AP2 wire] $message") },
            logBinaryBodies = false,
        )

        try {
            log("[AP2] Opening control connection to $host:$port")
            client.connect()
            checked(client.sendRequest("GET", "/info", headers), "GET /info")
            log("[AP2] Receiver information accepted")

            val activeCredentials: HomeKitPairingCredentials
            val newlyPaired: Boolean
            if (credentials == null) {
                val pairingPassword = normalizePairingPassword(
                    password ?: throw AirPlay2ConnectionException(
                        "A receiver password or displayed setup code is required for first pairing",
                    ),
                )
                activeCredentials = pair(client, headers, pairingPassword)
                newlyPaired = true
                onCredentialsCreated(activeCredentials)
                log("[AP2] Pairing identity saved; future connections will not need the password")
            } else {
                activeCredentials = credentials
                newlyPaired = false
                log("[AP2] Using the saved receiver pairing")
            }

            val verify = HomeKitPairVerify(activeCredentials)
            log("[AP2] Pair-verify M1 -> M2")
            val m2 = checked(
                client.sendRequest(
                    "POST",
                    "/pair-verify",
                    headers + pairingHeaders,
                    rawBody = verify.start(),
                ),
                "pair-verify M2",
            )
            log("[AP2] Pair-verify M3 -> M4")
            val m4 = checked(
                client.sendRequest(
                    "POST",
                    "/pair-verify",
                    headers + pairingHeaders,
                    rawBody = verify.continueWithAccessory(m2.rawBody),
                ),
                "pair-verify M4",
            )
            val keys = verify.finish(m4.rawBody)
            log("[AP2] Receiver identity and pair-verify signatures accepted")

            client.enableEncryptedControl(
                HomeKitControlCipher(keys.controlWriteKey, keys.controlReadKey),
            )
            val encryptedInfo = checked(
                client.sendRequest("GET", "/info", headers),
                "encrypted GET /info",
            )
            log("[AP2] Encrypted control connection ready (GET /info ${encryptedInfo.code})")
            return AirPlay2ConnectResult(
                AirPlay2Connection(client, keys, headers),
                newlyPaired,
            )
        } catch (error: Exception) {
            client.close()
            if (error is AirPlay2ConnectionException) throw error
            throw AirPlay2ConnectionException(
                error.message ?: "AirPlay 2 connection failed",
                error,
            )
        }
    }

    /**
     * Establish an encrypted session with M1-M4 transient pairing. This is
     * intended for explicitly enabled diagnostics and registers no controller.
     */
    internal fun connectTransient(password: String): AirPlay2Connection {
        val identity = SessionIdentity.create()
        val headers = identity.commonHeaders(hkp = "4")
        val client = RtspClient(
            host = host,
            port = port,
            logCallback = { message -> log("[AP2 wire] $message") },
            logBinaryBodies = false,
        )
        try {
            log("[AP2] Opening transient control connection to $host:$port")
            client.connect()
            checked(client.sendRequest("GET", "/info", headers), "GET /info")
            val setup = HomeKitPairSetup()
            val transientHeaders = pairingHeaders + ("X-Apple-HKP" to "4")
            val m2 = checked(
                client.sendRequest(
                    "POST",
                    "/pair-setup",
                    headers + transientHeaders,
                    rawBody = setup.start(transient = true),
                ),
                "transient pair-setup M2",
            )
            val m4 = checked(
                client.sendRequest(
                    "POST",
                    "/pair-setup",
                    headers + transientHeaders,
                    rawBody = setup.continueWithPassword(
                        m2.rawBody,
                        normalizePairingPassword(password),
                    ),
                ),
                "transient pair-setup M4",
            )
            check(setup.continueAfterProof(m4.rawBody) == null)
            val keys = deriveHomeKitSessionKeys(
                setup.transientSessionKey()
                    ?: throw AirPlay2ConnectionException("Missing transient session key"),
            )
            client.enableEncryptedControl(
                HomeKitControlCipher(keys.controlWriteKey, keys.controlReadKey),
            )
            checked(client.sendRequest("GET", "/info", headers), "encrypted GET /info")
            log("[AP2] Transient encrypted control connection ready")
            return AirPlay2Connection(client, keys, headers)
        } catch (error: Exception) {
            client.close()
            if (error is AirPlay2ConnectionException) throw error
            throw AirPlay2ConnectionException(
                error.message ?: "Transient AirPlay 2 connection failed",
                error,
            )
        }
    }

    /** Explicit user action that asks a PIN-mode receiver to show its setup code. */
    fun requestSetupCode() {
        val identity = SessionIdentity.create()
        val client = RtspClient(host, port, logBinaryBodies = false)
        try {
            log("[AP2] Asking $host:$port to display a setup code")
            client.connect()
            checked(
                client.sendRequest("POST", "/pair-pin-start", identity.commonHeaders()),
                "pair-pin-start",
            )
            log("[AP2] Receiver accepted the setup-code request")
        } finally {
            client.close()
        }
    }

    private fun pair(
        client: RtspClient,
        headers: Map<String, String>,
        password: String,
    ): HomeKitPairingCredentials {
        val setup = HomeKitPairSetup()
        log("[AP2] Pair-setup M1 -> M2")
        val m2 = checked(
            client.sendRequest(
                "POST",
                "/pair-setup",
                headers + pairingHeaders,
                rawBody = setup.start(),
            ),
            "pair-setup M2",
        )
        log("[AP2] Pair-setup M3 -> M4")
        val m4 = checked(
            client.sendRequest(
                "POST",
                "/pair-setup",
                headers + pairingHeaders,
                rawBody = setup.continueWithPassword(m2.rawBody, password),
            ),
            "pair-setup M4",
        )
        val m5 = setup.continueAfterProof(m4.rawBody)
            ?: throw AirPlay2ConnectionException("Receiver unexpectedly selected transient pairing")
        log("[AP2] Password proof accepted; registering Lampan (M5 -> M6)")
        val m6 = checked(
            client.sendRequest(
                "POST",
                "/pair-setup",
                headers + pairingHeaders,
                rawBody = m5,
            ),
            "pair-setup M6",
        )
        return setup.finish(m6.rawBody).also {
            log("[AP2] Receiver registration and identity signature accepted")
        }
    }

    private fun checked(response: RtspResponse, step: String): RtspResponse {
        if (response.code !in 200..299) {
            throw AirPlay2ConnectionException("$step returned RTSP ${response.code}")
        }
        return response
    }

    private data class SessionIdentity(
        val clientInstance: String,
        val deviceId: String,
        val activeRemote: String,
    ) {
        fun commonHeaders(hkp: String = "3"): Map<String, String> = mapOf(
            "User-Agent" to "AirPlay/550.10",
            "X-Apple-HKP" to hkp,
            "X-Apple-Client-Name" to "Lampan",
            "X-Apple-Device-ID" to deviceId,
            "DACP-ID" to clientInstance,
            "Client-Instance" to clientInstance,
            "Active-Remote" to activeRemote,
        )

        companion object {
            fun create(): SessionIdentity {
                val random = SecureRandom()
                val bytes = ByteArray(8).also(random::nextBytes)
                val instance = bytes.joinToString("") { "%02X".format(it) }
                val device = bytes.copyOfRange(2, bytes.size)
                    .also { it[0] = (it[0].toInt() or 0x02).toByte() }
                    .joinToString(":") { "%02X".format(it) }
                val remote = random.nextInt(Int.MAX_VALUE).toString()
                return SessionIdentity(instance, device, remote)
            }
        }
    }

    private companion object {
        val pairingHeaders = mapOf(
            "Content-Type" to "application/octet-stream",
            "X-Apple-HKP" to "3",
        )
    }
}

class AirPlay2ConnectionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Preserve custom passwords, but canonicalize eight-digit HomeKit display codes. */
internal fun normalizePairingPassword(value: String): String {
    require(value.isNotEmpty()) { "Pairing password must not be empty" }
    val digits = value.filter(Char::isDigit)
    val codeCharactersOnly = value.all { it.isDigit() || it == ' ' || it == '-' }
    return if (codeCharactersOnly && digits.length == 8) {
        "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5)}"
    } else {
        value
    }
}
