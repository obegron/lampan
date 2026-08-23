package com.egron.lampan

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun isNowPlayingAccessEnabled(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** Watches Android's highest-priority playing media session. */
internal class NowPlayingMonitor(
    context: Context,
    private val onUpdate: (NowPlayingInfo?) -> Unit,
    private val onStatus: (String) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(
        appContext,
        NowPlayingNotificationListener::class.java,
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
    )
    private var controllers = emptyList<MediaController>()
    private var ticker: Job? = null
    private var lastArtworkKey: String? = null
    private var lastArtwork: ByteArray? = null
    private var lastPublishedIdentity: String? = null
    @Volatile private var closed = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = schedulePublish()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { active ->
            replaceControllers(active.orEmpty())
        }

    fun start() {
        if (!isNowPlayingAccessEnabled(appContext)) {
            onStatus("Now-playing information is disabled")
            onUpdate(null)
            return
        }
        mainHandler.post {
            if (closed) return@post
            try {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    listenerComponent,
                    mainHandler,
                )
                replaceControllers(
                    mediaSessionManager.getActiveSessions(listenerComponent),
                )
                ticker = scope.launch {
                    while (isActive) {
                        delay(PROGRESS_REFRESH_MS)
                        publishCurrent()
                    }
                }
                onStatus("Now-playing information enabled")
            } catch (error: SecurityException) {
                onStatus("Enable Notification Access to share now-playing information")
                onUpdate(null)
            }
        }
    }

    private fun refreshSessions() {
        mainHandler.post {
            try {
                replaceControllers(
                    mediaSessionManager.getActiveSessions(listenerComponent),
                )
            } catch (_: SecurityException) {
                replaceControllers(emptyList())
            }
        }
    }

    private fun replaceControllers(active: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = active.filterNot { it.packageName == appContext.packageName }
        controllers.forEach { it.registerCallback(controllerCallback, mainHandler) }
        schedulePublish()
    }

    private fun schedulePublish() {
        scope.launch { publishCurrent() }
    }

    private fun publishCurrent() {
        val controller = selectController(controllers)
        if (controller == null) {
            if (lastPublishedIdentity != null) {
                lastPublishedIdentity = null
                lastArtworkKey = null
                lastArtwork = null
                onUpdate(null)
            }
            return
        }
        val metadata = controller.metadata
        val title = metadata?.firstText(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        ) ?: applicationLabel(controller.packageName)
        val artist = metadata?.firstText(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
        ).orEmpty()
        val album = metadata?.firstText(
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        ).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0 }
        val state = controller.playbackState
        val position = state?.currentPositionMs()
        val playing = state?.state in PLAYING_STATES
        val artworkKey = artworkKey(controller.packageName, title, album, metadata)
        val artwork = if (artworkKey == lastArtworkKey) {
            lastArtwork
        } else {
            loadArtwork(metadata).also {
                lastArtworkKey = artworkKey
                lastArtwork = it
            }
        }
        val identity = "${controller.packageName}\u0000$title\u0000$artist\u0000$album"
        if (identity != lastPublishedIdentity) {
            lastPublishedIdentity = identity
            onStatus(
                buildString {
                    append("Now playing: ")
                    append(title)
                    if (artist.isNotEmpty()) append(" — $artist")
                },
            )
        }
        onUpdate(
            NowPlayingInfo(
                sourcePackage = controller.packageName,
                title = title,
                artist = artist,
                album = album,
                positionMs = position,
                durationMs = duration,
                isPlaying = playing,
                artworkJpeg = artwork,
            ),
        )
    }

    private fun selectController(active: List<MediaController>): MediaController? =
        active.firstOrNull { it.playbackState?.state in PLAYING_STATES }
            ?: active.firstOrNull { it.metadata != null }

    private fun applicationLabel(packageName: String): String =
        runCatching {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

    private fun artworkKey(
        packageName: String,
        title: String,
        album: String,
        metadata: MediaMetadata?,
    ): String {
        val bitmap = metadata?.firstBitmap()
        val uri = metadata?.firstText(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
        return "$packageName:$title:$album:${bitmap?.generationId}:${uri.orEmpty()}"
    }

    private fun loadArtwork(metadata: MediaMetadata?): ByteArray? {
        metadata?.firstBitmap()?.let { return it.toAirPlayJpeg() }
        val bitmap = metadata?.firstText(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )?.let { uri ->
            runCatching {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        }
        return try {
            bitmap?.toAirPlayJpeg()
        } finally {
            bitmap?.recycle()
        }
    }

    override fun close() {
        closed = true
        ticker?.cancel()
        ticker = null
        mainHandler.post {
            controllers.forEach { it.unregisterCallback(controllerCallback) }
            controllers = emptyList()
            runCatching {
                mediaSessionManager.removeOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                )
            }
        }
        scope.cancel()
    }

    private fun MediaMetadata.firstText(vararg keys: String): String? =
        keys.asSequence()
            .mapNotNull { getText(it)?.toString()?.trim() }
            .firstOrNull(String::isNotEmpty)

    @Suppress("DEPRECATION")
    private fun MediaMetadata.firstBitmap(): Bitmap? =
        sequenceOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).mapNotNull(::getBitmap).firstOrNull()

    private fun PlaybackState.currentPositionMs(): Long {
        if (position < 0) return 0L
        if (state !in PLAYING_STATES || lastPositionUpdateTime <= 0L) return position
        val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
        return (position + elapsed * playbackSpeed).toLong().coerceAtLeast(0L)
    }

    private fun Bitmap.toAirPlayJpeg(): ByteArray? {
        val largest = maxOf(width, height)
        val outputBitmap = if (largest > ARTWORK_MAX_DIMENSION) {
            val scale = ARTWORK_MAX_DIMENSION.toFloat() / largest
            Bitmap.createScaledBitmap(
                this,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            this
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (outputBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    output.toByteArray().takeIf { it.size <= ARTWORK_MAX_BYTES }
                } else {
                    null
                }
            }
        } finally {
            if (outputBitmap !== this) outputBitmap.recycle()
        }
    }

    private companion object {
        val PLAYING_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
        )
        const val PROGRESS_REFRESH_MS = 5_000L
        const val ARTWORK_MAX_DIMENSION = 600
        const val ARTWORK_MAX_BYTES = 1_048_576
        const val JPEG_QUALITY = 88
    }
}
