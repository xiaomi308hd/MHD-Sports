package com.sportsplayer.app

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val view = PlayerView(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(view)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val p = ExoPlayer.Builder(this).build().also { player = it; view.player = it }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Không phát được: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })

        lifecycleScope.launch {
            // Giải link rút gọn trước khi phát
            val (finalUrl, type) = try {
                withContext(Dispatchers.IO) { Net.resolve(url) }
            } catch (e: Exception) {
                url to null
            }
            val item = MediaItem.Builder().setUri(finalUrl).apply {
                if (type?.contains("mpegurl", true) == true || finalUrl.contains(".m3u8", true))
                    setMimeType(MimeTypes.APPLICATION_M3U8)
            }.build()
            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
