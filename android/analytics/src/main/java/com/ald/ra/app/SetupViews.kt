package com.ald.ra.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/** `file:<name>` videos live in the site pack or in clips/ (shared by all sites); null for a stream URL. */
fun videoFile(ctx: Context, siteDir: File, url: String): File? = if (!url.startsWith("file:")) null else url.removePrefix("file:").let { n ->
    listOf(File(siteDir, n), File(ctx.getExternalFilesDir("clips"), n)).firstOrNull { it.exists() } ?: error("video $n not found in the site pack or clips/")
}

/** A camera's live picture for setup: plays the stream and freezes a frame at full stream resolution. */
@OptIn(UnstableApi::class)
class LiveView(ctx: Context) : FrameLayout(ctx) {
    private val tv = TextureView(ctx)
    private val msg = TextView(ctx).apply { setTextColor(Color.WHITE); setShadowLayer(3f, 1f, 1f, Color.BLACK); setPadding(8, 8, 8, 8) }
    private var player: ExoPlayer? = null
    var url: String? = null; private set
    var videoW = 0; private set
    var videoH = 0; private set

    init {
        setBackgroundColor(Color.BLACK)
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(msg, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    fun play(url: String, file: File?) {
        if (url == this.url && player != null) return
        stop()
        this.url = url
        msg.text = "connecting to ${redact(url)}"
        val p = ExoPlayer.Builder(context).build()
        p.volume = 0f
        p.setVideoTextureView(tv)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width <= 0) return
                videoW = v.width; videoH = v.height; fit()
            }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) msg.text = "${redact(url)}  ${videoW}×$videoH"
            }
            override fun onPlayerError(e: PlaybackException) { msg.text = "can't play ${redact(url)}: ${e.errorCodeName}\n${e.message}" }
        })
        p.setMediaSource(if (file == null) RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000).createMediaSource(MediaItem.fromUri(url))
            else ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(MediaItem.fromUri(Uri.fromFile(file))))
        if (file != null) p.repeatMode = Player.REPEAT_MODE_ALL
        p.playWhenReady = true
        p.prepare()
        player = p
    }

    /** The current frame at the stream's own resolution, or null before the first frame. */
    fun grab(): Bitmap? = if (videoW > 0 && tv.isAvailable) tv.getBitmap(videoW, videoH) else null

    val isFile get() = url?.startsWith("file:") == true
    val positionS get() = (player?.currentPosition ?: 0L) / 1000.0
    fun seekBy(s: Double) { player?.let { it.seekTo(maxOf(0L, it.currentPosition + (s * 1000).toLong())) } }
    fun seekTo(s: Double) { player?.seekTo((s * 1000).toLong()) }
    fun togglePause() { player?.let { it.playWhenReady = !it.playWhenReady } }

    fun stop() { player?.release(); player = null; url = null; videoW = 0; videoH = 0 }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); post { fit() } }

    /** Keep the picture's aspect ratio inside this view. */
    private fun fit() {
        if (width == 0 || videoW == 0) return
        val s = minOf(width.toDouble() / videoW, height.toDouble() / videoH)
        tv.layoutParams = LayoutParams((videoW * s).toInt(), (videoH * s).toInt(), Gravity.CENTER)
    }
}

/** The keyboard stays a keyboard in landscape (no full-screen text box covering the dialog or the picture). */
const val noFullscreenIme = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN

/** rtsp://user:pass@host -> rtsp://user:****@host, for anything shown on screen or logged. */
fun redact(url: String) = url.replace(Regex("(://[^:/@]+:)[^@]*@"), "$1****@")

/**
 * A still frame with shapes drawn on it, in image pixels. Mode POINTS: each tap is reported (polygon corners, map
 * points); mode RECT: each drag is reported as a rectangle (reference boxes).
 */
class FrameEditor(ctx: Context) : View(ctx) {
    enum class Mode { NONE, POINTS, RECT }
    class Shape(val pts: List<List<Double>>, val color: Int, val label: String? = null, val closed: Boolean = true, val dashed: Boolean = false)

    var image: Bitmap? = null; set(v) { field = v; invalidate() }
    var shapes: List<Shape> = emptyList(); set(v) { field = v; invalidate() }
    /** Points tapped so far (POINTS mode), drawn as an open polygon. */
    val draft = mutableListOf<List<Double>>()
    var mode = Mode.NONE
    var onPoint: ((List<Double>) -> Unit)? = null
    var onRect: ((List<Double>) -> Unit)? = null

    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var down: FloatArray? = null
    private var cur: FloatArray? = null
    private val text = Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true; setShadowLayer(4f, 1f, 1f, Color.BLACK) }

    init { setBackgroundColor(Color.BLACK); contentDescription = "frame editor" }

    private fun layout(bm: Bitmap) {
        scale = minOf(width.toFloat() / bm.width, height.toFloat() / bm.height)
        ox = (width - bm.width * scale) / 2; oy = (height - bm.height * scale) / 2
    }
    private fun sx(x: Double) = ox + x.toFloat() * scale
    private fun sy(y: Double) = oy + y.toFloat() * scale
    private fun img(x: Float, y: Float): List<Double> {
        val bm = image!!
        return listOf(((x - ox) / scale).toDouble().coerceIn(0.0, bm.width.toDouble()), ((y - oy) / scale).toDouble().coerceIn(0.0, bm.height.toDouble()))
            .map { Math.round(it).toDouble() }
    }

    override fun onDraw(cv: Canvas) {
        val bm = image ?: run { cv.drawText("no still frame for this camera yet (Cameras: connect, then Freeze as still)", 20f, 40f, text); return }
        layout(bm)
        cv.drawBitmap(bm, null, RectF(ox, oy, ox + bm.width * scale, oy + bm.height * scale), null)
        for (s in shapes + listOf(Shape(draft, Color.YELLOW, closed = false))) {
            if (s.pts.isEmpty()) continue
            val p = Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 3f; color = s.color; isAntiAlias = true
                if (s.dashed) pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            }
            val path = Path()
            s.pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(sx(q[0]), sy(q[1])) else path.lineTo(sx(q[0]), sy(q[1])) }
            if (s.closed) path.close()
            cv.drawPath(path, p)
            if (s.pts.size == 1 || !s.closed) for (q in s.pts) cv.drawCircle(sx(q[0]), sy(q[1]), 6f, p)
            s.label?.let { cv.drawText(it, sx(s.pts[0][0]) + 6, sy(s.pts[0][1]) + 28, text) }
        }
        val d = down; val c = cur
        if (mode == Mode.RECT && d != null && c != null)
            cv.drawRect(minOf(d[0], c[0]), minOf(d[1], c[1]), maxOf(d[0], c[0]), maxOf(d[1], c[1]),
                Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.YELLOW })
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (image == null || mode == Mode.NONE) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = floatArrayOf(e.x, e.y); cur = down }
            MotionEvent.ACTION_MOVE -> { cur = floatArrayOf(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP -> {
                val d = down ?: return true
                val moved = kotlin.math.hypot(e.x - d[0], e.y - d[1])
                if (mode == Mode.POINTS && moved < 30) { val q = img(e.x, e.y); draft += q; onPoint?.invoke(q) }
                if (mode == Mode.RECT && moved >= 20) {
                    val a = img(d[0], d[1]); val b = img(e.x, e.y)
                    onRect?.invoke(listOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[0], b[0]), maxOf(a[1], b[1])))
                }
                down = null; cur = null; invalidate()
            }
        }
        return true
    }
}
