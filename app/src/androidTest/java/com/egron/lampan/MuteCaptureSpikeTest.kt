package com.egron.lampan

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Pandroid.testInstrumentationRunnerArguments.lampanMuteSpike=true. */
class MuteCaptureSpikeTest {
    @Test fun quantifyPlaybackCaptureWhilePhoneMediaIsMuted() {
        assumeTrue("Explicit opt-in required for the audible audio capture experiment",
            InstrumentationRegistry.getArguments().getString("lampanMuteSpike") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val originalIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val originalMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO",
        ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        MuteCaptureSpikeResult.reset()
        context.startActivity(Intent(context, MuteCaptureSpikeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val deadline = SystemClock.elapsedRealtime() + 35_000
            while (!MuteCaptureSpikeResult.complete && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.uiAutomation.rootInActiveWindow?.let(::approveProjection)
                SystemClock.sleep(100)
            }
            assertTrue("Experiment did not complete", MuteCaptureSpikeResult.complete)
            MuteCaptureSpikeResult.failure?.let { throw AssertionError("Experiment failed", it) }
            val windows = requireNotNull(MuteCaptureSpikeResult.windows)
            assertEquals(5, windows.size)
            windows.forEach { window ->
                assertTrue("Capture became silent: $window", window.rms > 100 && window.nonzero > 1000)
                val ratio = window.rms / windows.first().rms
                assertTrue("Capture amplitude changed: $window, ratio=$ratio", ratio in 0.9..1.1)
            }
            assertEquals(0, windows[1].phoneIndex)
            assertTrue("ADJUST_MUTE did not mute the media stream", windows[3].phoneMuted)
            assertEquals(originalIndex, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(originalMuted, audio.isStreamMute(AudioManager.STREAM_MUSIC))
        } finally {
            context.stopService(Intent(context, MuteCaptureSpikeService::class.java))
        }
    }

    private fun approveProjection(node: AccessibilityNodeInfo) {
        // Android's standard MediaProjection confirmation button; never accept other prompts.
        val text = node.text?.toString().orEmpty()
        if (node.isClickable && node.viewIdResourceName == "android:id/button1" &&
            text.equals("Start now", ignoreCase = true)) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let(::approveProjection)
    }
}
