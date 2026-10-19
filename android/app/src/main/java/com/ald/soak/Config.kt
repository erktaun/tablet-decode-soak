package com.ald.soak

import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One soak-test run. Every field can be set from `adb shell am start ... --es <key> <value>`
 * (all values are passed as strings so the laptop script stays simple).
 */
data class Config(
    val runId: String,
    val source: String,        // "file" (local clips, decode only) | "rtsp"
    val uris: List<String>,    // rtsp://... urls, or absolute paths of local clips
    val inferFps: Double,      // how often each stream's frame is pulled and shrunk, per second
    val inferSize: Int,        // model input side (letterboxed square)
    val minutes: Double,       // run length; 0 = until stopped
    val tcp: Boolean,          // RTSP over TCP (interleaved) instead of UDP
    val sink: String,          // "gl" = GPU downscale + readback (default) | "yuv" = CPU YUV frames | "private" = decode only
    val infoUrl: String?,      // laptop endpoint giving its clock + sender start times (latency)
    val clipFrames: Int,       // frames per clip loop, for counting lost frames (0 = unknown)
    val clipFps: Double,
    val width: Int,            // expected video size; corrected automatically if the stream differs
    val height: Int,
    val periodS: Int,          // metrics period
) {
    fun toJson(): JSONObject = JSONObject()
        .put("run_id", runId).put("source", source).put("uris", JSONArray(uris))
        .put("infer_fps", inferFps).put("infer_size", inferSize).put("minutes", minutes)
        .put("transport", if (tcp) "tcp" else "udp").put("sink", sink).put("info_url", infoUrl)
        .put("clip_frames", clipFrames).put("clip_fps", clipFps)
        .put("width", width).put("height", height).put("period_s", periodS)

    companion object {
        fun clipsDir(ctx: Context): File = ctx.getExternalFilesDir("clips")!!
        fun runsDir(ctx: Context): File = ctx.getExternalFilesDir("runs")!!

        fun newRunId(tag: String): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + tag

        /** Local clips pushed by the laptop script (or by hand) into files/clips/. */
        fun localClips(ctx: Context): List<String> =
            clipsDir(ctx).listFiles { f -> f.name.endsWith(".mp4") }
                ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()

        fun from(ctx: Context, extras: Bundle?): Config {
            fun s(key: String): String? = extras?.getString(key)?.takeIf { it.isNotBlank() }
            val source = s("source") ?: "file"
            val streams = s("streams")?.toInt() ?: 4
            val uris = s("urls")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: localClips(ctx)
            return Config(
                runId = s("run_id") ?: newRunId(source),
                source = source,
                uris = uris.take(streams),
                inferFps = s("infer_fps")?.toDouble() ?: 5.0,
                inferSize = s("infer_size")?.toInt() ?: 320,
                minutes = s("minutes")?.toDouble() ?: 0.0,
                tcp = (s("transport") ?: "tcp") == "tcp",
                sink = s("sink") ?: "gl",
                infoUrl = s("info_url"),
                clipFrames = s("clip_frames")?.toInt() ?: 750,
                clipFps = s("clip_fps")?.toDouble() ?: 25.0,
                width = s("width")?.toInt() ?: 1920,
                height = s("height")?.toInt() ?: 1080,
                periodS = s("period_s")?.toInt() ?: 5,
            )
        }
    }
}
