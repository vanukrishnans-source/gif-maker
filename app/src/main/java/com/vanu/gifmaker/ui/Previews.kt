package com.vanu.gifmaker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File

/** Muted MediaPlayer on a SurfaceView with exact (SEEK_CLOSEST) seeks, coalesced while dragging. */
class RangePlayer(context: Context, uri: Uri) : SurfaceHolder.Callback {
    private val mp = MediaPlayer()
    private var prepared = false
    private var seeking = false
    private var pendingSeek = -1L
    var failed = false
        private set

    init {
        try {
            mp.setDataSource(context, uri)
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { prepared = true; if (pendingSeek >= 0) { val t = pendingSeek; pendingSeek = -1; seek(t) } }
            mp.setOnSeekCompleteListener {
                seeking = false
                if (pendingSeek >= 0) { val t = pendingSeek; pendingSeek = -1; seek(t) }
            }
            mp.setOnErrorListener { _, _, _ -> failed = true; true }
            mp.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
    }

    val positionMs: Long get() = try { if (prepared) mp.currentPosition.toLong() else 0L } catch (_: Exception) { 0L }

    fun seek(ms: Long) {
        if (failed) return
        if (!prepared || seeking) { pendingSeek = ms; return }
        seeking = true
        try {
            if (Build.VERSION.SDK_INT >= 26) mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else mp.seekTo(ms.toInt())
        } catch (_: Exception) { seeking = false }
    }

    fun play() { if (prepared && !failed) try { mp.start() } catch (_: Exception) {} }
    fun pause() { try { if (prepared && mp.isPlaying) mp.pause() } catch (_: Exception) {} }
    fun release() { try { mp.release() } catch (_: Exception) {} }

    override fun surfaceCreated(holder: SurfaceHolder) { try { mp.setDisplay(holder) } catch (_: Exception) {} }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { pause(); try { mp.setDisplay(null) } catch (_: Exception) {} }
}

/** Exact-frame poster images for the trim handles (MediaMetadataRetriever, serialised). */
class ThumbLoader(context: Context, uri: Uri) {
    private val r = MediaMetadataRetriever()
    private var ok = false
    init { try { r.setDataSource(context, uri); ok = true } catch (_: Exception) {} }

    @Synchronized
    fun frameAt(ms: Long, maxSide: Int): Bitmap? {
        if (!ok) return null
        return try {
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide)
            else r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) { null }
    }

    @Synchronized
    fun release() { try { r.release() } catch (_: Exception) {}; ok = false }
}

@Composable
fun VideoRangePreview(uri: Uri, aspect: Float, startMs: Long, endMs: Long, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) { RangePlayer(context.applicationContext, uri) }
    DisposableEffect(player) { onDispose { player.release() } }
    val thumbs = remember(uri) { ThumbLoader(context.applicationContext, uri) }
    DisposableEffect(thumbs) { onDispose { thumbs.release() } }
    var poster by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var posterTarget by remember(uri) { mutableLongStateOf(startMs) }
    var playing by remember(uri) { mutableStateOf(false) }
    var lastStart by remember(uri) { mutableLongStateOf(-1L) }
    var lastEnd by remember(uri) { mutableLongStateOf(-1L) }
    var pos by remember(uri) { mutableLongStateOf(startMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) { playing = false; player.pause() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    if (!enabled && playing) playing = false
    LaunchedEffect(startMs, endMs, playing) {
        if (playing) {
            player.seek(startMs)
            player.play()
            while (isActive) {
                delay(40)
                pos = player.positionMs
                if (pos >= endMs || pos < startMs - 500) player.seek(startMs)
            }
        } else {
            player.pause()
            // Show whichever handle moved last (start frame / last frame of the range).
            when {
                startMs != lastStart && lastStart >= 0 && endMs == lastEnd -> { pos = startMs; posterTarget = startMs }
                endMs != lastEnd && lastEnd >= 0 && startMs == lastStart -> { pos = endMs; posterTarget = maxOf(startMs, endMs - 60) }
                else -> { pos = startMs; posterTarget = startMs }
            }
            lastStart = startMs; lastEnd = endMs
        }
    }
    LaunchedEffect(posterTarget) {
        delay(90) // debounce while dragging
        val bmp = withContext(Dispatchers.IO) { thumbs.frameAt(posterTarget, 720) }
        if (bmp != null) poster = bmp
    }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.4f, 3f))
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { it.holder.addCallback(player) } },
            modifier = Modifier.fillMaxSize(),
        )
        val p = poster
        if (!playing && p != null) {
            Image(p.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(Color.Black))
        }
        Text(
            fmtTime(pos), color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
        )
        FilledTonalIconButton(
            onClick = { playing = !playing },
            enabled = enabled,
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xCC000000), contentColor = Color.White),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play selection")
        }
    }
}

/** Animated preview of the finished GIF (ImageDecoder on Android 9+, first frame on 8.x). */
@Composable
fun GifPreview(file: File, aspect: Float, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true } },
        update = { iv ->
            if (iv.tag != file.absolutePath) {
                iv.tag = file.absolutePath
                try {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                        iv.setImageDrawable(d)
                        (d as? AnimatedImageDrawable)?.start()
                    } else {
                        iv.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                    }
                } catch (_: Exception) {
                    iv.setImageDrawable(null)
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.4f, 3f))
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111114)),
    )
}

fun fmtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val m = t / 60000
    val s = (t / 1000) % 60
    val d = (t % 1000) / 100
    return "%d:%02d.%d".format(m, s, d)
}
