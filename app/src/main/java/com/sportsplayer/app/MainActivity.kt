package com.sportsplayer.app

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var tvDate: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var content: LinearLayout

    private var all: List<Channel> = emptyList()
    private var lastSig = ""
    private val dateFmt = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi", "VN"))
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Mỗi giây cập nhật đồng hồ; mỗi 30 giây tự lọc lại trận theo giờ hiện tại
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            tvDate.text = dateFmt.format(now)
            tvTime.text = timeFmt.format(now)
            if (now.time / 1000 % 30 == 0L) render()
            ui.postDelayed(this, 1000 - now.time % 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("p", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1115.toInt())
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvDate = TextView(this).apply { setTextColor(0xFFB0B8C4.toInt()); textSize = 14f }
        tvTime = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 30f; setTypeface(typeface, Typeface.BOLD)
        }
        clock.addView(tvDate); clock.addView(tvTime)
        bar.addView(clock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = SwitchCompat(this).apply {
            text = "Lọc theo giờ "; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("filter", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("filter", c).apply(); render(true) }
        }
        val btnLink = Button(this).apply { text = "Nhập link"; setOnClickListener { askLink() } }
        val btnReload = Button(this).apply { text = "Làm mới"; setOnClickListener { load() } }
        bar.addView(sw); bar.addView(btnLink); bar.addView(btnReload)
        root.addView(bar)

        tvStatus = TextView(this).apply { setTextColor(0xFF9AA3B0.toInt()); textSize = 12f; setPadding(0, dp(6), 0, 0) }
        root.addView(tvStatus)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load()
    }

    override fun onResume() { super.onResume(); ui.post(tick); render() }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun askLink() {
        val et = EditText(this).apply {
            hint = Config.LINK_HINT
            setText(prefs.getString("url", Config.DEFAULT_SOURCE_URL))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Link m3u8 / JSON")
            .setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.edit().putString("url", et.text.toString().trim()).apply()
                load()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun load() {
        val url = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: Config.DEFAULT_SOURCE_URL
        if (url.isBlank()) {
            tvStatus.text = "Chưa có link. Bấm “Nhập link” để bắt đầu."
            askLink(); return
        }
        tvStatus.text = "Đang tải danh sách…"
        lifecycleScope.launch {
            try {
                all = SourceLoader.load(url)
                lastSig = ""
                render(true)
            } catch (e: Exception) {
                tvStatus.text = "Lỗi tải link: ${e.message}"
            }
        }
    }

    private fun render(force: Boolean = false) {
        if (all.isEmpty()) { if (force) content.removeAllViews(); return }
        val now = System.currentTimeMillis()
        val filter = prefs.getBoolean("filter", true)
        val items = all.map { it to it.state(now) }.filter { !filter || it.second != State.ENDED }
        val sig = filter.toString() + items.joinToString("|") { it.first.url + it.second }
        if (!force && sig == lastSig) return
        lastSig = sig

        content.removeAllViews()
        tvStatus.text = "Đang hiển thị ${items.size}/${all.size} kênh"
        val rank = mapOf(State.LIVE to 0, State.ALWAYS to 1, State.UPCOMING to 2, State.ENDED to 3)
        items.groupBy { it.first.group }.forEach { (g, list) ->
            content.addView(TextView(this).apply {
                text = g; setTextColor(Color.WHITE); textSize = 18f
                setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(6))
            })
            val sorted = list.sortedWith(
                compareBy<Pair<Channel, State>>({ rank[it.second]!! }, { it.first.start ?: 0L })
            )
            content.addView(RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
                adapter = ChannelAdapter(sorted) { openPlayer(it) }
            })
        }
    }

    private fun openPlayer(ch: Channel) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", ch.url).putExtra("name", ch.name))
    }
}
