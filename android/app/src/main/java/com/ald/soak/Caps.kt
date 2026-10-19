package com.ald.soak

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Experiment 0: what the device says about its video decoders, plus a direct probe of how many
 * hardware decoder instances can actually be configured and started at once.
 */
object Caps {
    private val MIMES = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
    private val SIZES = listOf(1920 to 1080, 1280 to 720, 2560 to 1440, 640 to 360)

    fun collect(ctx: Context): JSONObject {
        val out = JSONObject()
        out.put("device", device(ctx))
        val decoders = JSONArray()
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.startsWith("video/")) continue
                decoders.put(describe(info, type))
            }
        }
        out.put("decoders", decoders)
        val probes = JSONArray()
        for (mime in MIMES) {
            val hw = hardwareDecoder(mime) ?: continue
            for ((w, h) in listOf(1920 to 1080, 1280 to 720)) probes.put(probeInstances(hw, mime, w, h))
        }
        out.put("instance_probe", probes)
        return out
    }

    fun write(ctx: Context, file: File): JSONObject {
        val j = collect(ctx)
        file.writeText(j.toString(2))
        return j
    }

    /** One-paragraph summary for the screen. */
    fun summary(j: JSONObject): String {
        val sb = StringBuilder()
        val d = j.getJSONObject("device")
        sb.append("${d.optString("manufacturer")} ${d.optString("model")}  SoC ${d.optString("soc")}  " +
            "Android ${d.optString("release")}  RAM ${d.optLong("ram_mb")} MB\n")
        val probes = j.getJSONArray("instance_probe")
        for (i in 0 until probes.length()) {
            val p = probes.getJSONObject(i)
            sb.append("${p.getString("decoder")} ${p.getString("size")}: ${p.getInt("started")} instances started " +
                "(reported max ${p.optInt("reported_max")})\n")
        }
        return sb.toString()
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))

    private fun hardwareDecoder(mime: String): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && isHardware(it) && it.supportedTypes.any { t -> t.equals(mime, true) } &&
                !it.name.contains("secure", true)
        }?.name

    private fun describe(info: MediaCodecInfo, type: String): JSONObject {
        val caps = info.getCapabilitiesForType(type)
        val v = caps.videoCapabilities
        val j = JSONObject()
            .put("name", info.name).put("type", type)
            .put("hardware", isHardware(info))
            .put("max_instances", caps.maxSupportedInstances)
        if (Build.VERSION.SDK_INT >= 29) {
            j.put("software_only", info.isSoftwareOnly).put("vendor", info.isVendor)
        }
        if (v != null) {
            j.put("widths", v.supportedWidths.toString()).put("heights", v.supportedHeights.toString())
                .put("frame_rates", v.supportedFrameRates.toString())
                .put("bitrates", v.bitrateRange.toString())
            val sizes = JSONObject()
            for ((w, h) in SIZES) {
                val s = JSONObject().put("supported", v.isSizeSupported(w, h))
                if (v.isSizeSupported(w, h)) {
                    s.put("ok_25fps", v.areSizeAndRateSupported(w, h, 25.0))
                    s.put("achievable_fps", v.getAchievableFrameRatesFor(w, h)?.toString())
                }
                sizes.put("${w}x$h", s)
            }
            j.put("sizes", sizes)
            if (Build.VERSION.SDK_INT >= 29) {
                j.put("performance_points", JSONArray(v.supportedPerformancePoints?.map { it.toString() } ?: emptyList<String>()))
            }
        }
        return j
    }

    private fun probeInstances(name: String, mime: String, w: Int, h: Int): JSONObject {
        val codecs = ArrayList<MediaCodec>()
        var error: String? = null
        var started = 0
        try {
            while (started < 16) {
                val c = MediaCodec.createByCodecName(name)
                codecs.add(c)
                c.configure(MediaFormat.createVideoFormat(mime, w, h), null, null, 0)
                c.start()
                started++
            }
        } catch (e: Exception) {
            error = e.toString()
        }
        codecs.forEach { runCatching { it.release() } }
        val reported = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == name }
            .getCapabilitiesForType(mime).maxSupportedInstances
        return JSONObject().put("decoder", name).put("mime", mime).put("size", "${w}x$h")
            .put("started", started).put("limit_tested", 16)
            .put("reported_max", reported).put("error", error)
    }

    private fun device(ctx: Context): JSONObject {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val j = JSONObject()
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("hardware", Build.HARDWARE).put("board", Build.BOARD)
            .put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
            .put("fingerprint", Build.FINGERPRINT)
            .put("ram_mb", mi.totalMem / (1024 * 1024))
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("cpu_max_khz", JSONArray(Sys.cpuMaxFreqs()))
        if (Build.VERSION.SDK_INT >= 31) j.put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        else j.put("soc", Build.HARDWARE)
        return j
    }
}
