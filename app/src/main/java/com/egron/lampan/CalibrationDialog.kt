package com.egron.lampan

import android.content.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.egron.lampan.calibration.*
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun CalibrationDialog(
    receivers: List<GroupTimingReceiver>,
    onSaved: (GroupSyncProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val token = remember { UUID.randomUUID().toString() }
    var microphone by remember { mutableStateOf<CalibrationMicrophone?>(null) }
    var busy by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableStateOf(0) }
    var proposed by remember { mutableStateOf<GroupSyncProfile?>(null) }
    var verified by remember { mutableStateOf(false) }
    val measured = remember { mutableMapOf<String, Double>() }
    val verification = remember { mutableMapOf<String, Double>() }
    val dismiss by rememberUpdatedState(onDismiss)
    val receiver = receivers[index.coerceAtMost(receivers.lastIndex)]

    fun command(operation: String): Intent = Intent(context, AudioCaptureService::class.java)
        .setAction(CalibrationProtocol.COMMAND).putExtra("TOKEN", token).putExtra("OP", operation)

    DisposableEffect(token, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) dismiss()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.startService(command("end"))
            microphone?.close()
        }
    }
    LaunchedEffect(token) {
        try {
            calibrationRequest(context, command("begin").putStringArrayListExtra("ADDRESSES", ArrayList(receivers.map { it.address })))
            // Construct on the main thread so disposal cannot race an abandoned
            // withContext result and leave a live recorder behind.
            microphone = CalibrationMicrophone(context)
            microphone!!.awaitReady()
            ready = true
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            error = failure.message ?: "Could not start the microphone"
        } finally { busy = false }
    }
    LaunchedEffect(token) {
        while (true) {
            delay(10_000)
            context.startService(command("heartbeat"))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (verified) "Speakers calibrated" else if (proposed != null) "Verify speaker timing" else "Automatic speaker timing") },
        text = {
            Text(error ?: when {
                !ready -> "Preparing the microphone…"
                verified -> "Timing measurements agree. Save these settings to resume playback."
                busy -> "Listening to three quiet test sounds from ${receiver.name}. Keep the phone still."
                else -> "Hold the phone about 10–20 cm from ${receiver.name}, with the microphone uncovered. " +
                    "Use a similar distance for every speaker. ${index + 1} of ${receivers.size}. " +
                    if (proposed == null) "You'll hear only test sounds during calibration." else "This second pass checks the adjustment before saving."
            })
        },
        confirmButton = {
            TextButton(enabled = !busy && ready, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        if (verified) {
                            calibrationRequest(context, command("commit"))
                            onSaved(requireNotNull(proposed))
                            onDismiss()
                        } else {
                            val result = calibrationRequest(context, command("probe").putExtra("ADDRESS", receiver.address))
                            val times = requireNotNull(result.getLongArrayExtra("EXPECTED"))
                            val values = times.map { microphone!!.measure(it) }
                            val residual = CalibrationSignal.medianResidual(values)
                            val profile = proposed
                            if (profile == null) {
                                measured[receiver.key] = residual
                                if (index == receivers.lastIndex) {
                                    val proposal = calibratedProfile(measured)
                                    calibrationRequest(context, command("apply")
                                        .putStringArrayListExtra("ADDRESSES", ArrayList(receivers.map { it.address }))
                                        .putIntegerArrayListExtra("DELAYS", ArrayList(receivers.map { proposal.delaysMs.getValue(it.key) })))
                                    proposed = proposal
                                    index = 0
                                } else index++
                            } else {
                                check(abs(residual - measured.getValue(receiver.key)) <= 15.0) {
                                    "Timing changed since the first pass. Cancel and restart calibration"
                                }
                                verification[receiver.key] = residual + profile.delaysMs.getValue(receiver.key)
                                if (index == receivers.lastIndex) {
                                    check(verification.values.max() - verification.values.min() <= 15.0) {
                                        "Speakers did not follow the correction consistently. Cancel and restart calibration"
                                    }
                                    verified = true
                                } else index++
                            }
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                        error = failure.message ?: "Measurement failed; please try again"
                    } finally { busy = false }
                }
            }) { Text(if (verified) "Save timing" else "I'm near this speaker") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun calibratedProfile(residuals: Map<String, Double>): GroupSyncProfile {
    require(residuals.size > 1 && residuals.values.all(Double::isFinite)) { "Measure every speaker" }
    val reference = residuals.maxBy { it.value }
    val delays = residuals.mapValues { (_, residual) -> (reference.value - residual).roundToInt() }
    require(delays.values.all { it in 0..MAX_RECEIVER_DELAY_MS }) { "Required correction exceeds 500 ms; check the receiver configuration" }
    return GroupSyncProfile(reference.key, delays)
}

private suspend fun calibrationRequest(context: Context, intent: Intent): Intent = withTimeout(5_000) {
    suspendCancellableCoroutine { continuation ->
        val request = UUID.randomUUID().toString()
        intent.putExtra("REQUEST", request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, response: Intent) {
                if (response.getStringExtra("REQUEST") != request || response.getStringExtra("TOKEN") != intent.getStringExtra("TOKEN")) return
                context.unregisterReceiver(this)
                if (continuation.isActive) continuation.resume(response)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(CalibrationProtocol.RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        try { context.startService(intent) } catch (failure: Exception) {
            runCatching { context.unregisterReceiver(receiver) }
            continuation.resumeWith(Result.failure(failure))
        }
    }.also { response -> check(!response.hasExtra("ERROR")) { response.getStringExtra("ERROR").orEmpty() } }
}
