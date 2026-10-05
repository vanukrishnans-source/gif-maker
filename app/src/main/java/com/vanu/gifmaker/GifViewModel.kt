package com.vanu.gifmaker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanu.gifmaker.engine.FragmentedMp4Fix
import com.vanu.gifmaker.engine.GifMaker
import com.vanu.gifmaker.engine.GifResult
import com.vanu.gifmaker.engine.GifSaver
import com.vanu.gifmaker.engine.GifSettings
import com.vanu.gifmaker.engine.Quality
import com.vanu.gifmaker.engine.VideoMeta
import com.vanu.gifmaker.engine.VideoProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class UiState(
    val uri: Uri? = null,
    val meta: VideoMeta? = null,
    val loading: Boolean = false,
    val settings: GifSettings = GifSettings(0, 0),
    val busy: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val result: GifResult? = null,
    val savedUri: Uri? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class GifViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var job: Job? = null
    var autoCreateAfterProbe = false
    var autoSaveAfterCreate = false
    /** Debug automation: range to apply after the probe (ms), or null. */
    var testRange: Pair<Long, Long>? = null

    fun openVideo(uri: Uri) {
        job?.cancel()
        _state.update { UiState(uri = uri, loading = true, settings = it.settings.copy(startMs = 0, endMs = 0)) }
        viewModelScope.launch {
            try {
                val (useUri, meta) = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val fixed = FragmentedMp4Fix.fixIfNeeded(app, uri)
                    val original = try { VideoProbe.probe(app, uri) } catch (e: Exception) { if (fixed == null) throw e; null }
                    val fm = fixed?.let { f -> try { VideoProbe.probe(app, Uri.fromFile(f)) } catch (_: Exception) { null } }
                    if (fixed != null && fm != null && (original == null || fm.durationMs > original.durationMs)) {
                        Uri.fromFile(fixed) to fm.copy(name = original?.name ?: fm.name)
                    } else {
                        fixed?.delete()
                        uri to (original ?: throw IllegalArgumentException("Can't read that video."))
                    }
                }
                _state.update {
                    val end = min(meta.durationMs, 5_000L)
                    it.copy(uri = useUri, meta = meta, loading = false, settings = it.settings.copy(startMs = 0, endMs = end))
                }
                testRange?.let { (a, b) -> testRange = null; _state.update { it.copy(settings = it.settings.copy(startMs = a, endMs = min(b, meta.durationMs))) } }
                if (autoCreateAfterProbe) { autoCreateAfterProbe = false; create() }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, uri = null, error = e.message ?: "Couldn't open that video.") }
            }
        }
    }

    // ------------------------------------------------------------------ trim
    private fun clampRange(start: Long, end: Long, moved: Int): Pair<Long, Long> {
        val dur = _state.value.meta?.durationMs ?: return start to end
        var s = start.coerceIn(0, dur)
        var e = end.coerceIn(0, dur)
        val minL = GifMaker.MIN_LENGTH_MS; val maxL = GifMaker.MAX_LENGTH_MS
        if (moved == 0) { // start moved: drag end along if needed
            if (e - s < minL) e = min(dur, s + minL)
            if (e - s > maxL) e = s + maxL
            if (e - s < minL) s = max(0, e - minL)
        } else {
            if (e - s < minL) s = max(0, e - minL)
            if (e - s > maxL) s = e - maxL
            if (e - s < minL) e = min(dur, s + minL)
        }
        return s to e
    }

    fun setRange(start: Long, end: Long) {
        val cur = _state.value.settings
        val moved = if (start != cur.startMs) 0 else 1
        val (s, e) = clampRange(start, end, moved)
        updateSettings { it.copy(startMs = s, endMs = e) }
    }

    fun nudgeStart(deltaMs: Long) {
        val cur = _state.value.settings
        val (s, e) = clampRange(cur.startMs + deltaMs, cur.endMs, 0)
        updateSettings { it.copy(startMs = s, endMs = e) }
    }

    fun nudgeEnd(deltaMs: Long) {
        val cur = _state.value.settings
        val (s, e) = clampRange(cur.startMs, cur.endMs + deltaMs, 1)
        updateSettings { it.copy(startMs = s, endMs = e) }
    }

    /** Set GIF length (from the current start); shifts start back if it would run past the end. */
    fun setLength(ms: Long) {
        val dur = _state.value.meta?.durationMs ?: return
        val l = ms.coerceIn(GifMaker.MIN_LENGTH_MS, min(GifMaker.MAX_LENGTH_MS, dur))
        val cur = _state.value.settings
        var s = cur.startMs
        if (s + l > dur) s = dur - l
        updateSettings { it.copy(startMs = s, endMs = s + l) }
    }

    fun setFps(v: Int) = updateSettings { it.copy(fps = v) }
    fun setMaxSide(v: Int) = updateSettings { it.copy(maxSide = v) }
    fun setSpeed(v: Float) = updateSettings { it.copy(speed = v) }
    fun setQuality(v: Quality) = updateSettings { it.copy(quality = v) }
    fun setLoop(v: Boolean) = updateSettings { it.copy(loop = v) }
    fun setDither(v: Boolean) = updateSettings { it.copy(dither = v) }

    private fun updateSettings(f: (GifSettings) -> GifSettings) {
        if (_state.value.busy) return
        _state.update { it.copy(settings = f(it.settings)) }
    }

    // ------------------------------------------------------------------ create
    fun create() {
        val st = _state.value
        val uri = st.uri ?: return
        val meta = st.meta ?: return
        if (st.busy) return
        val s = st.settings
        st.result?.file?.delete()
        _state.update { it.copy(busy = true, progressDone = 0, progressTotal = s.frameCount, result = null, savedUri = null, error = null, message = null) }
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    GifMaker.make(getApplication(), uri, meta, s) { done, total ->
                        _state.update { it.copy(progressDone = done, progressTotal = total) }
                    }
                }
                _state.update { it.copy(busy = false, result = res) }
                if (autoSaveAfterCreate) { autoSaveAfterCreate = false; save() }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false, message = "Cancelled.") }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(busy = false, error = "Out of memory — try a smaller size.") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "GIF creation failed.") }
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun save() {
        val res = _state.value.result ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { GifSaver.save(getApplication(), res.file) }
                _state.update { it.copy(saving = false, savedUri = uri, message = "Saved to Pictures/${GifSaver.ALBUM}") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "Save failed: ${e.message}") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }
}
