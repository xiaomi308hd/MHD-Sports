package com.mhd.sports

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private lateinit var chipFilter: TextView
    private lateinit var pillUpdate: TextView

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
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Mỗi giây: cập nhật đồng hồ, lọc lại trận theo giờ, đếm phút:giây trận đang phát
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            tvDate.text = dateFmt.format(now)
            tvTime.text = timeFmt.format(now)
            render()
            if (++secs % 300 == 0) refreshDataIfDue()
            if (secs % 1800 == 0) checkUpdate()
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
            setPadding(dp(18), dp(14), dp(18), 0)
        }

        // ---- thanh trên: đồng hồ + nút chức năng (kính) ----
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.glass(this@MainActivity, 24f)
            setPadding(dp(18), dp(10), dp(12), dp(10))
        }
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvDate = TextView(this).apply { setTextColor(Glass.AMBER); textSize = 13f }
        tvTime = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 30f; setTypeface(typeface, Typeface.BOLD)
        }
        clock.addView(tvDate); clock.addView(tvTime)
        bar.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))

        chipFilter = Glass.chip(this, "")
        chipFilter.setOnClickListener {
            prefs.edit().putBoolean("filter", !prefs.getBoolean("filter", true)).apply()
            styleFilter(); render(true)
        }
        styleFilter()
        val chipOrder = Glass.chip(this, "Sắp xếp").apply { setOnClickListener { askOrder() } }
        val chipLink = Glass.chip(this, "Nhập link").apply { setOnClickListener { askLink() } }
        val chipReload = Glass.chip(this, "Làm mới").apply { setOnClickListener { load() } }
        val chips = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        listOf(chipFilter, chipOrder, chipLink, chipReload).forEach {
            chips.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).also { p -> p.leftMargin = dp(8) })
        }
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true
            addView(chips, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

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
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun styleFilter() {
        val on = prefs.getBoolean("filter", true)
        chipFilter.text = if (on) "Lọc theo giờ: Bật" else "Lọc theo giờ: Tắt"
        chipFilter.background = if (on) Glass.accentButton(this) else Glass.button(this)
    }

    private fun AlertDialog.Builder.showGlass() = create().also { d ->
        d.show()
        d.window?.setBackgroundDrawable(Glass.glass(this@MainActivity, 24f, tint = 0xF2101012.toInt()))
    }

    // ---------- link ----------
    private fun askLink() {
        val et = EditText(this).apply {
            hint = Config.LINK_HINT
            setText(prefs.getString("url", Config.DEFAULT_SOURCE_URL))
            setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Link index.json / JSON / M3U")
            .setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.edit().putString("url", et.text.toString().trim()).apply()
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
        val savedUrl = prefs.getString("url", null)?.takeIf { it.isNotBlank() }
        // Chuyển link sport.json cũ của Api-JK sang index.json để kích hoạt danh mục nhiều nhà đài.
        val isOldApiJkSportLink = savedUrl != null && savedUrl.contains("Api-JK", true) &&
            savedUrl.substringBefore("?").endsWith("/sport.json", true)
        val url = if (savedUrl == null || isOldApiJkSportLink) {
            prefs.edit().putString("url", Config.DEFAULT_SOURCE_URL).apply()
            Config.DEFAULT_SOURCE_URL
        } else savedUrl
        if (url.isBlank()) {
            tvStatus.text = "Chưa có link. Bấm “Nhập link” để bắt đầu."
            if (!silent) askLink()
            return
        }
        loadingData = true
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
                tvStatus.text = "Đã cập nhật ${all.size} trận/kênh · ${result.stationCount} nhà đài · Tự làm mới mỗi ${Config.DATA_REFRESH_MIN} phút"
            } catch (e: Exception) {
                if (all.isEmpty()) tvStatus.text = "Lỗi tải dữ liệu: ${e.message}"
                else tvStatus.text = "Không cập nhật được · Đang dùng dữ liệu đã tải trước đó (${e.message})"
            } finally {
                loadingData = false
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
        val items = all.map { it to it.state(now) }
            .filter { (!filter || it.second != State.ENDED) && it.first.group !in hid }
        val sig = filter.toString() + hid.sorted().joinToString(",") + "#" +
            items.joinToString("|") { it.first.name + it.first.url + it.first.detail + it.second }
        if (!force && sig == lastSig) {
            adapters.forEach { it.tickTimers() }   // chỉ đếm giờ, không dựng lại
            return
        }
        lastSig = sig

        content.removeAllViews(); adapters.clear()
        tvStatus.text = when {
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
    private fun checkUpdate() {
        if (downloading) return
        lifecycleScope.launch {
            try {
                val r = Updater.latest()
                if (r != null && r.code > Updater.localCode(this@MainActivity)) {
                    pending = r
                    pillUpdate.text = "Có bản mới · Cập nhật"
                    pillUpdate.visibility = View.VISIBLE
                } else {
                    pending = null
                    pillUpdate.visibility = View.GONE
                }
            } catch (_: Exception) { /* im lặng: không có mạng / chưa cấu hình repo */ }
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
                .setItems(labels) { _, which -> playStream(ch, options[which].url, options[which].headers) }
                .setNegativeButton("Đóng", null)
                .showGlass()
        } else if (options.size == 1) {
            playStream(ch, options[0].url, options[0].headers)
        } else {
            playStream(ch, ch.url, ch.headers)
        }
    }

    private fun playStream(ch: Channel, url: String, headers: Map<String, String>) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", url).putExtra("name", ch.name)
            .putExtra("headers", HashMap(headers)))
    }
}
