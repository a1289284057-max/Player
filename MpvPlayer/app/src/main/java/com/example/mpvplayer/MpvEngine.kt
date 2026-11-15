package com.example.mpvplayer

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import java.io.File

/** 画质预设，对应桌面 mpv 的 fast / default / high-quality 配置 */
enum class Quality(val label: String) {
    FAST("快速"),
    BALANCED("均衡"),
    HIGH("mpv 高质量")
}

data class Track(val id: Int, val title: String)

class MpvEngine(private val ctx: Context) : MPVLib.EventObserver {

    interface Listener {
        fun onPosition(sec: Double)
        fun onDuration(sec: Double)
        fun onPause(paused: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onEnd()
    }

    var listener: Listener? = null
    private var initialized = false
    private var hasShaders = false

    fun init(quality: Quality) {
        if (initialized) return
        MPVLib.create(ctx)

        // ---- 初始化前选项 ----
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "movie")

        // 硬解优先；失败自动回退到软解（FFmpeg / dav1d）
        MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

        // 网络 / 缓存
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
        MPVLib.setOptionString("demuxer-max-back-bytes", "32MiB")
        MPVLib.setOptionString("network-timeout", "15")

        // 字幕（libass）
        MPVLib.setOptionString("sub-auto", "fuzzy")
        MPVLib.setOptionString("sub-font-size", "48")
        MPVLib.setOptionString("sub-border-size", "3")
        MPVLib.setOptionString("blend-subtitles", "video")

        // TLS 证书
        copyAsset("cacert.pem")?.let {
            MPVLib.setOptionString("tls-verify", "yes")
            MPVLib.setOptionString("tls-ca-file", it.absolutePath)
        }
        // 外挂 GLSL 着色器
        hasShaders = installShaders()

        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "yes")

        MPVLib.init()
        initialized = true

        applyQuality(quality)

