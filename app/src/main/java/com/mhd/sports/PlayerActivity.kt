package com.mhd.sports

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private data class Attempt(val label: String, val ua: String, val headers: Map<String, String>)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var tvRes: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvSpeed: TextView
    private var attempts: List<Attempt> = emptyList()
    private var idx = 0
    private var playUrl = ""
    private var mime: String? = null

    // ---- số liệu thực tế ----
    private val ui = Handler(Looper.getMainLooper())
    private val bytes = AtomicLong(0)          // byte đã tải trong giây hiện tại
    private val frames = AtomicInteger(0)      // số khung hình thực sự được vẽ trong giây hiện tại
    private val samples = ArrayDeque<Long>()   // trung bình 5 giây cho tốc độ tải đỡ giật (HLS tải theo cụm)
    private val statsTick = object : Runnable {
        override fun run() { updateStats(); ui.postDelayed(this, 1000) }
    }

    // Link đã có đuôi rõ ràng thì không cần "dò" trước (tránh làm hỏng link dùng 1 lần / có token)
    private val direct = Regex("""\.(m3u8|flv|ts|mp4|mpd)\b""", RegexOption.IGNORE_CASE)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val name = intent.getStringExtra("name") ?: ""

        // ---- giao diện: video + thanh thông tin kính phía trên ----
        val root = FrameLayout(this)
        view = PlayerView(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.glass(this@PlayerActivity, 20f, tint = 0x99000000.toInt())
            setPadding(dp(18), dp(10), dp(10), dp(10))
        }
        val tvName = TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, Typeface.BOLD); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(tvName, LinearLayout.LayoutParams(0, WRAP, 1f))
        tvRes = statChip("-- × --")
        tvFps = statChip("-- fps")
        tvSpeed = statChip("Tải -- KB/s")
        listOf(tvRes, tvFps, tvSpeed).forEach {
            bar.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).also { p -> p.leftMargin = dp(8) })
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
            .also { it.setMargins(dp(16), dp(12), dp(16), 0) })
        // thanh thông tin hiện/ẩn cùng bộ nút điều khiển (bấm OK/chạm màn hình để hiện)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            bar.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
        })
        setContentView(root)

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
        ui.post(statsTick)
    }

    private fun statChip(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 13f
        setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = Glass.glass(this@PlayerActivity, 14f, tint = 0x40FFFFFF)
    }

    private fun updateStats() {
        val p = player ?: return
        // độ phân giải thực tế của khung hình đang giải mã
        val vs = p.videoSize
        tvRes.text = if (vs.width > 0 && vs.height > 0) "${vs.width}×${vs.height}" else "-- × --"
        // fps thực tế: đếm khung hình thật sự được vẽ; nếu chưa có thì dùng fps khai báo của luồng
        val measured = frames.getAndSet(0)
        val declared = p.videoFormat?.frameRate ?: -1f
        val fps = when {
            measured > 0 -> measured
            p.isPlaying && declared > 0 -> declared.roundToInt()
            else -> 0
        }
        tvFps.text = if (fps > 0) "$fps fps" else "-- fps"
        // tốc độ tải: byte nhận qua mạng, trung bình 5 giây
        samples.addLast(bytes.getAndSet(0))
        if (samples.size > 5) samples.removeFirst()
        tvSpeed.text = "Tải " + formatSpeed(samples.sum() / samples.size)
    }

    private fun formatSpeed(bytesPerSec: Long): String =
        if (bytesPerSec >= 1_000_000) String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000.0)
        else String.format(Locale.US, "%d KB/s", bytesPerSec / 1000)

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
        val counter = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(a.ua)
            .setAllowCrossProtocolRedirects(true)   // http <-> https khi chuyển hướng
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(a.headers)
            .setTransferListener(counter)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
        player = p
        view.player = p
        p.setVideoFrameMetadataListener(VideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() })
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
        ui.removeCallbacks(statsTick)
        player?.release()
        player = null
        super.onDestroy()
    }
}
