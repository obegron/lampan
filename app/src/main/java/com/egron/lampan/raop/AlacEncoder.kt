package com.egron.lampan.raop

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AlacEncoder {

    fun encode(pcm: ByteArray, numFrames: Int): ByteArray {
        // Calculate max output size
        // Header (approx 7 bytes) + PCM data + End Tag (1 byte)
        val maxLen = 20 + pcm.size 
        val out = ByteArray(maxLen)
        
        val writer = BitWriter(out)

        // 3 bits: channel=1 (stereo)
        writer.write(1, 3)
        // 4 bits: Unknown (0)
        writer.write(0, 4)
        // 8 bits: Unknown (0)
        writer.write(0, 8)
        // 4 bits: Unknown (0)
        writer.write(0, 4)
        // 1 bit: Hassize (1)
        writer.write(1, 1)
        // 2 bits: Unused (0)
        writer.write(0, 2)
        // 1 bit: Is-not-compressed (1)
        writer.write(1, 1)
        
        // 32 bits: Frame length
        writer.write((numFrames shr 24) and 0xFF, 8)
        writer.write((numFrames shr 16) and 0xFF, 8)
        writer.write((numFrames shr 8) and 0xFF, 8)
        writer.write(numFrames and 0xFF, 8)

        // PCM Data: 16-bit Stereo
        // Input PCM is Little Endian (Android/WAV standard)
        // ALAC expects Big Endian samples
        for (i in 0 until pcm.size step 2) {
             // pcm[i] is Low, pcm[i+1] is High
             val low = pcm[i].toInt() and 0xFF
             val high = if (i + 1 < pcm.size) pcm[i + 1].toInt() and 0xFF else 0
             
             // Write High then Low (Big Endian)
             writer.write(high, 8)
             writer.write(low, 8)
        }

        // 3 bits: End tag (7)
        writer.write(7, 3)
        
        // Return only the used bytes
        val usedBytes = writer.position()
        return out.copyOf(usedBytes)
    }

    private class BitWriter(val buffer: ByteArray) {
        var byteIndex = 0
        var bitIndex = 0 // 0..7

        fun write(data: Int, numBits: Int) {
            var bitsToWrite = numBits
            var currentData = data

            while (bitsToWrite > 0) {
                val bitsFreeInByte = 8 - bitIndex
                val bitsNow = minOf(bitsToWrite, bitsFreeInByte)
                
                // Shift data to align with the current bit position
                // We want the most significant bits of `currentData` (relevant to `bitsToWrite`)
                // to go into the buffer.
                // The C code does: `*(*p)++ |= (data >> -rb)` where rb is negative remaining bits.
                // Let's simplify: We write the *least significant* `numBits` of `data`? 
                // Wait, standard bitstreams usually write MSB first?
                // The C code `bit_writer`:
                // `int rb = 8 - *pos - len;`
                // if rb >= 0: `**p = (*pos ? **p : 0) | (data << rb);` -> This puts `data` (which is `len` bits) at `pos`. 
                // If `data` is 1 (001) and len is 3. pos is 0. rb = 5. `data << 5` is `00100000`.
                // So it puts the LSB of `data` at the *highest* available bit in the byte?
                // No, `data << rb`. 
                // Let's trace `channel=1` (1 bit, value 1? No, value 1, len 3). `001`.
                // rb = 5. `1 << 5` = `32` (00100000). 
                // So it writes the value into the byte from MSB to LSB.
                
                // My implementation needs to match this.
                
                // If we are writing `bitsNow` bits:
                // We need to take the `bitsNow` *least significant bits* of `currentData` if we process from right to left?
                // But the caller passes e.g. `(n_frames >> 24) & 0xff` (8 bits).
                
                // Let's stick to the C logic port directly.
                val rb = 8 - bitIndex - numBits
                if (rb >= 0) {
                    // Fits in current byte
                    // Mask data to numBits
                    val mask = (1 shl numBits) - 1
                    val d = data and mask
                    buffer[byteIndex] = (buffer[byteIndex].toInt() or (d shl rb)).toByte()
                    bitIndex += numBits
                    if (bitIndex == 8) {
                        byteIndex++
                        bitIndex = 0
                    }
                    return
                } else {
                    // Splits across bytes
                    // Write first part to current byte
                    // We need to fill the remaining `8 - bitIndex` bits.
                    val bitsFirst = 8 - bitIndex
                    val bitsSecond = numBits - bitsFirst
                    
                    // Top `bitsFirst` bits of `data` go to current byte
                    val d1 = (data shr bitsSecond) and ((1 shl bitsFirst) - 1)
                    buffer[byteIndex] = (buffer[byteIndex].toInt() or d1).toByte()
                    
                    byteIndex++
                    bitIndex = 0
                    
                    // Remaining `bitsSecond` bits go to next byte(s) - Recurse or loop
                    // But since we write max 8 bits at a time in calls usually, logic handles it.
                    // The loop handles it.
                    // data for next iteration is `data` (masked for the remaining bits)
                    // bitsToWrite becomes `bitsSecond`
                    // But wait, my loop structure is slightly different.
                    
                    // Let's restart the loop body logic to be cleaner
                    
                    // We want to write `bitsNow` bits.
                    // If splitting, we write into current byte first.
                    
                    // C logic port:
                    // int rb = 8 - *pos - len;
                    // if (rb >= 0) { ... } else {
                    //    *(*p)++ |= (data >> -rb);
                    //    **p = data << (8+rb);
                    //    *pos = -rb;
                    // }
                    
                    // Porting exactly:
                    val rb = 8 - bitIndex - numBits
                    if (rb >= 0) {
                        buffer[byteIndex] = (buffer[byteIndex].toInt() or (data shl rb)).toByte()
                        bitIndex += numBits
                        if (bitIndex == 8) {
                            byteIndex++
                            bitIndex = 0
                        }
                        return // Done
                    } else {
                         // rb is negative. -rb is the number of bits that overflow to next byte.
                         val overflow = -rb
                         // Fill current byte
                         buffer[byteIndex] = (buffer[byteIndex].toInt() or (data shr overflow)).toByte()
                         byteIndex++
                         // Start next byte
                         buffer[byteIndex] = (data shl (8 - overflow)).toByte()
                         bitIndex = overflow
                         return // Done (assuming numBits <= 8 + space, which is true for max 32 bits if we handle it carefully, but this port assumes data fits in 2 bytes max crossing?
                         // The C code assumes `data` fits in the types used.
                         // BUT, the C code `bit_writer` takes `uint8_t data`. It only writes up to 8 bits at a time?
                         // The C call `bit_writer(&bp, &bpos, (n_frames >> 24) & 0xff, 8);` calls it with 8 bits.
                         // So the C `bit_writer` handles max 8 bits or so?
                         // "data" is `uint8_t`. So yes.
                         // MY `write` method takes `Int`.
                         // If I call `write(numFrames, 32)`, the C logic above fails because `data` is shifted.
                         
                         // I should break down large writes into 8-bit chunks or use a proper stream writer.
                    }
                }
            }
        }
        
        // Helper to write > 8 bits
        // But wait, the C code breaks 32-bit size into 4 calls of 8 bits.
        // My Kotlin code `writer.write((numFrames shr 24) and 0xFF, 8)` does the same.
        // So I only need to support up to 8 bits per write call to match the C usage pattern.
        
        fun position(): Int {
            return if (bitIndex > 0) byteIndex + 1 else byteIndex
        }
    }
}
