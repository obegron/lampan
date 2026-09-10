package com.egron.lampan.calibration

import kotlin.math.*

/** Quiet, windowed chirp; identical PCM is used by the sender and detector. */
internal object CalibrationSignal {
    const val RATE = 44_100
    const val SPACING = 66_150
    const val LENGTH = 3_528
    val template = ShortArray(LENGTH) { i ->
        val t = i.toDouble() / RATE
        val duration = LENGTH.toDouble() / RATE
        val window = sin(PI * i / LENGTH).pow(2)
        (1_800 * window * sin(2 * PI * (700 * t + 1_800 * t * t / (2 * duration)))).toInt().toShort()
    }

    fun pcm(offset: Long, frames: Int): ByteArray = ByteArray(frames * 4).also { bytes ->
        repeat(frames) { i ->
            val position = offset + i
            val burst = position / SPACING
            val index = position % SPACING
            val value = if (position >= 0 && burst in 0..2 && index < LENGTH) template[index.toInt()].toInt() else 0
            bytes[i * 4] = value.toByte()
            bytes[i * 4 + 1] = (value shr 8).toByte()
            bytes[i * 4 + 2] = value.toByte()
            bytes[i * 4 + 3] = (value shr 8).toByte()
        }
    }

    /** Normalized correlation, coarse search then sample-level refinement. */
    fun detect(samples: ShortArray): Int {
        require(samples.size > LENGTH) { "Recording is too short" }
        require(samples.count { abs(it.toInt()) > 32_000 } < samples.size / 100) { "Recording clipped; move the phone slightly farther away" }
        fun score(offset: Int): Double {
            var dot = 0.0
            var energy = 0.0
            var templateEnergy = 0.0
            var i = 0
            while (i < LENGTH) {
                val a = samples[offset + i].toDouble()
                val b = template[i].toDouble()
                dot += a * b
                energy += a * a
                templateEnergy += b * b
                i += 6
            }
            return if (energy < 1.0) 0.0 else abs(dot) / sqrt(energy * templateEnergy)
        }
        var best = 0
        var peak = 0.0
        for (offset in 0..samples.size - LENGTH step 6) {
            val value = score(offset)
            if (value > peak) { peak = value; best = offset }
        }
        val coarse = best
        for (offset in max(0, coarse - 6)..min(samples.size - LENGTH, coarse + 6)) {
            val value = score(offset)
            if (value > peak) { peak = value; best = offset }
        }
        require(peak >= 0.45) { "Could not hear the test clearly. Move closer and try again" }
        val second = (0..samples.size - LENGTH step 6)
            .filter { abs(it - best) > RATE / 50 }.maxByOrNull(::score)
        val secondPeak = second?.let { candidate ->
            (max(0, candidate - 6)..min(samples.size - LENGTH, candidate + 6)).maxOf(::score)
        } ?: 0.0
        require(secondPeak < peak * 0.8) { "Ambiguous sound or echoes. Move closer and try again" }
        return best
    }

    fun medianResidual(values: List<Double>): Double {
        require(values.size == 3 && values.all(Double::isFinite)) { "Three measurements are required" }
        val sorted = values.sorted()
        require(sorted.last() - sorted.first() <= 10.0) { "Timing changed between sounds. Please try again" }
        return sorted[1]
    }
}
