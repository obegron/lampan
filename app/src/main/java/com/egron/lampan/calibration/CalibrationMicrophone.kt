package com.egron.lampan.calibration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Activity-owned continuous recording; samples never leave the phone. */
internal class CalibrationMicrophone(context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val ring = ShortArray(CalibrationSignal.RATE * 20)
    private var written = 0L
    private var timestampFrame = 0L
    private var timestampNanos = 0L
    private var failure: String? = null
    private var routeId: Int? = null
    private val recorder: AudioRecord

    init {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        val manager = context.getSystemService(AudioManager::class.java)
        val minimum = AudioRecord.getMinBufferSize(CalibrationSignal.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone sample rate is unavailable" }
        @Suppress("MissingPermission")
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(CalibrationSignal.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 4, CalibrationSignal.RATE * 2)).build()
        recorder = record
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not start" }
            val builtin = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            check(builtin != null && record.setPreferredDevice(builtin)) { "Built-in microphone is unavailable" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not record" }
        } catch (error: Exception) {
            record.release()
            throw error
        }
        scope.launch {
            val block = ShortArray(1_024)
            val stamp = AudioTimestamp()
            try {
                while (isActive) {
                    val size = record.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                    check(size > 0) { "Microphone recording stopped" }
                    val route = record.routedDevice
                    check(route?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { "Microphone route changed" }
                    synchronized(lock) {
                        check(routeId == null || routeId == route.id) { "Microphone route changed" }
                        routeId = route.id
                        repeat(size) { ring[((written + it) % ring.size).toInt()] = block[it] }
                        written += size
                        if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            if (timestampNanos != 0L) {
                                val predicted = timestampNanos + (stamp.framePosition - timestampFrame) * 1_000_000_000L / CalibrationSignal.RATE
                                check(kotlin.math.abs(stamp.nanoTime - predicted) < 10_000_000L) { "Microphone clock changed; restart calibration" }
                            }
                            timestampFrame = stamp.framePosition
                            timestampNanos = stamp.nanoTime
                        }
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) { failure = error.message ?: "Microphone failed" }
            }
        }
    }

    suspend fun awaitReady() = withTimeout(5_000) {
        while (true) {
            val ready = synchronized(lock) {
                check(failure == null) { failure.orEmpty() }
                timestampNanos != 0L
            }
            if (ready) break
            delay(50)
        }
    }

    suspend fun measure(expectedNanos: Long): Double {
        // +/- 600 ms catches residual device delays without confusing adjacent bursts.
        val windowStart = expectedNanos - 600_000_000L
        val length = CalibrationSignal.RATE * 12 / 10 + CalibrationSignal.LENGTH
        val samples = withTimeout(8_000) {
            while (true) {
                val snapshot = synchronized(lock) {
                    check(failure == null) { failure.orEmpty() }
                    check(timestampNanos != 0L) { "Microphone timestamps unavailable" }
                    val first = timestampFrame + (windowStart - timestampNanos) * CalibrationSignal.RATE / 1_000_000_000L
                    check(first >= maxOf(0L, written - ring.size)) { "Recording window expired; retry this speaker" }
                    if (first + length <= written) {
                        val startTime = timestampNanos + (first - timestampFrame) * 1_000_000_000L / CalibrationSignal.RATE
                        ShortArray(length) { ring[((first + it) % ring.size).toInt()] } to startTime
                    } else null
                }
                if (snapshot != null) return@withTimeout snapshot
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            error("No recording")
        }
        return withContext(Dispatchers.Default) {
            val offset = CalibrationSignal.detect(samples.first)
            (samples.second - expectedNanos) / 1_000_000.0 + offset * 1_000.0 / CalibrationSignal.RATE
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { recorder.stop() }
        // Release after the blocking read has returned.
        kotlinx.coroutines.runBlocking { scope.coroutineContext[Job]?.join() }
        recorder.release()
        synchronized(lock) { ring.fill(0) }
    }
}
