package com.ald.soak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.net.Uri
import android.os.Build
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
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One camera stream: ExoPlayer (RTSP or local file, hardware MediaCodec) decoding into a GPU
 * texture (GlSink, default) or an ImageReader. Every frame is counted and its barcode read; at
 * the inference rate a frame is also shrunk to the model input size and turned into a tensor.
 */
@OptIn(UnstableApi::class)
class StreamWorker(
    private val ctx: Context,
    val id: Int,
    private val uri: String,
    private val cfg: Config,
    private val log: EventLog,
    private val clock: HostClock,
) {
    private val playerThread = HandlerThread("player$id").apply { start() }
    private val frameThread = HandlerThread("frames$id").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    private val frameHandler = Handler(frameThread.looper)
    private var player: ExoPlayer? = null
    private var reader: ImageReader? = null
    private var readerW = 0
    private var readerH = 0
    private val yuv = cfg.sink == "yuv"
    private val gl = cfg.sink == "gl"
    private var glSink: GlSink? = null

    // frame-thread state
    private val pre = Preprocessor(cfg.inferSize)
    private val periodNs = (1e9 / cfg.inferFps).toLong()
    private var nextProcNs = 0L
    private var lastPreviewNs = 0L
    private var prevIdx = -1

    // Counters: written on the player/frame threads, read by the metrics thread.
    val frames = AtomicLong()        // frames that reached our ImageReader
    val processed = AtomicLong()     // frames shrunk to model input
    val procNs = AtomicLong()
    val gaps = AtomicLong()          // frames missing between consecutive barcodes
    val repeats = AtomicLong()       // barcode went backwards / repeated
    val unreadable = AtomicLong()    // barcode guards failed (corrupt or foreign frame)
    val dropped = AtomicLong()       // ExoPlayer: decoded too late, dropped before render
    val errors = AtomicLong()
    val restarts = AtomicLong()
    val stalls = AtomicLong()        // READY -> BUFFERING transitions
    val frameErrors = AtomicLong()
    val catchups = AtomicLong()      // times playback was sped up to drain a backlog
    @Volatile var bufferedMs = 0L    // decoded-but-not-yet-shown video waiting in the player
    @Volatile var speed = 1f
    @Volatile var decoder: String? = null
    @Volatile var size: String? = null
    @Volatile var state = "idle"
    @Volatile var lastError: String? = null
    @Volatile var preview: Bitmap? = null
    @Volatile var previewWanted = true
    private val latencies = ArrayList<Long>()

    fun start() {
        playerHandler.post { createPlayer() }
    }

    fun stop() {
        val done = CountDownLatch(1)
        playerHandler.post {
            player?.release(); player = null
            frameHandler.post {
                reader?.close(); reader = null
                glSink?.release(); glSink = null
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        playerThread.quitSafely(); frameThread.quitSafely()
    }

    /** Latencies collected since the last call (ms), for the metrics window. */
    fun drainLatencies(): LongArray = synchronized(latencies) {
        val a = latencies.toLongArray(); latencies.clear(); a
    }

    private fun mediaSource(): MediaSource =
        if (cfg.source == "rtsp") {
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(cfg.tcp)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx))
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(File(uri))))
        }

    private fun createPlayer() {
        // Small buffers: a camera feed is consumed live, not pre-buffered like a movie.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1000, 3000, 250, 500).build()
        // Decoder fallback: if no hardware decoder instance is free, ExoPlayer tries the next one
        // (usually software). The decoder name is logged, so a fallback shows up in the results.
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(ctx, renderers)
            .setLooper(playerThread.looper)
            .setLoadControl(load)
            .build()
        p.addAnalyticsListener(listener)
        p.volume = 0f
        if (cfg.source == "file") p.repeatMode = Player.REPEAT_MODE_ONE
        player = p
        if (gl) attachGl(p) else attachReader(cfg.width, cfg.height)
        p.setMediaSource(mediaSource())
        p.playWhenReady = true
        p.prepare()
        log.log(id, "start", uri)
        if (cfg.source == "rtsp") playerHandler.postDelayed(catchUp, 500)
    }

    /**
     * A live camera must not fall behind: after a network stall the backlog arrives in a burst and,
     * played at 1x, would stay as permanent delay (and once the player's buffer is full, the server
     * drops data). Play faster until the backlog is drained, like low-latency live players do.
     */
    private val catchUp = object : Runnable {
        override fun run() {
            val p = player ?: return
            bufferedMs = p.totalBufferedDuration
            if (speed == 1f && bufferedMs > CATCH_UP_ABOVE_MS) {
                speed = CATCH_UP_SPEED; p.playbackParameters = PlaybackParameters(speed)
                catchups.incrementAndGet()
                log.log(id, "catch_up", "buffered ${bufferedMs}ms")
            } else if (speed != 1f && bufferedMs < CAUGHT_UP_BELOW_MS) {
                speed = 1f; p.playbackParameters = PlaybackParameters(speed)
            }
            playerHandler.postDelayed(this, 250)
        }
    }

    private fun attachGl(p: ExoPlayer) {
        // The EGL context lives on the frame thread; create it there and wait.
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        frameHandler.post {
            try {
                glSink = GlSink(cfg.inferSize, frameHandler) { onGlFrame() }.also {
                    it.videoW = cfg.width; it.videoH = cfg.height; it.init()
                }
            } catch (e: Throwable) {
                error = e
            }
            ready.countDown()
        }
        ready.await(5, TimeUnit.SECONDS)
        error?.let { log.log(id, "gl_error", it.toString()); throw it }
        p.setVideoSurface(glSink!!.surface)
    }

    private fun onGlFrame() {
        val s = glSink ?: return
        try {
            s.latch()
            frames.incrementAndGet()
            stamp(s.readStamp(), System.currentTimeMillis())
            val now = SystemClock.elapsedRealtimeNanos()
            if (now >= nextProcNs) {
                nextProcNs = if (now - nextProcNs > periodNs) now + periodNs else nextProcNs + periodNs
                s.readModel()
                pre.fromRgba(s.modelRgba)
                processed.incrementAndGet()
                procNs.addAndGet(SystemClock.elapsedRealtimeNanos() - now)
                if (previewWanted && now - lastPreviewNs > 1_000_000_000L) {
                    preview = pre.bitmap(); lastPreviewNs = now
                }
            }
        } catch (e: Exception) {
            frameErrors.incrementAndGet(); lastError = e.toString()
        }
    }

    private fun attachReader(w: Int, h: Int) {
        val p = player ?: return
        val old = reader
        val r = if (yuv && Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4, HardwareBuffer.USAGE_CPU_READ_OFTEN)
        } else if (yuv) {
            ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4)
        } else {
            ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 4)
        }
        r.setOnImageAvailableListener({ onImage(it) }, frameHandler)
        p.setVideoSurface(r.surface)
        reader = r; readerW = w; readerH = h
        if (old != null) frameHandler.post { old.close() }
    }

    private fun onImage(r: ImageReader) {
        val img = try {
            r.acquireNextImage()
        } catch (e: Exception) {
            frameErrors.incrementAndGet(); lastError = e.toString(); null
        } ?: return
        try {
            frames.incrementAndGet()
            if (!yuv) return
            val arrival = System.currentTimeMillis()
            val c = img.cropRect
            val y = img.planes[0]
            stamp(FrameStamp.read(y.buffer, y.rowStride, y.pixelStride, c.left, c.top, c.width(), c.height()), arrival)
            val now = SystemClock.elapsedRealtimeNanos()
            if (now >= nextProcNs) {
                // Fixed-rate schedule; if we fell behind by more than a period, don't try to catch up.
                nextProcNs = if (now - nextProcNs > periodNs) now + periodNs else nextProcNs + periodNs
                pre.run(img)
                processed.incrementAndGet()
                procNs.addAndGet(SystemClock.elapsedRealtimeNanos() - now)
                if (previewWanted && now - lastPreviewNs > 1_000_000_000L) {
                    preview = pre.bitmap(); lastPreviewNs = now
                }
            }
        } catch (e: Exception) {
            frameErrors.incrementAndGet(); lastError = e.toString()
        } finally {
            img.close()
        }
    }

    private fun stamp(idx: Int, arrivalMs: Long) {
        if (idx < 0) { unreadable.incrementAndGet(); return }
        val n = cfg.clipFrames
        if (n <= 0) return
        if (prevIdx >= 0) {
            val d = Math.floorMod(idx - prevIdx - 1, n)
            if (d < n / 2) gaps.addAndGet(d.toLong()) else repeats.incrementAndGet()
        }
        prevIdx = idx
        // Latency = arrival - (sender start + idx/fps), modulo the clip length (the sender loops).
        val host = clock.toHostMs(arrivalMs) ?: return
        val t0 = clock.t0(id) ?: return
        val clipMs = (n * 1000.0 / cfg.clipFps).toLong()
        val lat = Math.floorMod(host - t0 - (idx * 1000.0 / cfg.clipFps).toLong(), clipMs)
        synchronized(latencies) { latencies.add(lat) }
    }

    private fun restart() {
        val p = player ?: return
        restarts.incrementAndGet()
        log.log(id, "restart")
        p.setMediaSource(mediaSource())
        p.prepare()
    }

    companion object {
        private const val CATCH_UP_ABOVE_MS = 1000L
        private const val CAUGHT_UP_BELOW_MS = 300L
        private const val CATCH_UP_SPEED = 1.5f
    }

    private val listener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            decoder = decoderName
            log.log(id, "decoder", "$decoderName init=${initializationDurationMs}ms")
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            dropped.addAndGet(droppedFrames.toLong())
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            if (videoSize.width <= 0) return
            size = "${videoSize.width}x${videoSize.height}"
            glSink?.let { it.videoW = videoSize.width; it.videoH = videoSize.height; return }
            if (videoSize.width != readerW || videoSize.height != readerH) {
                log.log(id, "resize", "reader ${readerW}x$readerH -> $size")
                attachReader(videoSize.width, videoSize.height)
            }
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, s: Int) {
            val name = when (s) {
                Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"; else -> "ended"
            }
            if (state == "ready" && name == "buffering") stalls.incrementAndGet()
            if (name != state) log.log(id, "state", name)
            state = name
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            errors.incrementAndGet()
            lastError = "${error.errorCodeName}: ${error.message}"
            log.log(id, "error", lastError!!)
            playerHandler.postDelayed({ restart() }, 2000)
        }
    }
}
