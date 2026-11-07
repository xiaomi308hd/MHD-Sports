package com.mhd.sports

import android.graphics.Color
import android.net.Uri
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private data class Attempt(val label: String, val ua: String, val headers: Map<String, String>)

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private var attempts: List<Attempt> = emptyList()
    private var idx = 0
    private var playUrl = ""
    private var mime: String? = null

    // Link đã có đuôi rõ ràng thì không cần "dò" trước (tránh làm hỏng link dùng 1 lần / có token)
    private val direct = Regex("""\.(m3u8|flv|ts|mp4|mpd)\b""", RegexOption.IGNORE_CASE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view = PlayerView(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(view)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val own = (intent.getSerializableExtra("headers") as? HashMap<String, String>) ?: HashMap()
        val listUrl = getSharedPreferences("p", MODE_PRIVATE).getString("url", null)
        attempts = buildAttempts(url, own, listUrl)

        lifecycleScope.launch {
            playUrl = url
            mime = if (url.contains(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else null
            if (!direct.containsMatchIn(url)) { // link rút gọn / không đuôi => giải về link thật
                try {
                    val a = attempts[0]
                    val (u, type) = withContext(Dispatchers.IO) { Net.resolve(url, a.headers, a.ua) }
                    playUrl = u
                    if (type?.contains("mpegurl", true) == true || u.contains(".m3u8", true)) mime = MimeTypes.APPLICATION_M3U8
                } catch (_: Exception) { }
            }
            startAttempt()
        }
    }

    private fun origin(u: String): String? = Uri.parse(u).let {
        if (it.scheme != null && it.host != null) "${it.scheme}://${it.host}" + (if (it.port > 0) ":${it.port}" else "") else null
    }

    /** Các cách thử lần lượt khi máy chủ trả lỗi HTTP: mặc định -> Referer theo máy chủ phát -> Referer theo nguồn danh sách -> UA khác. */
    private fun buildAttempts(url: String, own: Map<String, String>, listUrl: String?): List<Attempt> {
        val ua = own["User-Agent"] ?: Net.UA
        val clean = own.filterKeys { !it.equals("User-Agent", true) }
        val so = origin(url)
        val lo = listUrl?.let { origin(it) }
        val l = mutableListOf(Attempt("mặc định", ua, clean))
        if (so != null) l += Attempt("Referer theo máy chủ phát", ua, mapOf("Referer" to "$so/", "Origin" to so) + clean)
        if (lo != null && lo != so) l += Attempt("Referer theo nguồn danh sách", ua, mapOf("Referer" to "$lo/", "Origin" to lo) + clean)
        l += Attempt("giả lập VLC", "VLC/3.0.18 LibVLC/3.0.18", clean)
        l += Attempt("giả lập ffmpeg", "Lavf/60.3.100", clean)
        return l
    }

    private fun startAttempt() {
        player?.release()
        val a = attempts[idx]
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(a.ua)
            .setAllowCrossProtocolRedirects(true)   // http <-> https khi chuyển hướng
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(a.headers)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
        player = p
        view.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && idx < attempts.size - 1) {
                    idx++
                    Toast.makeText(
                        this@PlayerActivity,
                        "Máy chủ từ chối (HTTP ${code ?: "?"}). Thử cách ${idx + 1}/${attempts.size}: ${attempts[idx].label}…",
                        Toast.LENGTH_SHORT
                    ).show()
                    view.post { startAttempt() }
                } else {
                    Toast.makeText(this@PlayerActivity, "Không phát được: " + explain(code, error), Toast.LENGTH_LONG).show()
                }
            }
        })
        p.setMediaItem(MediaItem.Builder().setUri(playUrl).apply { mime?.let { setMimeType(it) } }.build())
        p.prepare()
        p.playWhenReady = true
    }

    private fun explain(code: Int?, e: PlaybackException): String = when {
        code == 401 || code == 403 -> "HTTP $code: máy chủ chặn truy cập (nguồn cần Referer/User-Agent riêng hoặc giới hạn IP/thiết bị)"
        code == 404 || code == 410 -> "HTTP $code: link không còn tồn tại hoặc đã hết hạn"
        code != null && code >= 500 -> "HTTP $code: máy chủ phát đang lỗi, thử lại sau"
        code != null -> "HTTP $code"
        else -> e.errorCodeName
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
