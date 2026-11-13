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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
    private class Source(val url: String, val headers: Map<String, String>, val label: String = "")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var tvRes: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvSpeed: TextView
    private val streamChips = mutableListOf<TextView>()
    private lateinit var streamBox: LinearLayout
    private var attempts: List<Attempt> = emptyList()
    private var idx = 0
    private var playUrl = ""
    private var mime: String? = null
    private var sources: List<Source> = emptyList()
    private var srcIdx = 0
    private var listUrl: String? = null
    private var playedOnce = false
    private var reconnects = 0
    private var resumeOnStart = false

    // Chống treo: kẹt ở trạng thái đệm quá lâu mà không báo lỗi => kết nối lại / thử cách khác.
    private val stallWatch = Runnable { onStalled() }

    // Vừa mở một luồng (Auto / HD1 / HD2) mà sau SWITCH_DELAY_MS vẫn chưa phát được hình
    // => tự chuyển sang luồng kế tiếp. Muốn đổi thời gian chờ chỉ cần sửa SWITCH_DELAY_MS.
    private val SWITCH_DELAY_MS = 1_500L
    private val streamNames = listOf("Auto", "HD1", "HD2", "FHD")
    private var armSwitch = false
    private val switchWatch = Runnable { onSwitchTimeout() }

    // Đã xem được rồi thì chịu lỗi tải tạm thời lâu hơn (mất gói, đổi sóng) trước khi coi là lỗi thật;
    // chưa xem được lần nào thì giữ mức thấp để chuyển nhanh sang cách/luồng dự phòng.
    private val retryPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (playedOnce) 8 else 3
    }

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

        // ---- Full-screen player: thông tin phía trên, chọn luồng ở góc dưới bên trái ----
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            useController = true
            setControllerShowTimeoutMs(1000)
            setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
        }
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(10))
            background = Glass.glass(this@PlayerActivity, 16f, tint = 0xD9161D29.toInt(),
                stroke = 0x33FFFFFF, swDp = 1f, sheen = 0x12FFFFFF)
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val tvName = TextView(this).apply {
            text = name
            setTextColor(0xFFF4F6FA.toInt())
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleRow.addView(tvName, LinearLayout.LayoutParams(0, WRAP, 1f))
        val live = TextView(this).apply {
            text = "TRỰC TIẾP"
            setTextColor(0xFFFF7777.toInt())
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = Glass.glass(this@PlayerActivity, 14f, tint = 0x553D2029,
                stroke = 0x664B2832, swDp = 1f, sheen = 0x11FFFFFF)
        }
        titleRow.addView(live)
        header.addView(titleRow)

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        tvRes = statChip("--×--").apply {
            setTextColor(0xFF24D98B.toInt())
            background = Glass.glass(this@PlayerActivity, 13f, tint = 0x3320B77A, stroke = 0x3320B77A)
        }
        tvFps = statChip("-- fps")
        tvSpeed = statChip("Tải -- KB/s")
        listOf(tvRes, tvFps, tvSpeed).forEachIndexed { i, chip ->
            statsRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).also {
                if (i > 0) it.leftMargin = dp(7)
            })
        }
        header.addView(statsRow)
        root.addView(header, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
            .also { it.setMargins(dp(12), 0, dp(12), 0) })   // sát mép trên màn hình (có thể che bảng tỉ số vì chỉ hiện ngắn)

        // Bốn nút chọn nguồn thực tế đặt ở góc dưới bên trái (bên phải bị nút cài đặt của trình phát che).
        streamBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = Glass.glass(this@PlayerActivity, 17f, tint = 0xD9161D29.toInt(),
                stroke = 0x22FFFFFF, swDp = 1f, sheen = 0x10FFFFFF)
        }
        // Lề trái chừa chỗ cho đồng hồ thời gian của trình phát (-00:01 · 00:12) để không bị che.
        root.addView(streamBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.LEFT)
            .also { it.setMargins(dp(180), 0, 0, dp(12)) })

        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            val show = visibility == View.VISIBLE
            header.visibility = if (show) View.VISIBLE else View.GONE
            streamBox.visibility = if (show) View.VISIBLE else View.GONE
        })
        setContentView(root)
        view.showController()

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val own = (intent.getSerializableExtra("headers") as? HashMap<String, String>) ?: HashMap()
        val altUrls = intent.getStringArrayListExtra("alt_urls") ?: arrayListOf()
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val altHeaders = (intent.getSerializableExtra("alt_headers") as? ArrayList<HashMap<String, String>>) ?: arrayListOf()
        listUrl = getSharedPreferences("p", MODE_PRIVATE).getString("url", null)
        val altLabels = intent.getStringArrayListExtra("alt_labels") ?: arrayListOf()
        val list = mutableListOf(makeSource(url, own, intent.getStringExtra("format").orEmpty()))
        altUrls.forEachIndexed { i, u -> list += makeSource(u, altHeaders.getOrNull(i) ?: emptyMap(), altLabels.getOrNull(i).orEmpty()) }
        sources = list.distinctBy { it.url }
        buildStreamChips()

        lifecycleScope.launch { beginSource(0) }
        ui.post(statsTick)
    }

    private fun statChip(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFE8ECF3.toInt())
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = Glass.glass(this@PlayerActivity, 13f, tint = 0x333B4555,
            stroke = 0x223B4555, swDp = 1f, sheen = 0x11FFFFFF)
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
    private fun makeSource(raw: String, base: Map<String, String>, label: String = ""): Source {
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
        return Source(u, headers, label)
    }

    /** Nút đổi luồng (HLS / HLS2 / FLV) trên thanh trạng thái; chỉ hiện khi trận có từ 2 luồng trở lên. */
    private fun buildStreamChips() {
        streamBox.removeAllViews()
        streamChips.clear()
        val labels = streamNames
        labels.forEachIndexed { i, label ->
            val available = i < sources.size
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(8), dp(13), dp(8))
                isEnabled = available
                alpha = if (available) 1f else 0.38f
                setOnClickListener {
                    if (available && i != srcIdx) {
                        view.showController()
                        lifecycleScope.launch { beginSource(i) }
                    }
                }
            }
            streamChips += chip
            streamBox.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).also {
                if (i > 0) it.leftMargin = dp(6)
            })
        }
        highlightStream()
    }

    private fun highlightStream() {
        streamChips.forEachIndexed { i, chip ->
            chip.background = if (i == srcIdx) Glass.accentButton(this, 18f) else Glass.button(this, 18f)
            chip.setTextColor(if (i == srcIdx) Color.WHITE else 0xFFE8ECF3.toInt())
        }
    }

    /** Mở một nguồn (luồng chính hoặc luồng dự phòng): dò link rút gọn rồi bắt đầu thử từ cách đầu tiên. */
    private suspend fun beginSource(i: Int) {
        ui.removeCallbacks(switchWatch)
        srcIdx = i
        if (streamChips.isNotEmpty()) highlightStream()
        idx = 0
        reconnects = 0
        playedOnce = false
        armSwitch = true
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
        ui.removeCallbacks(stallWatch)
        ui.removeCallbacks(switchWatch)
        if (armSwitch) {
            armSwitch = false
            // Chỉ hẹn giờ ở lần mở luồng đầu tiên (không áp dụng khi tự kết nối lại giữa chừng)
            // và chỉ khi còn luồng phía sau để chuyển sang.
            if (srcIdx < sources.size - 1) ui.postDelayed(switchWatch, SWITCH_DELAY_MS)
        }
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

        // Luôn chọn độ phân giải / bitrate cao nhất mà thiết bị giải mã được:
        // bỏ giới hạn theo kích thước màn hình và tắt chuyển chất lượng tự động xuống thấp.
        val trackSelector = DefaultTrackSelector(this)
        val tsb = trackSelector.buildUponParameters()
        tsb.setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
        tsb.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        tsb.setMaxVideoBitrate(Int.MAX_VALUE)
        tsb.setForceHighestSupportedBitrate(true)
        tsb.setExceedVideoConstraintsIfNecessary(true)
        trackSelector.setParameters(tsb.build())
        // Đệm dày hơn mặc định: bắt đầu phát sau ~3s dữ liệu, sau khi đứng hình đợi đủ ~8s mới phát lại
        // để không đứng liên tục; giữ tối đa 60s phía trước (luồng trực tiếp bị giới hạn bởi điểm live).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(25_000, 60_000, 3_000, 8_000)
            .build()
        // Nếu bộ giải mã phần cứng ưu tiên khởi tạo lỗi (thường gặp ở độ phân giải cao) thì tự dùng bộ giải mã khác.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(factory).setLoadErrorHandlingPolicy(retryPolicy)
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)           // giữ Wi-Fi/CPU khi màn hình tắt hoặc TV vào chế độ chờ
            .build()
        player = p
        view.player = p
        p.setVideoFrameMetadataListener(VideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() })
        p.addListener(object : Player.Listener {
            // Luồng 720p / 1080p luôn hiển thị đúng tỉ lệ 16:9 (kể cả khi luồng khai báo tỉ lệ điểm ảnh lạ).
            // Listener này đăng ký sau PlayerView nên giá trị 16:9 được áp dụng sau cùng.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height == 720 || videoSize.height == 1080) {
                    view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(
                        androidx.media3.ui.R.id.exo_content_frame
                    )?.setAspectRatio(16f / 9f)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                ui.removeCallbacks(stallWatch)
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(switchWatch)
                        playedOnce = true; reconnects = 0; view.showController()
                    }
                    Player.STATE_BUFFERING -> ui.postDelayed(stallWatch, if (playedOnce) 15_000L else 30_000L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Luồng trực tiếp bị tụt khỏi cửa sổ phát: nhảy về điểm trực tiếp
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare(); return
                }
                // Đang xem được rồi mới rớt mạng: kết nối lại cùng cách, tối đa 3 lần liên tiếp
                if (playedOnce && reconnects < 3) {
                    reconnects++; playedOnce = false
                    view.post { startAttempt() }
                    return
                }
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val status = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                val retryable = status || error.errorCode in 2000..3999
                advance(code, status, retryable, explainShort(code, error), explain(code, error))
            }
        })
        val uri = if (a.swapScheme) swapScheme(playUrl) else playUrl
        val type = a.forceMime ?: mime
        val item = MediaItem.Builder().setUri(uri)
            .apply { type?.let { setMimeType(it) } }
            // Luồng trực tiếp: giữ khoảng đệm an toàn tối thiểu 8s sau điểm live (không ghi đè nếu luồng yêu cầu lớn hơn)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinOffsetMs(8_000).build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    /** Thử cách kế tiếp -> luồng dự phòng kế tiếp -> báo hết cách. */
    private fun advance(code: Int?, status: Boolean, retryable: Boolean, shortMsg: String, longMsg: String) {
        // Lỗi HTTP: thử cách kế tiếp. Lỗi kết nối/định dạng: bỏ qua các cách chỉ đổi header, nhảy sang cách đổi giao thức.
        val next = if (!retryable) -1
            else (idx + 1 until attempts.size).firstOrNull { status || attempts[it].protocol } ?: -1
        if (next >= 0) {
            idx = next
            Toast.makeText(this, "$shortMsg. Thử cách ${idx + 1}/${attempts.size}: ${attempts[idx].label}…", Toast.LENGTH_SHORT).show()
            view.post { startAttempt() }
        } else if (retryable && srcIdx < sources.size - 1) {
            Toast.makeText(this, "Luồng này lỗi, chuyển sang " + (sources[srcIdx + 1].label.ifBlank { "luồng dự phòng" }) + "…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { beginSource(srcIdx + 1) }
        } else {
            Toast.makeText(this, "Không phát được: $longMsg", Toast.LENGTH_LONG).show()
        }
    }

    /** Hết thời gian chờ mà luồng hiện tại chưa phát được => chuyển sang luồng kế tiếp (Auto -> HD1 -> HD2 -> FHD). */
    private fun onSwitchTimeout() {
        if (playedOnce || srcIdx >= sources.size - 1) return
        val next = srcIdx + 1
        val from = streamNames.getOrElse(srcIdx) { "Luồng ${srcIdx + 1}" }
        val to = streamNames.getOrElse(next) { "Luồng ${next + 1}" }
        Toast.makeText(this, "$from chưa phát được, chuyển sang $to…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch { beginSource(next) }
    }

    /** Kẹt ở trạng thái đệm quá lâu mà không có lỗi: coi như luồng đã treo. */
    private fun onStalled() {
        val p = player ?: return
        if (!p.playWhenReady || p.playbackState != Player.STATE_BUFFERING) return
        if (playedOnce && reconnects < 3) {
            reconnects++; playedOnce = false
            Toast.makeText(this, "Luồng bị đứng, đang kết nối lại…", Toast.LENGTH_SHORT).show()
            startAttempt()
        } else {
            advance(null, false, true, "Luồng không phản hồi", "luồng không phản hồi (hết thời gian chờ)")
        }
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

    // Ra khỏi app thì tạm dừng (đỡ tốn dữ liệu); quay lại thì nhảy về điểm trực tiếp để không bị trễ.
    override fun onStop() {
        player?.let { resumeOnStart = it.playWhenReady; it.playWhenReady = false }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (resumeOnStart) {
            player?.let { if (it.isCurrentMediaItemLive) it.seekToDefaultPosition(); it.playWhenReady = true }
            resumeOnStart = false
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(stallWatch)
        ui.removeCallbacks(switchWatch)
        ui.removeCallbacks(statsTick)
        player?.release()
        player = null
        super.onDestroy()
    }
}
