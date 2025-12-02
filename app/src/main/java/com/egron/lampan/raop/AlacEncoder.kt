package com.egron.lampan.raop

import java.io.ByteArrayOutputStream

class AlacEncoder {

    private class BitWriter(val output: ByteArrayOutputStream) {
        private var currentByte = 0
        private var bitPos = 0 // 0..7, current bit position in currentByte (from MSB)

        fun write(data: Int, numBits: Int) {
            var remainingBits = numBits
            // We assume 'data' is right-aligned (e.g. 3 bits = 00000111)
            
            while (remainingBits > 0) {
                // How many bits can we fit in the current byte?
                val spaceInByte = 8 - bitPos
                val bitsToWrite = minOf(remainingBits, spaceInByte)
                
                // We want the *top* 'bitsToWrite' bits from 'data's remaining bits.
                // If data has 'remainingBits' left, we shift right to get the MSBs.
                val shift = remainingBits - bitsToWrite
                val chunk = (data shr shift) and ((1 shl bitsToWrite) - 1)
                
                // Shift chunk into position in currentByte
                // MSB first: position is 'bitPos'.
                // e.g. bitPos=0, write 3 bits. chunk is 3 bits.
                // we want chunk << (8 - 3 - 0) = 5.
                // e.g. bitPos=5, write 3 bits. chunk is 3 bits.
                // we want chunk << (8 - 3 - 5) = 0.
                val shiftInByte = 8 - bitPos - bitsToWrite
                currentByte = currentByte or (chunk shl shiftInByte)
                
                bitPos += bitsToWrite
                remainingBits -= bitsToWrite
                
                if (bitPos == 8) {
                    output.write(currentByte)
                    currentByte = 0
                    bitPos = 0
                }
            }
        }
        
        fun flush() {
            if (bitPos > 0) {
                output.write(currentByte)
                currentByte = 0
                bitPos = 0
            }
        }
    }

    fun encode(pcm: ByteArray): ByteArray {
        if (pcm.isEmpty()) return pcm

        val out = ByteArrayOutputStream()
        val writer = BitWriter(out)
        
        // Header: 0x20 0x00 0x12 (24 bits)
        // 001 (Stereo) 0000 (Unk) 0 (Unk)
        // 00000000 (Unk)
        // 000 (Unk) 1 (HasSize) 00 (Unk) 1 (NoComp)
        
        writer.write(1, 3)  // Ch=1
        writer.write(0, 4)
        writer.write(0, 8)
        writer.write(0, 4)
        writer.write(1, 1)  // HasSize
        writer.write(0, 2)
        writer.write(1, 1)  // NoComp
        
        val nFrames = pcm.size / 4 // 352
        // PipeWire writes 352 as 32-bit integer?
        // Reference C: bit_writer(..., n_frames, 32).
        // And we saw 0x000002c0 (704) in the hex dump.
        // If nFrames=352, nFrames*2 = 704.
        // So it writes (nFrames * channels).
        val lenVal = nFrames
        writer.write(lenVal, 32) // 32 bits
        
        // PCM Data
        for (i in 0 until pcm.size step 2) {
            if (i + 1 < pcm.size) {
                // Swap LE -> BE
                val high = pcm[i+1].toInt() and 0xFF
                val low = pcm[i].toInt() and 0xFF
                writer.write(high, 8)
                writer.write(low, 8)
            }
        }
        
        // End Tag: 7 (111) over 3 bits
        writer.write(7, 3)
        
        writer.flush()
        return out.toByteArray()
    }
}