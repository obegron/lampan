package com.egron.lampan.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class AudioCapture(
    private val mediaProjection: MediaProjection,
    private val onStatus: (String) -> Unit,
    private val onAudioData: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val bufferSize: Int
    private val isRecording = false

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
                
                onStatus("AudioCapture: Entering read loop...")
                
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val data = buffer.copyOfRange(0, read)
                        onAudioData(data)
                        packetsRead++
                        if (packetsRead == 1) {
                            onStatus("AudioCapture: First audio data captured ($read bytes)")
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
            onStatus("AudioCapture Failed: ${e.message}")
            throw e
        }
    }

    fun stop() {
        try {
            captureJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d("AudioCapture", "Stopped recording")
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error stopping", e)
        }
    }
}
