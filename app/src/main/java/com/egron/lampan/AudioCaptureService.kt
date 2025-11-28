package com.egron.lampan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.egron.lampan.R
import com.egron.lampan.audio.AudioCapture
import com.egron.lampan.raop.RaopSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AudioCaptureService : Service() {

    private var audioCapture: AudioCapture? = null
    private var raopSession: RaopSession? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                val resultCode = intent.getIntExtra("RESULT_CODE", 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("DATA", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("DATA")
                }
                val host = intent.getStringExtra("HOST") ?: "192.168.1.1"
                val port = intent.getIntExtra("PORT", 7000)
                
                if (resultCode != 0 && data != null) {
                    startForegroundService()
                    startCapture(resultCode, data, host, port)
                }
            }
            "STOP" -> {
                stopCapture()
                stopSelf()
            }
            "SET_VOLUME" -> {
                val vol = intent.getFloatExtra("VOLUME", 1.0f)
                raopSession?.setVolume(vol)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = "AudioCaptureChannel"
        val channel = NotificationChannel(
            channelId,
            "Audio Capture Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Lampan")
            .setContentText("Streaming Audio...")
            .setSmallIcon(android.R.drawable.ic_media_play) // Using system drawable for now
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent, host: String, port: Int) {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mpManager.getMediaProjection(resultCode, data) ?: throw Exception("MediaProjection denied or null")

            sendStatusBroadcast("Connecting to $host:$port...")
            
            raopSession = RaopSession(host, port) { msg ->
                sendStatusBroadcast(msg)
            }
            
            audioCapture = AudioCapture(mediaProjection, { status -> 
                sendStatusBroadcast(status)
            }) { pcmData ->
                // This runs on IO thread from AudioCapture
                raopSession?.sendFrame(pcmData)
            }
            
            scope.launch {
                try {
                    raopSession?.connect()
                    sendStatusBroadcast("Connected. Starting capture...")
                    audioCapture?.start()
                } catch (e: Exception) {
                    e.printStackTrace()
                    sendErrorBroadcast("Connection failed: ${e.message}")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendErrorBroadcast("Failed to start capture: ${e.message}")
            stopSelf()
        }
    }

    private fun sendStatusBroadcast(msg: String) {
        val intent = Intent("com.egron.lampan.STATUS")
        intent.putExtra("STATUS_MSG", msg)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(msg: String) {
        val intent = Intent("com.egron.lampan.ERROR")
        intent.putExtra("ERROR_MSG", msg)
        intent.setPackage(packageName) // Explicit intent for security
        sendBroadcast(intent)
    }

    private fun stopCapture() {
        audioCapture?.stop()
        scope.launch {
            raopSession?.stop()
        }
    }
}
