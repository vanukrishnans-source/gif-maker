package com.vanu.gifmaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanu.gifmaker.GifViewModel
import com.vanu.gifmaker.engine.GifMaker
import com.vanu.gifmaker.engine.Quality
import java.util.Locale
import kotlin.math.min

private val FPS_OPTIONS = listOf(5, 8, 10, 12, 15, 20, 25)
private val SIZE_OPTIONS = listOf(240, 320, 480, 640, 800)
private val SPEED_OPTIONS = listOf(0.5f, 1f, 1.5f, 2f)
private val LENGTH_OPTIONS = listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L, 15_000L)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    vm: GifViewModel,
    onPick: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
) {
    val st by vm.state.collectAsState()
    val meta = st.meta
    val s = st.settings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Gif, null, tint = Pink, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("GIF Maker", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Video → GIF · offline", fontSize = 12.sp, color = TextSecondary)
            }
            if (meta != null) {
                OutlinedButton(onClick = onPick, enabled = !st.busy) {
                    Icon(Icons.Filled.VideoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Change")
                }
            }
        }

        (st.error ?: st.message)?.let { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = if (st.error != null) Danger.copy(alpha = 0.18f) else Teal.copy(alpha = 0.16f))) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 14.sp)
                    TextButton(onClick = vm::clearMessage) { Text("OK") }
                }
            }
        }

        if (meta == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (st.loading) {
                        CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Reading video…")
                    } else {
                        Icon(Icons.Filled.VideoLibrary, null, tint = Pink, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Turn any part of a video into an animated GIF.", fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Pick a video, choose the start and end (or a length), set FPS and size, then Create GIF. " +
                                "Everything happens on your phone.",
                            fontSize = 13.sp, color = TextSecondary,
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Filled.VideoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Pick a video", fontSize = 16.sp)
                        }
                    }
                }
            }
            return@Column
        }

        val uri = st.uri!!
        // ---------------------------------------------------------------- preview
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                VideoRangePreview(uri, meta.displayWidth.toFloat() / meta.displayHeight, s.startMs, s.endMs, enabled = !st.busy)
                Spacer(Modifier.height(8.dp))
                Text(meta.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    "${meta.displayWidth}×${meta.displayHeight} · ${fmtTime(meta.durationMs)} · ${"%.0f".format(meta.fps)} fps",
                    fontSize = 12.sp, color = TextSecondary,
                )
            }
        }

        // ---------------------------------------------------------------- trim
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("GIF length", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                RangeSlider(
                    value = s.startMs.toFloat()..s.endMs.toFloat(),
                    onValueChange = { r -> vm.setRange(r.start.toLong(), r.endInclusive.toLong()) },
                    valueRange = 0f..meta.durationMs.toFloat(),
                    enabled = !st.busy,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TimeNudge("Start", s.startMs, !st.busy, { vm.nudgeStart(-100) }, { vm.nudgeStart(100) })
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(secs(s.lengthMs), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pink)
                        Text("selected", fontSize = 11.sp, color = TextSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    TimeNudge("End", s.endMs, !st.busy, { vm.nudgeEnd(-100) }, { vm.nudgeEnd(100) })
                }
                Spacer(Modifier.height(8.dp))
                Text("Quick length from start", fontSize = 12.sp, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (l in LENGTH_OPTIONS) {
                        if (l > meta.durationMs) continue
                        FilterChip(selected = s.lengthMs == l, onClick = { vm.setLength(l) }, enabled = !st.busy, label = { Text("${l / 1000}s") })
                    }
                    val whole = min(meta.durationMs, GifMaker.MAX_LENGTH_MS)
                    FilterChip(
                        selected = s.startMs == 0L && s.lengthMs == whole, onClick = { vm.setRange(0, whole); vm.setLength(whole) },
                        enabled = !st.busy,
                        label = { Text(if (meta.durationMs <= GifMaker.MAX_LENGTH_MS) "Whole video" else "Max 30s") },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- options
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                val (ow, oh) = GifMaker.outputSize(meta, s.maxSide)
                Text("Options", fontWeight = FontWeight.SemiBold)
                OptionLabel("Frame rate", "${s.fps} fps · ${s.frameCount} frames")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in FPS_OPTIONS) FilterChip(selected = s.fps == f, onClick = { vm.setFps(f) }, enabled = !st.busy, label = { Text("$f") })
                }
                OptionLabel("Size (longest side)", "${ow}×${oh}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (z in SIZE_OPTIONS) FilterChip(selected = s.maxSide == z, onClick = { vm.setMaxSide(z) }, enabled = !st.busy, label = { Text("$z") })
                }
                OptionLabel("Playback speed", "GIF plays ${secs(s.gifLengthMs.toLong())}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sp in SPEED_OPTIONS) FilterChip(
                        selected = s.speed == sp, onClick = { vm.setSpeed(sp) }, enabled = !st.busy,
                        label = { Text((if (sp % 1f == 0f) "%.0f×" else "%.1f×").format(sp)) },
                    )
                }
                OptionLabel("Quality", null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (q in Quality.entries) FilterChip(selected = s.quality == q, onClick = { vm.setQuality(q) }, enabled = !st.busy, label = { Text(q.label) })
                }
                SwitchRow("Loop forever", s.loop, !st.busy, vm::setLoop)
                SwitchRow("Dithering (smoother gradients)", s.dither, !st.busy, vm::setDither)
            }
        }

        // ---------------------------------------------------------------- create
        if (st.busy) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Creating GIF… ${st.progressDone} / ${st.progressTotal} frames", Modifier.weight(1f))
                        TextButton(onClick = vm::cancel) { Icon(Icons.Filled.Close, null); Text("Cancel") }
                    }
                    LinearProgressIndicator(
                        progress = { if (st.progressTotal > 0) st.progressDone.toFloat() / st.progressTotal else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Button(
                onClick = vm::create,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
            ) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                Text(if (st.result == null) "Create GIF" else "Create again", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---------------------------------------------------------------- result
        st.result?.let { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardElevated)) {
                Column(Modifier.padding(12.dp)) {
                    GifPreview(r.file, r.width.toFloat() / r.height)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${r.width}×${r.height} · ${r.frames} frames · ${secs(r.durationMs.toLong())} · ${sizeStr(r.bytes)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Made in ${secs(r.elapsedMs)}${if (r.usedFallback) " (compatibility decoder)" else ""}",
                        fontSize = 12.sp, color = TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSave, enabled = st.savedUri == null && !st.saving, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (st.savedUri != null) "Saved" else if (st.saving) "Saving…" else "Save")
                        }
                        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
                        }
                        if (st.savedUri != null) {
                            OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open")
                            }
                        }
                    }
                    if (st.savedUri != null) {
                        Text("In Gallery → Pictures/GifMaker", fontSize = 12.sp, color = Teal, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TimeNudge(label: String, ms: Long, enabled: Boolean, minus: () -> Unit, plus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = minus, enabled = enabled, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("−", fontSize = 18.sp) }
            Text(fmtTime(ms), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = plus, enabled = enabled, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("+", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun OptionLabel(title: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
        Text(title, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun secs(ms: Long): String = String.format(Locale.US, "%.1fs", ms / 1000.0)

private fun sizeStr(b: Long): String =
    if (b >= 1024 * 1024) String.format(Locale.US, "%.1f MB", b / 1048576.0) else String.format(Locale.US, "%d KB", b / 1024)
