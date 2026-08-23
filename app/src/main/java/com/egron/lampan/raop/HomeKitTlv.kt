package com.egron.lampan.raop

import java.io.ByteArrayOutputStream

/** HomeKit TLV8 field identifiers used by AirPlay 2 pairing. */
object HomeKitTlvType {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val FLAGS = 0x13
}

/**
 * Minimal TLV8 codec for HomeKit pairing.
 *
 * Values longer than 255 bytes are emitted as consecutive fields and joined
 * again while decoding. This is required for the 384-byte SRP public keys used
 * by AirPlay 2 receivers.
 */
object HomeKitTlv {
    fun encode(vararg fields: Pair<Int, ByteArray>): ByteArray = encode(fields.asList())

    fun encode(fields: List<Pair<Int, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        for ((type, value) in fields) {
            require(type in 0..0xFF) { "TLV8 type must fit in one byte" }
            if (value.isEmpty()) {
                output.write(type)
                output.write(0)
                continue
            }

            var offset = 0
            while (offset < value.size) {
                val length = minOf(0xFF, value.size - offset)
                output.write(type)
                output.write(length)
                output.write(value, offset, length)
                offset += length
            }
        }
        return output.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val fields = linkedMapOf<Int, ByteArrayOutputStream>()
        var offset = 0
        while (offset < data.size) {
            require(data.size - offset >= 2) { "Truncated TLV8 header at byte $offset" }
            val type = data[offset].toInt() and 0xFF
            val length = data[offset + 1].toInt() and 0xFF
            offset += 2
            require(data.size - offset >= length) {
                "Truncated TLV8 value for type $type at byte $offset"
            }
            fields.getOrPut(type) { ByteArrayOutputStream() }
                .write(data, offset, length)
            offset += length
        }
        return fields.mapValues { it.value.toByteArray() }
    }
}
