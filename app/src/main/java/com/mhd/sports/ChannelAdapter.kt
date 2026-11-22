package com.mhd.sports

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import okhttp3.Headers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Match card: two team crests, VS, team names, league and kickoff. */
class ChannelAdapter(
    private val items: List<Pair<Channel, State>>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(
        val card: LinearLayout,
        val homeImg: ImageView,
        val awayImg: ImageView,
        val homeName: TextView,
        val awayName: TextView,
        val badge: TextView,
        val league: TextView,
        val kickoff: TextView
    ) : RecyclerView.ViewHolder(card)

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val day = SimpleDateFormat("dd/MM", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = RecyclerView.LayoutParams(dp(244), dp(174)).also { it.rightMargin = dp(10) }
            // Android TV: làm nổi bật rõ thẻ đang được remote chọn.
            // Trước đây thẻ chỉ có nền/viền cố định nên trạng thái focus khó nhận biết.
            fun cardShape(fill: Int, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(fill)
                setStroke(dp(strokeWidth), stroke)
            }
            background = StateListDrawable().apply {
                val focused = cardShape(0xFF25496D.toInt(), 0xFFFFB347.toInt(), 4)
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), cardShape(0xE9081B3A.toInt(), 0xFF16A9FF.toInt(), 1))
            }
        }

        val crestRow = FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88))
        }
        fun crest(): ImageView = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF10294D.toInt())
            }
        }
        val home = crest()
        val away = crest()
        crestRow.addView(home, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.START or Gravity.CENTER_VERTICAL).also { it.leftMargin = dp(12) })
        crestRow.addView(away, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.END or Gravity.CENTER_VERTICAL).also { it.rightMargin = dp(12) })
        val vs = text(15f, 0xFFFFFFFF.toInt(), true).apply { text = "VS" }
        crestRow.addView(vs, FrameLayout.LayoutParams(dp(48), dp(66), Gravity.CENTER))

        val badge = text(10f, Color.WHITE, true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFFF5A00.toInt()) }
        }
        crestRow.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP))

        val names = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
        }
        val homeName = text(11f, Color.WHITE, true).apply { setPadding(dp(2), 0, dp(2), 0) }
        val awayName = text(11f, Color.WHITE, true).apply { setPadding(dp(2), 0, dp(2), 0) }
        names.addView(homeName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        names.addView(awayName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val divider = View(c).apply {
            setBackgroundColor(0x557BBCEB)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(3); it.bottomMargin = dp(4) }
        }
        val footer = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26))
        }
        val league = text(10f, 0xFFDDEBFF.toInt(), true).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; maxLines = 1 }
        val kickoff = text(10f, 0xFFFFD36A.toInt(), true).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; maxLines = 1 }
        footer.addView(league, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        footer.addView(kickoff, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        card.addView(crestRow)
        card.addView(names)
        card.addView(divider)
        card.addView(footer)
        return VH(card, home, away, homeName, awayName, badge, league, kickoff)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val (ch, state) = items[position]
        val teams = ch.name.substringBefore('|').split(Regex("\\s+vs\\s+", RegexOption.IGNORE_CASE), limit = 2)
        h.homeName.text = ch.homeName?.takeIf { it.isNotBlank() }
            ?: teams.getOrNull(0)?.trim().orEmpty().ifBlank { ch.name.take(24) }
        h.awayName.text = ch.awayName?.takeIf { it.isNotBlank() }
            ?: teams.getOrNull(1)?.trim().orEmpty()
        h.homeImg.dispose(); h.awayImg.dispose()
        h.homeImg.setImageDrawable(null); h.awayImg.setImageDrawable(null)
        // Logo có thể nằm trên máy chủ yêu cầu Referer giống nguồn M3U.
        // Dùng lại header đã đọc từ #EXTVLCOPT (ví dụ http-referrer của Bóng 69).
        fun loadTeamLogo(view: ImageView, url: String?, requestHeaders: Map<String, String>) {
            url?.takeIf { it.startsWith("http", true) }?.let { imageUrl ->
                val imageHeaders = Headers.Builder().apply {
                    add("User-Agent", Net.UA)
                    requestHeaders.forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) {
                            removeAll(name)
                            add(name, value)
                        }
                    }
                }.build()
                view.load(imageUrl) {
                    crossfade(true)
                    headers(imageHeaders)
                }
            }
        }
        loadTeamLogo(h.homeImg, ch.homeLogo, ch.headers)
        loadTeamLogo(h.awayImg, ch.awayLogo, ch.headers)

        val leagueName = ch.detail?.substringBefore("·")?.trim().orEmpty()
        h.league.text = leagueName.ifBlank { ch.sport?.label ?: ch.group }
        h.kickoff.text = ch.start?.let { "${hm.format(Date(it))} · ${day.format(Date(it))}" } ?: ""

        when (state) {
            State.LIVE -> {
                h.badge.text = "● TRỰC TIẾP"
                h.badge.background = GradientDrawable().apply { cornerRadius = 30f; setColor(0xFFFF5200.toInt()) }
                h.badge.visibility = View.VISIBLE
            }
            State.UPCOMING -> {
                h.badge.text = ch.start?.let { hm.format(Date(it)) } ?: "SẮP DIỄN RA"
                h.badge.background = GradientDrawable().apply { cornerRadius = 30f; setColor(0xFF0757B8.toInt()) }
                h.badge.visibility = View.VISIBLE
            }
            State.ENDED -> {
                h.badge.text = "ĐÃ KẾT THÚC"
                h.badge.background = GradientDrawable().apply { cornerRadius = 30f; setColor(0xFF596579.toInt()) }
                h.badge.visibility = View.VISIBLE
            }
            State.ALWAYS -> h.badge.visibility = View.GONE
        }
        h.card.setOnClickListener { onClick(ch) }
    }

    override fun getItemCount() = items.size
}
