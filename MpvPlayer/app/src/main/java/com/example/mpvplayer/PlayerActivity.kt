package com.example.mpvplayer

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.mpvplayer.databinding.ActivityPlayerBinding
import kotlin.math.abs

class PlayerActivity : AppCompatActivity(), MpvEngine.Listener, SurfaceHolder.Callback {

    private lateinit var b: ActivityPlayerBinding
    private val engine by lazy { MpvEngine(applicationContext) }
    private val ui = Handler(Looper.getMainLooper())

    private var duration = 0.0
    private var userSeeking = false
    private var quality = Quality.HIGH
    private var pendingPath: String? = null
    private var gestureMode = 0 // 0 无 1 亮度 2 音量 3 横向 seek
    private var gestureSeekTarget = 0.0
    private var position = 0.0

    private val hideControls = Runnable { b.controls.visibility = View.GONE }
    private val hideOsd = Runnable { b.osd.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        quality = Quality.values().getOrElse(intent.getIntExtra(EXTRA_QUALITY, 2)) { Quality.HIGH }
        val uri = intent.data ?: Uri.parse(intent.getStringExtra(EXTRA_URL) ?: "")
        pendingPath = resolve(uri)

        engine.listener = this
        engine.init(quality)
        b.surface.holder.addCallback(this)
        setupControls()
        setupGestures()
        scheduleHide()
    }

    // ---------------- Surface ----------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        engine.attachSurface(holder.surface)
        pendingPath?.let { engine.load(it); pendingPath = null }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        engine.surfaceSize(w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine.detachSurface()
    }

    // ---------------- 控制栏 ----------------
    private fun setupControls() {
        b.btnPlay.setOnClickListener { engine.setPaused(!engine.isPaused()) }
        b.btnBack10.setOnClickListener { engine.seekRelative(-10) }
        b.btnFwd10.setOnClickListener { engine.seekRelative(10) }
        b.btnSpeed.setOnClickListener { pickSpeed() }
        b.btnAudio.setOnClickListener { pickTrack("audio", "音轨") }
        b.btnSub.setOnClickListener { pickTrack("sub", "字幕") }
        b.btnQuality.setOnClickListener { pickQuality() }
        b.btnShot.setOnClickListener {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val f = dir?.absolutePath + "/mpv-" + System.currentTimeMillis() + ".png"
            engine.screenshot(f)
            toast("截图已保存：" + f)
        }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) b.tvPos.text = fmt(p.toDouble())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true; ui.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(sb: SeekBar) {
                engine.seekAbsolute(sb.progress.toDouble())
                userSeeking = false
                scheduleHide()
            }
        })
    }

    private fun pickSpeed() {
        val speeds = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)
        AlertDialog.Builder(this).setTitle("播放速度")
            .setItems(speeds.map { it.toString() + "x" }.toTypedArray()) { _, i ->
                engine.setSpeed(speeds[i]); osd("速度 " + speeds[i] + "x")
            }.show()
    }

    private fun pickTrack(type: String, title: String) {
        val list = engine.tracks(type)
        val names = ArrayList<String>().apply { add("关闭"); addAll(list.map { it.title }) }
        AlertDialog.Builder(this).setTitle(title).setItems(names.toTypedArray()) { _, i ->
            engine.selectTrack(type, if (i == 0) null else list[i - 1].id)
            osd(title + "：" + names[i])
        }.show()
    }

    private fun pickQuality() {
        val all = Quality.values()
        AlertDialog.Builder(this).setTitle("画质预设")
            .setSingleChoiceItems(all.map { it.label }.toTypedArray(), quality.ordinal) { d, i ->
                quality = all[i]
                engine.applyQuality(quality)
                osd("画质：" + quality.label)
                d.dismiss()
            }.show()
    }

    // ---------------- 手势 ----------------
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls(); return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val left = e.x < b.root.width / 2f
                engine.seekRelative(if (left) -10 else 10)
                osd(if (left) "-10 秒" else "+10 秒")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                engine.setSpeed(2.0); osd("2x 倍速")
            }
        })

        var downX = 0f; var downY = 0f
        var startVol = 0; var startBright = 0f
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

        b.surface.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; gestureMode = 0
                    startVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    startBright = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                    gestureSeekTarget = position
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (gestureMode == 0 && (abs(dx) > 40 || abs(dy) > 40)) {
                        gestureMode = if (abs(dx) > abs(dy)) 3 else if (downX < b.root.width / 2f) 1 else 2
                    }
                    when (gestureMode) {
                        1 -> {
                            val v = (startBright - dy / b.root.height).coerceIn(0.02f, 1f)
                            window.attributes = window.attributes.also { it.screenBrightness = v }
                            osd("亮度 " + (v * 100).toInt() + "%")
                        }
                        2 -> {
                            val v = (startVol - dy / b.root.height * maxVol * 1.2f).toInt().coerceIn(0, maxVol)
                            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
                            osd("音量 " + (v * 100 / maxVol) + "%")
                        }
                        3 -> {
                            gestureSeekTarget = (position + dx / b.root.width * 120).coerceIn(0.0, duration)
                            osd(fmt(gestureSeekTarget) + " / " + fmt(duration))
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureMode == 3) engine.seekAbsolute(gestureSeekTarget)
                    if (engine.isPaused().not()) engine.setSpeed(1.0)
                    gestureMode = 0
                }
            }
            true
        }
    }

    // ---------------- 引擎回调（可能在非主线程） ----------------
    override fun onPosition(sec: Double) = runOnUiThread {
        position = sec
        if (!userSeeking) {
            b.seek.progress = sec.toInt()
            b.tvPos.text = fmt(sec)
        }
    }

    override fun onDuration(sec: Double) = runOnUiThread {
        duration = sec
        b.seek.max = sec.toInt()
        b.tvDur.text = fmt(sec)
        osd(engine.videoInfo())
    }

    override fun onPause(paused: Boolean) = runOnUiThread {
        b.btnPlay.text = if (paused) "播放" else "暂停"
        if (paused) { b.controls.visibility = View.VISIBLE; ui.removeCallbacks(hideControls) } else scheduleHide()
    }

    override fun onBuffering(buffering: Boolean) = runOnUiThread {
        b.loading.visibility = if (buffering) View.VISIBLE else View.GONE
    }

    override fun onEnd() = runOnUiThread { finish() }

    // ---------------- 工具 ----------------
    private fun resolve(uri: Uri): String? = when (uri.scheme) {
        "content" -> {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) null else "fd://" + pfd.detachFd()
        }
        "file" -> uri.path
        else -> uri.toString()
    }

    private fun toggleControls() {
        if (b.controls.visibility == View.VISIBLE) {
            b.controls.visibility = View.GONE
        } else {
            b.controls.visibility = View.VISIBLE; scheduleHide()
        }
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (!engine.isPaused()) ui.postDelayed(hideControls, 3500)
    }

    private fun osd(msg: String) {
        b.osd.text = msg
        b.osd.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, 1500)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(t: Double): String {
        val s = t.toInt().coerceAtLeast(0)
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) engine.setPaused(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        engine.destroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_QUALITY = "quality"
    }
}
