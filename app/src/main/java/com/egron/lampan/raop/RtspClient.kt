package com.egron.lampan.raop

import android.util.Log
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

data class RtspResponse(val code: Int, val headers: Map<String, String>, val body: String, val rawBody: ByteArray = ByteArray(0))

class RtspClient(private val host: String, private val port: Int, private val logCallback: ((String) -> Unit)? = null) {
    private val TAG = "RtspClient"
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var cseq = 1

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logCallback?.invoke(msg)
    }

    fun connect() {
        log("Connecting to $host:$port...")
        socket = Socket(host, port)
        inputStream = socket!!.getInputStream()
        outputStream = socket!!.getOutputStream()
    }
    
    fun getLocalAddress(): java.net.InetAddress? {
        return socket?.localAddress
    }

    fun sendRequest(method: String, url: String, headers: Map<String, String>, body: String = "", rawBody: ByteArray? = null): RtspResponse {
        val out = outputStream ?: throw IllegalStateException("Not connected")
        
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        
        // Default headers if not provided
        if (!headers.containsKey("User-Agent")) {
            sb.append("User-Agent: AirPlay/1.0\r\n")
        }
        
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        
        val contentBytes = rawBody ?: body.toByteArray(StandardCharsets.ISO_8859_1)
        if (contentBytes.isNotEmpty()) {
            sb.append("Content-Length: ${contentBytes.size}\r\n")
        }
        
        sb.append("\r\n")
        
        // Debug logging
        log("--- Sending Request ---")
        log(sb.toString().trim())
        if (contentBytes.isNotEmpty()) {
            if (rawBody == null) {
                log(body)
            } else {
                log("<Binary Body: ${contentBytes.size} bytes>")
                // Log hex dump of first 32 bytes for verification
                val hex = contentBytes.take(32).joinToString("") { "%02x".format(it) }
                log("Body Hex (first 32): $hex")
            }
        }
        log("-----------------------")

        val headerBytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        out.write(headerBytes)
        
        if (contentBytes.isNotEmpty()) {
            out.write(contentBytes)
        }
        out.flush()

        return readResponse()
    }

    private fun readResponse(): RtspResponse {
        val inStream = inputStream ?: throw IllegalStateException("Not connected")
        
        val statusLine = readLine(inStream) ?: throw Exception("Connection closed")
        // Status line e.g. "RTSP/1.0 200 OK"
        val parts = statusLine.split(" ")
        val code = if (parts.size >= 2) parts[1].toInt() else 0

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(inStream)
            if (line.isNullOrEmpty()) break
            val split = line.split(":", limit = 2)
            if (split.size == 2) {
                headers[split[0].trim()] = split[1].trim()
            }
        }

        // Debug logging
        log("--- Received Response ---")
        log(statusLine)
        headers.forEach { (k, v) -> log("$k: $v") }
        log("-------------------------")

        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val rawBodyBytes = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val count = inStream.read(buffer, totalRead, contentLength - totalRead)
                if (count == -1) break
                totalRead += count
            }
            buffer
        } else {
            ByteArray(0)
        }
        
        val bodyString = if (rawBodyBytes.isNotEmpty()) String(rawBodyBytes, StandardCharsets.ISO_8859_1) else ""

        return RtspResponse(code, headers, bodyString, rawBodyBytes)
    }

    private fun readLine(inputStream: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b = inputStream.read()
        if (b == -1) return null
        
        while (b != -1) {
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                baos.write(b)
            }
            b = inputStream.read()
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    fun close() {
        socket?.close()
    }

    fun parseBinaryPlist(data: ByteArray): NSDictionary {
        val inputStream = ByteArrayInputStream(data)
        val propertyList = PropertyListParser.parse(inputStream)
        if (propertyList is NSDictionary) {
            return propertyList
        } else {
            throw IllegalArgumentException("Expected NSDictionary as root of plist, but got ${propertyList?.javaClass?.simpleName}")
        }
    }

    // Helper to parse the auth-setup response (server's Curve25519 public key + MFi Certificate)
    // Format: 32 bytes (server public key) + 4 bytes (cert length) + cert + 4 bytes (signature length) + signature
    fun parseAuthSetupResponse(responseBody: ByteArray): ByteArray {
        if (responseBody.size < 32) {
            throw IllegalArgumentException("Auth-setup response too short to contain public key")
        }
        // The first 32 bytes are the server's Curve25519 public key
        return responseBody.copyOfRange(0, 32)
    }
}
