package com.mhd.sports

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var tvDate: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var content: LinearLayout
    private lateinit var btnReload: ImageView
    private var spinAnim: ObjectAnimator? = null
    private lateinit var pillUpdate: TextView
    private var lastUpdateCheckAt = 0L
    private val sportChips = mutableMapOf<Sport?, TextView>()

    private var all: List<Channel> = emptyList()
    private var lastSig = ""
    private var secs = 0
    private var loadingData = false
    private var lastDataRefreshAt = 0L
    private var loadedStationNames: List<String> = emptyList()
    private var pending: Updater.Release? = null
    private var downloading = false
    private val adapters = mutableListOf<ChannelAdapter>()

    private val dateFmt = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi", "VN"))
    private val dateFmtShort = SimpleDateFormat("EEE, dd/MM", Locale("vi", "VN"))
    /** Màn hình hẹp (điện thoại dọc): thu nhỏ thanh trên cùng cho vừa. */
    private val compact get() = resources.configuration.screenWidthDp < 520
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Mỗi giây: cập nhật đồng hồ, lọc lại trận theo giờ, đếm phút:giây trận đang phát
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            tvDate.text = (if (compact) dateFmtShort else dateFmt).format(now)
            tvTime.text = timeFmt.format(now)
            render()
            secs++
            if (secs % 30 == 0) refreshDataIfDue()          // tự kiểm tra playlist mỗi 30 giây; tải lại khi đủ 2 phút
            if (secs % 600 == 0) checkUpdate()              // 10 phút kiểm tra bản APK mới một lần
            ui.postDelayed(this, 1000 - now.time % 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("p", MODE_PRIVATE)

        val root = FrameLayout(this)
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.bg_stadium); scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x40000000, 0xCC000000.toInt())
            )
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }

        // ---- thanh trên: [logo + tên] ........ [đồng hồ / ngày] [làm mới] [cài đặt] ----
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.glass(this@MainActivity, 24f)
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        val logoSize = if (compact) 40 else 54
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = Config.APP_NAME
        }
        Glass.clipRound(logo, 12f)
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(logo, LinearLayout.LayoutParams(dp(logoSize), dp(logoSize)))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val spaceAt = Config.APP_NAME.indexOf(' ')
        val nameSpan = SpannableString(Config.APP_NAME).apply {
            if (spaceAt > 0) {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, spaceAt, 0)
                setSpan(ForegroundColorSpan(Glass.AMBER), spaceAt + 1, length, 0)
            } else setSpan(ForegroundColorSpan(Color.WHITE), 0, length, 0)
        }
        brandText.addView(TextView(this).apply {
            text = nameSpan; textSize = if (compact) 18f else 24f
            setTypeface(typeface, Typeface.BOLD); maxLines = 1
        })
        if (!compact) brandText.addView(TextView(this).apply {
            text = Config.APP_TAGLINE; setTextColor(Glass.TEXT2); textSize = 12f
        })
        brand.addView(brandText, LinearLayout.LayoutParams(WRAP, WRAP).also { it.leftMargin = dp(10) })
        bar.addView(brand, LinearLayout.LayoutParams(0, WRAP, 1f))

        // đồng hồ + ngày: căn phải
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        tvDate = TextView(this).apply {
            setTextColor(Glass.AMBER); textSize = if (compact) 11f else 13f; gravity = Gravity.END; maxLines = 1
        }
        tvTime = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = if (compact) 22f else 30f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; maxLines = 1
        }
        clock.addView(tvDate); clock.addView(tvTime)
        bar.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP).also { it.rightMargin = dp(if (compact) 8 else 16) })

        // hai nút biểu tượng cạnh nhau ở góc phải trên cùng
        val iconSize = dp(if (compact) 40 else 46)
        btnReload = iconButton(R.drawable.ic_refresh, "Làm mới") { load(); checkUpdate(manual = false) }
        val btnSettings = iconButton(R.drawable.ic_settings, "Cài đặt") { openSettings() }
        bar.addView(btnReload, LinearLayout.LayoutParams(iconSize, iconSize))
        bar.addView(btnSettings, LinearLayout.LayoutParams(iconSize, iconSize).also { it.leftMargin = dp(8) })
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- lọc theo bộ môn: căn giữa khung ----
        val sportRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun addSportChip(sport: Sport?, label: String) {
            val chip = Glass.chip(this, label, 13f).apply { setOnClickListener { selectSport(sport) } }
            sportChips[sport] = chip
            sportRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).also { it.setMargins(dp(4), 0, dp(4), 0) })
        }
        addSportChip(null, "Tất cả")
        Sport.values().forEach { addSportChip(it, it.label) }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true     // đủ chỗ: dàn đều và căn giữa; hẹp quá: vuốt ngang
            addView(sportRow, FrameLayout.LayoutParams(WRAP, MATCH))
        }, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(10) })
        styleSportChips()

        tvStatus = TextView(this).apply {
            setTextColor(Glass.TEXT2); textSize = 12f; setPadding(dp(6), dp(8), 0, dp(2))
        }
        col.addView(tvStatus)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(80)) }
        col.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- thông báo bản mới: chỉ hiện ở góc khi có cập nhật ----
        pillUpdate = Glass.chip(this, "Có bản mới · Cập nhật").apply {
            background = Glass.accentButton(this@MainActivity)
            visibility = View.GONE
            setOnClickListener { pending?.let { if (!downloading) confirmUpdate(it) } }
        }
        root.addView(pillUpdate, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
            .also { it.setMargins(0, 0, dp(18), dp(18)) })

        setContentView(root)
        load()
        checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
        refreshDataIfDue()
        // quay lại ứng dụng: kiểm tra bản APK mới (cách nhau tối thiểu 60 giây để khỏi gọi GitHub liên tục)
        if (System.currentTimeMillis() - lastUpdateCheckAt > 60_000L) checkUpdate()
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun currentSport(): Sport? {
        val name = prefs.getString("sport", "") ?: ""
        return Sport.values().firstOrNull { it.name == name }
    }

    private fun selectSport(sport: Sport?) {
        prefs.edit().putString("sport", sport?.name ?: "").apply()
        styleSportChips()
        render(true)
    }

    private fun styleSportChips() {
        val cur = currentSport()
        sportChips.forEach { (sport, chip) ->
            chip.background = if (sport == cur) Glass.accentButton(this) else Glass.button(this)
        }
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        contentDescription = desc
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(10); setPadding(pad, pad, pad, pad)
        isFocusable = true; isClickable = true
        background = Glass.button(this@MainActivity, 23f)
        setOnClickListener { onClick() }
    }

    private fun spinReload(on: Boolean) {
        spinAnim?.cancel(); spinAnim = null
        if (on) {
            spinAnim = ObjectAnimator.ofFloat(btnReload, View.ROTATION, 0f, 360f).apply {
                duration = 900; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
            }
        } else btnReload.rotation = 0f
    }

    // ---------- cài đặt: lọc theo giờ / sắp xếp / nhập link / cập nhật ----------
    private fun openSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        lateinit var dialog: AlertDialog
        fun row(text: String, accent: Boolean = false, action: (TextView) -> Unit): TextView {
            val t = TextView(this).apply {
                this.text = text; setTextColor(Color.WHITE); textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(13), dp(16), dp(13))
                isFocusable = true; isClickable = true
                background = if (accent) Glass.accentButton(this@MainActivity, 16f) else Glass.button(this@MainActivity, 16f)
                setOnClickListener { action(this) }
            }
            box.addView(t, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(8) })
            return t
        }
        fun filterText() = if (prefs.getBoolean("filter", true)) "Lọc theo giờ: Bật" else "Lọc theo giờ: Tắt"
        row(filterText(), accent = prefs.getBoolean("filter", true)) { t ->
            val on = !prefs.getBoolean("filter", true)
            prefs.edit().putBoolean("filter", on).apply()
            t.text = filterText()
            t.background = if (on) Glass.accentButton(this, 16f) else Glass.button(this, 16f)
            render(true)
        }
        row("Sắp xếp, ẩn/hiện nhóm") { dialog.dismiss(); askOrder() }
                val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        row("Kiểm tra bản cập nhật  ·  đang dùng $ver") { dialog.dismiss(); checkUpdate(manual = true) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Cài đặt")
            .setView(box)
            .setNegativeButton("Đóng", null)
            .showGlass()
    }

    private fun AlertDialog.Builder.showGlass() = create().also { d ->
        d.show()
        d.window?.setBackgroundDrawable(Glass.glass(this@MainActivity, 24f, tint = 0xF2101012.toInt()))
    }

    // ---------- link ----------
    private fun askLink() {
        // Link có sẵn của ứng dụng không hiện trong ô nhập; chỉ hiện link do chính người dùng đã lưu.
        val saved = prefs.getString("url", null)?.trim().orEmpty()
        val et = EditText(this).apply {
            hint = Config.LINK_HINT
            setText(if (saved.isEmpty() || saved == Config.DEFAULT_SOURCE_URL) "" else saved)
            setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập link của bạn")
            .setMessage("Để trống để dùng nguồn có sẵn của ứng dụng.")
            .setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                val value = et.text.toString().trim()
                if (value.isEmpty()) prefs.edit().remove("url").apply()
                else prefs.edit().putString("url", value).apply()
                lastSig = ""
                load()
            }
            .setNegativeButton("Huỷ", null)
            .showGlass()
    }

    private fun refreshDataIfDue() {
        val interval = Config.DATA_REFRESH_MIN * 60_000L
        if (!loadingData && (lastDataRefreshAt == 0L || System.currentTimeMillis() - lastDataRefreshAt >= interval)) {
            load(silent = true)
        }
    }

    private fun load(silent: Boolean = false) {
        if (loadingData) return
        // Xóa link tùy chỉnh cũ để luôn sử dụng playlist M3U cố định của người dùng.
        prefs.edit().remove("url").apply()
        val url = Config.DEFAULT_SOURCE_URL
        loadingData = true
        if (!silent) spinReload(true)
        if (!silent || all.isEmpty()) tvStatus.text = "Đang tải dữ liệu nhà đài…"
        lifecycleScope.launch {
            try {
                val result = SourceLoader.loadCatalog(url)
                if (result.channels.isEmpty()) error("Nguồn không có trận đấu hoặc kênh phát hợp lệ")
                all = result.channels
                loadedStationNames = result.stationNames
                lastDataRefreshAt = System.currentTimeMillis()
                lastSig = ""
                render(true)
                tvStatus.text = "Đã cập nhật ${all.size} trận/kênh · ${result.stationCount} nhà đài" +
                    (if (result.failedStations.isNotEmpty()) " · ${result.failedStations.size} nguồn tạm lỗi" else "") +
                    " · Tự làm mới mỗi ${Config.DATA_REFRESH_MIN} phút"
            } catch (e: Exception) {
                if (all.isEmpty()) tvStatus.text = "Lỗi tải dữ liệu: ${Config.scrub(e.message)}"
                else tvStatus.text = "Không cập nhật được · Đang dùng dữ liệu đã tải trước đó (${Config.scrub(e.message)})"
            } finally {
                loadingData = false
                spinReload(false)
            }
        }
    }

    // ---------- hiển thị ----------
    private fun savedOrder() = (prefs.getString("order", "") ?: "").split("\n").filter { it.isNotBlank() }
    private fun favs(): Set<String> = prefs.getStringSet("fav", emptySet()) ?: emptySet()
    private fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()

    /** Nhóm ghim lên đầu, sau đó theo thứ tự người dùng đã sắp; nhóm mới xuất hiện ở cuối. */
    private fun orderedGroups(groups: List<String>): List<String> {
        val o = savedOrder(); val f = favs()
        return groups.sortedWith(compareBy<String>(
            { if (it in f) 0 else 1 },
            { o.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        ))
    }

    private fun render(force: Boolean = false) {
        if (all.isEmpty()) { if (force) content.removeAllViews(); return }
        val now = System.currentTimeMillis()
        val filter = prefs.getBoolean("filter", true)
        val hid = hidden()
        val sport = currentSport()
        val items = all.map { it to it.state(now) }
            .filter { (!filter || it.second != State.ENDED) && it.first.group !in hid }
            .filter { sport == null || it.first.sport == sport }
        val sig = filter.toString() + (sport?.name ?: "") + hid.sorted().joinToString(",") + "#" +
            items.joinToString("|") { it.first.name + it.first.url + it.first.detail + it.second }
        if (!force && sig == lastSig) {
            adapters.forEach { it.tickTimers() }   // chỉ đếm giờ, không dựng lại
            return
        }
        lastSig = sig

        content.removeAllViews(); adapters.clear()
        tvStatus.text = when {
            items.isEmpty() && sport != null -> "Chưa có trận/kênh môn ${sport.label}. Chọn “Tất cả” để xem đầy đủ."
            items.isEmpty() && hid.isNotEmpty() -> "Tất cả nhóm đang ẩn. Mở “Sắp xếp” để hiện lại."
            else -> "Đang hiển thị ${items.size}/${all.size} trận/kênh · ${items.map { it.first.group }.distinct().size} nhà đài" +
                if (hid.isNotEmpty()) " · ${hid.size} nhóm đang ẩn" else ""
        }
        val rank = mapOf(State.LIVE to 0, State.ALWAYS to 1, State.UPCOMING to 2, State.ENDED to 3)
        val grouped = items.groupBy { it.first.group }
        orderedGroups(grouped.keys.toList()).forEach { g ->
            val list = grouped.getValue(g)
            val head = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(16), 0, dp(8))
            }
            head.addView(View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Glass.AMBER, Glass.ORANGE)
                ).also { it.cornerRadius = dp(2).toFloat() }
            }, LinearLayout.LayoutParams(dp(4), dp(20)))
            val stationLogo = list.firstOrNull { !it.first.groupLogo.isNullOrBlank() }?.first?.groupLogo
            if (stationLogo != null) {
                val logoView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                logoView.load(stationLogo)
                head.addView(logoView, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.leftMargin = dp(9) })
            }
            head.addView(TextView(this).apply {
                text = g; setTextColor(Color.WHITE); textSize = 18f
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(if (stationLogo == null) 10 else 8), 0, 0, 0)
            })
            content.addView(head)

            val sorted = list.sortedWith(
                compareBy<Pair<Channel, State>>({ rank[it.second]!! }, { it.first.start ?: 0L })
            )
            val ad = ChannelAdapter(sorted) { openPlayer(it) }
            adapters += ad
            content.addView(RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
                adapter = ad
                itemAnimator = null
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
            })
        }
    }

    // ---------- sắp xếp / ghim / ẩn-hiện nhóm ----------
    private fun askOrder() {
        if (all.isEmpty()) { tvStatus.text = "Chưa có danh sách để sắp xếp"; return }
        val fav = favs().toMutableSet()
        val hid = hidden().toMutableSet()
        var cur = orderedGroups(all.map { it.group }.distinct())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun rebuild() {
            box.removeAllViews()
            cur.forEachIndexed { i, g ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Glass.glass(this@MainActivity, 16f, tint = 0x40000000)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                item.addView(TextView(this).apply {
                    text = (if (g in fav) "[Ghim] " else "") + g + (if (g in hid) "  (đang ẩn)" else "")
                    setTextColor(if (g in hid) 0x80FFFFFF.toInt() else Color.WHITE)
                    textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                })
                val row = LinearLayout(this).apply { setPadding(0, dp(6), 0, 0) }
                fun add(t: String, a: () -> Unit) {
                    row.addView(Glass.chip(this@MainActivity, t, 12f).apply { setOnClickListener { a() } },
                        LinearLayout.LayoutParams(WRAP, WRAP).also { it.rightMargin = dp(6) })
                }
                add(if (g in hid) "Hiện" else "Ẩn") {
                    if (g in hid) hid.remove(g) else hid.add(g)
                    rebuild()
                }
                add(if (g in fav) "Bỏ ghim" else "Ghim") {
                    if (g in fav) fav.remove(g) else fav.add(g)
                    cur = cur.sortedBy { if (it in fav) 0 else 1 }
                    rebuild()
                }
                add("Lên") {
                    if (i > 0) { cur = cur.toMutableList().also { it.add(i - 1, it.removeAt(i)) }; rebuild() }
                }
                add("Xuống") {
                    if (i < cur.size - 1) { cur = cur.toMutableList().also { it.add(i + 1, it.removeAt(i)) }; rebuild() }
                }
                item.addView(row)
                box.addView(item, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(8) })
            }
        }
        rebuild()
        AlertDialog.Builder(this)
            .setTitle("Sắp xếp, ẩn/hiện nhóm")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Xong") { _, _ ->
                prefs.edit().putString("order", cur.joinToString("\n"))
                    .putStringSet("fav", HashSet(fav))
                    .putStringSet("hidden", HashSet(hid)).apply()
                lastSig = ""; render(true)
            }
            .setNegativeButton("Huỷ", null)
            .showGlass()
    }

    // ---------- cập nhật: chỉ hiện thông báo ở góc khi có bản mới ----------
    /** Hộp thoại kết quả cho lần kiểm tra thủ công: luôn thấy được, không phụ thuộc dòng trạng thái nhỏ. */
    private fun showCheckResult(message: String) {
        tvStatus.text = message
        AlertDialog.Builder(this)
            .setTitle("Kiểm tra cập nhật")
            .setMessage(message)
            .setPositiveButton("Đóng", null)
            .showGlass()
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "Không kết nối được tới GitHub. Kiểm tra lại mạng rồi thử lại."
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
            "GitHub phản hồi quá lâu (hết thời gian chờ). Thử lại sau."
        else -> Config.scrub(e.message)
    }

    /** manual = true: luôn báo kết quả (kể cả lỗi) bằng hộp thoại; tự động: chỉ hiện khi có bản mới. */
    private fun checkUpdate(manual: Boolean = false) {
        if (downloading) {
            if (manual) android.widget.Toast.makeText(this, "Đang tải bản cập nhật…", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lastUpdateCheckAt = System.currentTimeMillis()
        if (manual) {
            tvStatus.text = "Đang kiểm tra bản cập nhật…"
            android.widget.Toast.makeText(this, "Đang kiểm tra bản cập nhật…", android.widget.Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            try {
                val r = Updater.latest()
                val local = Updater.localCode(this@MainActivity)
                if (r != null && r.code > local) {
                    pending = r
                    pillUpdate.text = "Có bản mới · Cập nhật"
                    pillUpdate.visibility = View.VISIBLE
                    if (manual) tvStatus.text = "Có bản mới: build ${r.code} (đang dùng build $local)"
                    // Hiện hộp thoại một lần cho mỗi bản mới để người dùng thấy ngay, không chỉ ở nút nhỏ góc dưới
                    if (manual || prefs.getInt("notified_build", 0) != r.code) {
                        prefs.edit().putInt("notified_build", r.code).apply()
                        confirmUpdate(r)
                    }
                } else {
                    pending = null
                    pillUpdate.visibility = View.GONE
                    if (manual) showCheckResult(
                        if (r == null) "Chưa có bản phát hành nào có file APK trên GitHub.\nKiểm tra repo đã chạy Actions xong và có Release chứa file .apk chưa."
                        else "Bạn đang dùng bản mới nhất (build $local)."
                    )
                }
            } catch (e: Exception) {
                if (manual) showCheckResult("Không kiểm tra được bản cập nhật:\n${friendlyError(e)}")
            }
        }
    }

    private fun confirmUpdate(r: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("Có bản mới: build ${r.code}")
            .setMessage(r.notes.take(400).ifBlank { "Bản cập nhật mới đã sẵn sàng." })
            .setPositiveButton("Cập nhật ngay") { _, _ -> doUpdate(r) }
            .setNegativeButton("Để sau", null)
            .showGlass()
    }

    private fun doUpdate(r: Updater.Release) {
        downloading = true
        pillUpdate.text = "Đang tải 0%"
        lifecycleScope.launch {
            try {
                val f = Updater.download(this@MainActivity, r.apkUrl) { pillUpdate.text = "Đang tải $it%" }
                pillUpdate.text = if (Updater.install(this@MainActivity, f)) "Có bản mới · Cập nhật"
                else "Bật quyền cài đặt rồi bấm lại"
            } catch (e: Exception) {
                pillUpdate.text = "Lỗi tải, bấm để thử lại"
            } finally {
                downloading = false
            }
        }
    }

    private fun openPlayer(ch: Channel) {
        val options = ch.streams
        if (options.isEmpty() && ch.url.isBlank()) {
            android.widget.Toast.makeText(this, "Trận đấu chưa có luồng phát.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (options.size > 1) {
            val labels = options.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("${ch.name}\nChọn bình luận viên / chất lượng")
                .setItems(labels) { _, which -> playStream(ch, options[which]) }
                .setNegativeButton("Đóng", null)
                .showGlass()
        } else if (options.size == 1) {
            playStream(ch, options[0])
        } else {
            playStream(ch, StreamOption("Phát", ch.url, ch.headers))
        }
    }

    private fun playStream(ch: Channel, option: StreamOption) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", option.url).putExtra("name", ch.name)
            .putExtra("headers", HashMap(option.headers))
            .putExtra("format", option.format)
            .putStringArrayListExtra("alt_labels", ArrayList(option.fallbacks.map { it.label }))
            .putStringArrayListExtra("alt_urls", ArrayList(option.fallbacks.map { it.url }))
            .putExtra("alt_headers", ArrayList(option.fallbacks.map { HashMap(it.headers) })))
    }
}
