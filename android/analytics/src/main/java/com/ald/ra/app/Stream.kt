package com.ald.ra.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.ald.ra.engine.Box
import com.ald.ra.engine.Camera
import com.ald.ra.engine.Det
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One camera as the app sees it: where it sits in its video (`region`, video pixels: its crop or the whole frame),
 * the detector's region of interest inside it (`roi`, camera pixels), and the size of the view read back for
 * crops, occupancy and the preview.
 */
class CamView(val camera: Camera, val roi: Box, val modelSize: Int, viewMax: Int) {
    val name = camera.name
    val w = camera.crop?.let { it[2] - it[0] } ?: camera.width.toDouble()
    val h = camera.crop?.let { it[3] - it[1] } ?: camera.height.toDouble()
    val viewScale = minOf(1.0, viewMax / maxOf(w, h))
    val viewW = Math.round(w * viewScale).toInt()
    val viewH = Math.round(h * viewScale).toInt()
    val lb = Letterbox(roi.w, roi.h, modelSize)
    private val free = ArrayBlockingQueue<Shot>(3).apply {
        repeat(3) { add(Shot(this@CamView, GlFrames.direct(4 * modelSize * modelSize), GlFrames.direct(4 * viewW * viewH))) }
    }
    val dropped = AtomicLong()
    val shots = AtomicLong()

    fun take(): Shot? = free.poll() ?: run { dropped.incrementAndGet(); null }
    fun give(s: Shot) { s.dets = emptyList(); free.offer(s) }

    /** Fractions of the video frame (s0, t0, s1, t1) for a box in camera pixels. */
    fun frac(b: Box, videoW: Int, videoH: Int): FloatArray {
        val ox = camera.crop?.get(0) ?: 0.0; val oy = camera.crop?.get(1) ?: 0.0
        // A camera without a crop is the whole video, whatever size the stream turns out to be.
        val sx = if (camera.crop == null) videoW / w else 1.0; val sy = if (camera.crop == null) videoH / h else 1.0
        return floatArrayOf(((ox + b.x0 * sx) / videoW).toFloat(), ((oy + b.y0 * sy) / videoH).toFloat(),
            ((ox + b.x1 * sx) / videoW).toFloat(), ((oy + b.y1 * sy) / videoH).toFloat())
    }
}

/** One analysed moment of one camera: the detector input and the camera's view, read back from the GPU. */
class Shot(val cam: CamView, val model: ByteBuffer, val view: ByteBuffer) {
    var t = 0.0
    var dets: List<Det> = emptyList()
    var inferMs = 0.0
}

/**
 * One video source (RTSP camera or local file), decoded by ExoPlayer into GPU frames. At `fps` (by stream time)
 * a frame is read back for each camera on this source and handed to `onShot`. File sources play once; time is
 * the media timestamp (so a recording gives the same times as on the laptop). RTSP time is seconds since `t0Ns`.
 */
@OptIn(UnstableApi::class)
class Stream(
    private val ctx: Context,
    val url: String,
    private val file: File?,
    val cams: List<CamView>,
    private val fps: Double,
    private val speed: Float,
    private val t0Ns: Long,
    private val onShot: (Shot) -> Unit,
    private val onEnded: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val playerThread = HandlerThread("player").apply { start() }
    private val glThread = HandlerThread("gl").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    private val glHandler = Handler(glThread.looper)
    private var player: ExoPlayer? = null
    private var gl: GlFrames? = null
    private val ptsByRelease = ConcurrentHashMap<Long, Long>()
    private var nextT = Double.NEGATIVE_INFINITY
    @Volatile var videoW = cams.first().camera.width
    @Volatile var videoH = cams.first().camera.height
    @Volatile var state = "idle"
    @Volatile var decoder: String? = null
    @Volatile var lastError: String? = null
    @Volatile var lastT = 0.0
    val frames = AtomicLong()
    val errors = AtomicLong()

    fun start() = playerHandler.post { create() }

    fun stop() {
        val done = CountDownLatch(1)
        playerHandler.post {
            player?.release(); player = null
            glHandler.post { gl?.release(); gl = null; done.countDown() }
        }
        done.await(5, TimeUnit.SECONDS)
        playerThread.quitSafely(); glThread.quitSafely()
    }

    private fun create() {
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1000, 3000, 250, 500).build()
        val p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setLooper(playerThread.looper).setLoadControl(load).build()
        p.addAnalyticsListener(listener)
        p.volume = 0f
        if (file != null && speed != 1f) p.playbackParameters = PlaybackParameters(speed)
        p.setVideoFrameMetadataListener { ptsUs, releaseNs, _, _ ->
            ptsByRelease[releaseNs] = ptsUs
            if (ptsByRelease.size > 64) ptsByRelease.keys.sorted().take(32).forEach { ptsByRelease.remove(it) }
        }
        player = p
        val ready = CountDownLatch(1)
        glHandler.post {
            try { gl = GlFrames(glHandler) { onFrame() }.also { it.init() } } catch (e: Throwable) { lastError = "gl: $e" }
            ready.countDown()
        }
        ready.await(5, TimeUnit.SECONDS)
        val g = gl ?: run { log("gl_error $lastError"); return }
        p.setVideoSurface(g.surface)
        p.setMediaSource(if (file == null) RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(url))
        else ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx)).createMediaSource(MediaItem.fromUri(Uri.fromFile(file))))
        p.playWhenReady = true
        p.prepare()
        log("stream_start $url")
    }

    private fun onFrame() {
        val g = gl ?: return
        try {
            g.latch()
            frames.incrementAndGet()
            val t = if (file != null) {
                val rel = g.timestampNs
                (ptsByRelease[rel] ?: ptsByRelease.entries.minByOrNull { kotlin.math.abs(it.key - rel) }
                    ?.takeIf { kotlin.math.abs(it.key - rel) < 20_000_000 }?.value ?: return) / 1e6
            } else (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1e9
            if (t + 1e-6 < nextT) return
            nextT = if (t - nextT > 1.0 / fps) t + 1.0 / fps else nextT + 1.0 / fps
            lastT = t
            for (c in cams) {
                val s = c.take() ?: continue
                s.t = t
                g.read(c.frac(c.roi, videoW, videoH), c.modelSize, c.modelSize, s.model, c.lb)
                g.read(c.frac(Box(0.0, 0.0, c.w, c.h), videoW, videoH), c.viewW, c.viewH, s.view)
                c.shots.incrementAndGet()
                onShot(s)
            }
        } catch (e: Exception) {
            errors.incrementAndGet(); lastError = e.toString()
        }
    }

    private val listener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                                               initializedTimestampMs: Long, initializationDurationMs: Long) {
            decoder = decoderName
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            if (videoSize.width <= 0) return
            videoW = videoSize.width; videoH = videoSize.height
            log("video_size $url ${videoW}x$videoH")
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, s: Int) {
            state = when (s) { Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; else -> "ended" }
            if (s == Player.STATE_ENDED) glHandler.post { onEnded() }  // after the last frames on the GL thread
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            errors.incrementAndGet()
            lastError = "${error.errorCodeName}: ${error.message}"
            log("stream_error $url $lastError")
            if (file == null) playerHandler.postDelayed({ player?.prepare() }, 2000)
        }
    }
}
