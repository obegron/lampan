package com.egron.lampan.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class AudioCapture(
    private val mediaProjection: MediaProjection,
    private val onStatus: (String) -> Unit,
    private val onAudioData: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val bufferSize: Int

    init {
        // Standard AirPlay format: 44100Hz, Stereo, 16-bit
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
    }

    @SuppressLint("MissingPermission") // Permission is checked in Service/Activity
    fun start() {
        if (captureJob?.isActive == true) return

        try {
            onStatus("AudioCapture: Configuring...")
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    onStatus("AudioCapture: MediaProjection stopped")
                    stopAudioRecord()
                }
            }
            mediaProjection.registerCallback(callback, callbackHandler)
            mediaProjectionCallback = callback

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord failed to start recording")
            }
            
            onStatus("AudioCapture: Recording started (State: Initialized, Recording)")
            Log.d("AudioCapture", "Started recording")

            captureJob = scope.launch {
                val buffer = ByteArray(352 * 4) 
                var packetsRead = 0
                var capturedSignal = false
                var silenceWarningLogged = false
                
                onStatus("AudioCapture: Entering read loop...")
                
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val data = buffer.copyOfRange(0, read)
                        val peak = pcm16LittleEndianPeak(data)
                        onAudioData(data)
                        packetsRead++
                        if (packetsRead == 1) {
                            onStatus(
                                "AudioCapture: First audio data captured " +
                                    "($read bytes, PCM peak=$peak)",
                            )
                        }
                        if (!capturedSignal && peak > SILENCE_PEAK_THRESHOLD) {
                            capturedSignal = true
                            onStatus(
                                "AudioCapture: Non-silent PCM confirmed " +
                                    "(packet $packetsRead, peak=$peak)",
                            )
                        } else if (
                            !capturedSignal &&
                            !silenceWarningLogged &&
                            packetsRead >= SILENCE_WARNING_PACKETS
                        ) {
                            silenceWarningLogged = true
                            onStatus(
                                "AudioCapture: Captured PCM is still silent; check the " +
                                    "app or screen selected in Android's sharing prompt",
                            )
                        }
                        if (packetsRead % 100 == 0) {
                             Log.d("AudioCapture", "Captured $packetsRead frames")
                             // Optional: onStatus("AudioCapture: Alive ($packetsRead frames)") 
                             // (Don't spam UI too much, maybe every 500?)
                        }
                    } else {
                         if (read < 0) {
                             onStatus("AudioCapture Error: read returned $read")
                         }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error starting capture", e)
            stopAudioRecord()
            unregisterMediaProjectionCallback()
            onStatus("AudioCapture Failed: ${e.message}")
            throw e
        }
    }

    fun stop() {
        stopAudioRecord()
        unregisterMediaProjectionCallback()
        try {
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error stopping MediaProjection", e)
        }
    }

    private fun stopAudioRecord() {
        captureJob?.cancel()
        captureJob = null

        val record = audioRecord ?: return
        audioRecord = null

        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                record.stop()
            } catch (e: Exception) {
                Log.e("AudioCapture", "Error stopping AudioRecord", e)
            }
        }

        try {
            record.release()
            Log.d("AudioCapture", "Stopped recording")
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error releasing AudioRecord", e)
        }
    }

    private fun unregisterMediaProjectionCallback() {
        val callback = mediaProjectionCallback ?: return
        try {
            mediaProjection.unregisterCallback(callback)
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error unregistering MediaProjection callback", e)
        } finally {
            mediaProjectionCallback = null
        }
    }

    private companion object {
        const val SILENCE_PEAK_THRESHOLD = 8
        const val SILENCE_WARNING_PACKETS = 125
    }
}

internal fun pcm16LittleEndianPeak(data: ByteArray): Int {
    var peak = 0
    var offset = 0
    while (offset + 1 < data.size) {
        val sample = (
            (data[offset].toInt() and 0xFF) or
                (data[offset + 1].toInt() shl 8)
            ).toShort().toInt()
        peak = maxOf(peak, abs(sample))
        offset += 2
    }
    return peak
}