        MPVLib.addObserver(this)
        MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
    }

    /**
     * 画质预设。HIGH 等价于桌面 mpv 的 profile=high-quality 再加上去色带、
     * sigmoid 放大、线性光缩小、HDR 峰值检测与 display-resample 插帧。
     */
    fun applyQuality(q: Quality) {
        val p = mutableMapOf<String, String>()
        when (q) {
            Quality.FAST -> {
                p["scale"] = "bilinear"; p["cscale"] = "bilinear"; p["dscale"] = "bilinear"
                p["dither-depth"] = "no"; p["deband"] = "no"
                p["correct-downscaling"] = "no"; p["linear-downscaling"] = "no"; p["sigmoid-upscaling"] = "no"
                p["interpolation"] = "no"; p["video-sync"] = "audio"
                p["glsl-shaders"] = ""
            }
            Quality.BALANCED -> {
                p["scale"] = "spline36"; p["cscale"] = "bilinear"; p["dscale"] = "mitchell"
                p["dither-depth"] = "auto"; p["deband"] = "no"
                p["correct-downscaling"] = "yes"; p["linear-downscaling"] = "no"; p["sigmoid-upscaling"] = "yes"
                p["interpolation"] = "no"; p["video-sync"] = "audio"
                p["glsl-shaders"] = ""
            }
            Quality.HIGH -> {
                p["scale"] = "ewa_lanczossharp"; p["cscale"] = "ewa_lanczossharp"; p["dscale"] = "mitchell"
                p["scale-antiring"] = "0.6"; p["cscale-antiring"] = "0.6"
                p["dither-depth"] = "auto"; p["dither"] = "fruit"
                p["deband"] = "yes"; p["deband-iterations"] = "2"; p["deband-grain"] = "24"
                p["correct-downscaling"] = "yes"; p["linear-downscaling"] = "yes"; p["sigmoid-upscaling"] = "yes"
                p["hdr-compute-peak"] = "yes"; p["tone-mapping"] = "bt.2390"
                p["video-sync"] = "display-resample"; p["interpolation"] = "yes"; p["tscale"] = "oversample"
                p["glsl-shaders"] = if (hasShaders) shaderList() else ""
            }
        }
        for ((k, v) in p) MPVLib.setPropertyString(k, v)
    }

    // ---------------- Surface ----------------
    fun attachSurface(surface: Surface) {
        MPVLib.attachSurface(surface)
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setPropertyString("vo", "gpu")
    }

    fun surfaceSize(w: Int, h: Int) {
        MPVLib.setPropertyString("android-surface-size", w.toString() + "x" + h.toString())
    }

    fun detachSurface() {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    // ---------------- 播放控制 ----------------
    fun load(path: String) = MPVLib.command(arrayOf("loadfile", path))
    fun setPaused(p: Boolean) = MPVLib.setPropertyBoolean("pause", p)
    fun isPaused(): Boolean = MPVLib.getPropertyBoolean("pause") ?: false
    fun seekRelative(sec: Int) = MPVLib.command(arrayOf("seek", sec.toString(), "relative+exact"))
    fun seekAbsolute(sec: Double) = MPVLib.command(arrayOf("seek", sec.toString(), "absolute+exact"))
    fun setSpeed(s: Double) = MPVLib.setPropertyDouble("speed", s)
    fun addSubtitle(path: String) = MPVLib.command(arrayOf("sub-add", path, "select"))
    fun screenshot(file: String) = MPVLib.command(arrayOf("screenshot-to-file", file, "window"))

    fun tracks(type: String): List<Track> {
        val n = MPVLib.getPropertyInt("track-list/count") ?: 0
        val out = ArrayList<Track>()
        for (i in 0 until n) {
            if (MPVLib.getPropertyString("track-list/" + i + "/type") != type) continue
            val id = MPVLib.getPropertyInt("track-list/" + i + "/id") ?: continue
            val title = MPVLib.getPropertyString("track-list/" + i + "/title")
            val lang = MPVLib.getPropertyString("track-list/" + i + "/lang")
            val codec = MPVLib.getPropertyString("track-list/" + i + "/codec")
            val name = listOfNotNull(title, lang, codec).joinToString(" · ")
            out.add(Track(id, if (name.isEmpty()) "Track " + id else name))
        }
        return out
    }

    fun selectTrack(type: String, id: Int?) {
        val prop = if (type == "audio") "aid" else "sid"
        if (id == null) MPVLib.setPropertyString(prop, "no") else MPVLib.setPropertyInt(prop, id)
    }

    fun videoInfo(): String {
        val w = MPVLib.getPropertyInt("video-params/w") ?: 0
        val h = MPVLib.getPropertyInt("video-params/h") ?: 0
        val codec = MPVLib.getPropertyString("video-codec") ?: "?"
        val hw = MPVLib.getPropertyString("hwdec-current") ?: "no"
        return w.toString() + "x" + h + "  " + codec + "  hwdec=" + hw
    }

    fun destroy() {
        if (!initialized) return
        MPVLib.removeObserver(this)
        MPVLib.destroy()
        initialized = false
    }

    // ---------------- 事件 ----------------
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: String) {}

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> listener?.onPosition(value)
            "duration" -> listener?.onDuration(value)
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> listener?.onPause(value)
            "paused-for-cache" -> listener?.onBuffering(value)
            "eof-reached" -> if (value) listener?.onEnd()
        }
    }

    override fun event(eventId: Int) {}

    // ---------------- 工具 ----------------
    private fun copyAsset(name: String): File? {
        return try {
            val out = File(ctx.filesDir, name)
            ctx.assets.open(name).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun installShaders(): Boolean {
        val names = try { ctx.assets.list("shaders") } catch (e: Exception) { null } ?: return false
        if (names.isEmpty()) return false
        val dir = File(ctx.filesDir, "shaders").apply { mkdirs() }
        for (n in names) {
            ctx.assets.open("shaders/" + n).use { i -> File(dir, n).outputStream().use { o -> i.copyTo(o) } }
        }
        return true
    }

    private fun shaderList(): String {
        val dir = File(ctx.filesDir, "shaders")
        return (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".glsl") }
            .sortedBy { it.name }.joinToString(":") { it.absolutePath }
    }
}
