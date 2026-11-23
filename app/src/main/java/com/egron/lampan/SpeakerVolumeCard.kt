package com.egron.lampan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun SpeakerVolumeCard(
    groupPercent: Int,
    levels: Map<String, Int>,
    revision: Int,
    acknowledgement: Long,
    names: Map<String, String>,
    onChange: (String?, Int) -> Long,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Volume", style = MaterialTheme.typography.titleMedium)
            if (levels.size == 1) {
                val address = levels.keys.first()
                VolumeControl(names[address] ?: address, levels.getValue(address), revision, acknowledgement) {
                    onChange(address, it)
                }
            } else {
                VolumeControl("All speakers", groupPercent, revision, acknowledgement) { onChange(null, it) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide individual speakers" else "Individual speakers")
                }
                if (expanded) {
                    levels.forEach { (address, level) ->
                        androidx.compose.runtime.key(address) {
                            VolumeControl(names[address] ?: address, level, revision, acknowledgement) { onChange(address, it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun VolumeControl(name: String, percent: Int, revision: Int, acknowledgement: Long, onCommit: (Int) -> Long) {
    var draft by remember { mutableStateOf(percent) }
    var dragging by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf(0L) }
    LaunchedEffect(percent, revision) {
        if (!dragging && acknowledgement >= pendingRequest) draft = percent
    }
    val latestDraft by rememberUpdatedState(draft)
    val latestPercent by rememberUpdatedState(percent)
    val latestCommit by rememberUpdatedState(onCommit)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sliderColors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant)
    fun commit(value: Int) {
        draft = value.coerceIn(0, 100)
        pendingRequest = onCommit(draft)
    }
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$draft%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { commit(draft - 2) },
                enabled = draft > 0,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Decrease $name volume" },
            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Box(Modifier.weight(1f)) {
                Slider(
                    value = draft.toFloat(),
                    onValueChange = { draft = (it / 2).roundToInt() * 2 },
                    onValueChangeFinished = { pendingRequest = onCommit(draft) },
                    valueRange = 0f..100f,
                    steps = 49,
                    colors = sliderColors,
                    track = { SliderDefaults.Track(it, colors = sliderColors,
                        drawStopIndicator = null, drawTick = { _, _ -> }) },
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "$name volume" },
                )
                // This touch layer handles thumb drags only. The standard Slider below
                // retains its keyboard and accessibility actions. Unclaimed vertical
                // gestures remain available to the surrounding scroll container.
                Box(Modifier.matchParentSize().pointerInput(rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val inset = 10.dp.toPx()
                        val width = (size.width - 2 * inset).coerceAtLeast(1f)
                        val fraction = latestDraft / 100f
                        val thumb = inset + width * (if (rtl) 1 - fraction else fraction)
                        if (abs(down.position.x - thumb) <= 24.dp.toPx()) {
                            var excess = 0f
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                excess = over
                            }
                            if (drag != null) {
                                dragging = true
                                val direction = if (rtl) -1 else 1
                                var raw = latestDraft + excess * direction / width * 100
                                draft = ((raw.coerceIn(0f, 100f) / 2).roundToInt() * 2)
                                val completed = horizontalDrag(drag.id) { change ->
                                    raw = (raw + change.positionChange().x * direction / width * 100)
                                        .coerceIn(0f, 100f)
                                    draft = (raw / 2).roundToInt() * 2
                                    change.consume()
                                }
                                dragging = false
                                if (completed) pendingRequest = latestCommit(draft)
                                else draft = latestPercent
                            }
                        }
                    }
                })
            }
            IconButton(
                onClick = { commit(draft + 2) },
                enabled = draft < 100,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Increase $name volume" },
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}
