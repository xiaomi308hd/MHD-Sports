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
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
    private data class Attempt(
        val label: String,
        val ua: String,
        val headers: Map<String, String>,
        val protocol: Boolean = false,   // true: thử đổi giao thức/định dạng (không chỉ đổi header)
        val swapScheme: Boolean = false, // http <-> https
        val lenientTls: Boolean = false, // bỏ kiểm tra chứng chỉ TLS (chỉ dùng khi các cách khác đã lỗi)
        val forceMime: String? = null    // ép đọc theo HLS / FLV
    )
    private class Source(val url: String, val headers: Map<String, String>)

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
    private var sources: List<Source> = emptyList()
    private var srcIdx = 0
    private var listUrl: String? = null
    private var playedOnce = false
    private var reconnects = 0

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
        val altUrls = intent.getStringArrayListExtra("alt_urls") ?: arrayListOf()
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val altHeaders = (intent.getSerializableExtra("alt_headers") as? ArrayList<HashMap<String, String>>) ?: arrayListOf()
        listUrl = getSharedPreferences("p", MODE_PRIVATE).getString("url", null)
        val list = mutableListOf(makeSource(url, own))
        altUrls.forEachIndexed { i, u -> list += makeSource(u, altHeaders.getOrNull(i) ?: emptyMap()) }
        sources = list.distinctBy { it.url }

        lifecycleScope.launch { beginSource(0) }
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

    /** Chuẩn hoá link; hỗ trợ dạng "https://...m3u8|User-Agent=abc&Referer=https://site". */
    private fun makeSource(raw: String, base: Map<String, String>): Source {
        val headers = LinkedHashMap(base)
        var u = raw.trim()
        val bar = u.indexOf('|')
        if (bar > 0) {
            val tail = u.substring(bar + 1)
            u = u.substring(0, bar)
            tail.split('&').forEach { kv ->
                val k = kv.substringBefore('=', "").trim()
                val v = kv.substringAfter('=', "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    val name = when (k.lowercase()) {
                        "user-agent", "useragent", "ua" -> "User-Agent"
                        "referer", "referrer" -> "Referer"
                        "origin" -> "Origin"
                        "cookie" -> "Cookie"
                        else -> k
                    }
                    headers[name] = v
                }
            }
        }
        u = u.replace(" ", "%20")
        if (u.startsWith("//")) u = "http:$u"
        return Source(u, headers)
    }

    /** Mở một nguồn (luồng chính hoặc luồng dự phòng): dò link rút gọn rồi bắt đầu thử từ cách đầu tiên. */
    private suspend fun beginSource(i: Int) {
        srcIdx = i
        idx = 0
        reconnects = 0
        playedOnce = false
        val src = sources[i]
        attempts = buildAttempts(src.url, src.headers, listUrl)
        playUrl = src.url
        mime = if (src.url.contains(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else null
        if (!direct.containsMatchIn(src.url)) { // link rút gọn / không đuôi => giải về link thật
            try {
                val a = attempts[0]
                val (u, type) = withContext(Dispatchers.IO) { Net.resolve(src.url, a.headers, a.ua) }
                playUrl = u
                if (type?.contains("mpegurl", true) == true || u.contains(".m3u8", true)) mime = MimeTypes.APPLICATION_M3U8
            } catch (_: Exception) { }
        }
        startAttempt()
    }

    private fun swapScheme(u: String): String = when {
        u.startsWith("https://", true) -> "http://" + u.substring(8)
        u.startsWith("http://", true) -> "https://" + u.substring(7)
        else -> u
    }

    /**
     * Các cách thử lần lượt:
     * 1) đổi header (mặc định -> Referer theo máy chủ phát -> Referer theo nguồn danh sách -> VLC -> ffmpeg),
     * 2) đổi giao thức: http <-> https, bỏ kiểm tra chứng chỉ TLS, ép đọc HLS, ép đọc FLV.
     */
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
        if (url.startsWith("http", true)) l += Attempt("đổi http ↔ https", ua, clean, protocol = true, swapScheme = true)
        l += Attempt("bỏ kiểm tra chứng chỉ TLS", ua, clean, protocol = true, lenientTls = true)
        l += Attempt("ép đọc HLS", ua, clean, protocol = true, forceMime = MimeTypes.APPLICATION_M3U8)
        l += Attempt("ép đọc FLV", ua, clean, protocol = true, forceMime = MimeTypes.VIDEO_FLV)
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
        val factory: DataSource.Factory = if (a.lenientTls) {
            OkHttpDataSource.Factory(Net.lenientClient)
                .setUserAgent(a.ua)
                .setDefaultRequestProperties(a.headers)
                .setTransferListener(counter)
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(a.ua)
                .setAllowCrossProtocolRedirects(true)   // http <-> https khi chuyển hướng
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(a.headers)
                .setTransferListener(counter)
        }
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
        player = p
        view.player = p
        p.setVideoFrameMetadataListener(VideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() })
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { playedOnce = true; reconnects = 0 }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Luồng trực tiếp bị tụt khỏi cửa sổ phát: nhảy về điểm trực tiếp
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare(); return
                }
                // Đang xem được rồi mới rớt mạng: kết nối lại cùng cách, tối đa 3 lần
                if (playedOnce && reconnects < 3) {
                    reconnects++; playedOnce = false
                    view.post { startAttempt() }
                    return
                }
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val status = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                val retryable = status || error.errorCode in 2000..3999
                // Lỗi HTTP: thử cách kế tiếp. Lỗi kết nối/định dạng: bỏ qua các cách chỉ đổi header, nhảy sang cách đổi giao thức.
                val next = if (!retryable) -1
                    else (idx + 1 until attempts.size).firstOrNull { status || attempts[it].protocol } ?: -1
                if (next >= 0) {
                    idx = next
                    Toast.makeText(
                        this@PlayerActivity,
                        "${explainShort(code, error)}. Thử cách ${idx + 1}/${attempts.size}: ${attempts[idx].label}…",
                        Toast.LENGTH_SHORT
                    ).show()
                    view.post { startAttempt() }
                } else if (retryable && srcIdx < sources.size - 1) {
                    Toast.makeText(this@PlayerActivity, "Luồng này lỗi, chuyển sang luồng dự phòng (FLV/HLS)…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch { beginSource(srcIdx + 1) }
                } else {
                    Toast.makeText(this@PlayerActivity, "Không phát được: " + explain(code, error), Toast.LENGTH_LONG).show()
                }
            }
        })
        val uri = if (a.swapScheme) swapScheme(playUrl) else playUrl
        val type = a.forceMime ?: mime
        p.setMediaItem(MediaItem.Builder().setUri(uri).apply { type?.let { setMimeType(it) } }.build())
        p.prepare()
        p.playWhenReady = true
    }

    private fun explainShort(code: Int?, e: PlaybackException): String = when {
        code != null -> "Máy chủ từ chối (HTTP $code)"
        e.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Thiết bị chặn HTTP"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Hết thời gian chờ"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Không kết nối được"
        else -> "Lỗi đọc luồng"
    }

    private fun explain(code: Int?, e: PlaybackException): String = when {
        code == 401 || code == 403 -> "HTTP $code: máy chủ chặn truy cập (nguồn cần Referer/User-Agent riêng hoặc giới hạn IP/thiết bị)"
        code == 404 || code == 410 -> "HTTP $code: link không còn tồn tại hoặc đã hết hạn"
        code != null && code >= 500 -> "HTTP $code: máy chủ phát đang lỗi, thử lại sau"
        code != null -> "HTTP $code"
        e.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "thiết bị chặn kết nối HTTP không mã hoá"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "không kết nối được tới máy chủ phát (đã thử đổi http/https, bỏ kiểm tra TLS, ép HLS/FLV)"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "máy chủ phát không phản hồi (hết thời gian chờ)"
        else -> e.errorCodeName
    }

    override fun onDestroy() {
        ui.removeCallbacks(statsTick)
        player?.release()
        player = null
        super.onDestroy()
    }
}
