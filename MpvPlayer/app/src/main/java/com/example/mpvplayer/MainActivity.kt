package com.example.mpvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.mpvplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            play(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnOpen.setOnClickListener { picker.launch(arrayOf("video/*", "audio/*", "application/x-matroska")) }
        b.btnPlayUrl.setOnClickListener {
            val u = b.etUrl.text.toString().trim()
            if (u.isEmpty()) {
                Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show()
            } else {
                play(Uri.parse(u))
            }
        }

        // 其他应用通过 "用 MPV Player 打开" 进来
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            play(intent.data!!)
        }
    }

    private fun qualityIndex(): Int = when (b.rgQuality.checkedRadioButtonId) {
        R.id.rbFast -> Quality.FAST.ordinal
        R.id.rbBalanced -> Quality.BALANCED.ordinal
        else -> Quality.HIGH.ordinal
    }

    private fun play(uri: Uri) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(PlayerActivity.EXTRA_QUALITY, qualityIndex())
        )
    }
}
