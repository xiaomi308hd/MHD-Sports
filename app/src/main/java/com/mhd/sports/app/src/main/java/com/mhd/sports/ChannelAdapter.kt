package com.mhd.sports

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChannelAdapter(
    private val items: List<Pair<Channel, State>>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(
        val card: LinearLayout, val img: ImageView, val badge: TextView,
        val title: TextView, val sub: TextView,
        val teams: LinearLayout, val homeImg: ImageView, val awayImg: ImageView
    ) : RecyclerView.ViewHolder(card)

    private val fmt = SimpleDateFormat("HH:mm · dd/MM", Locale.getDefault())
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(6), dp(6), dp(6), dp(8))
            layoutParams = RecyclerView.LayoutParams(dp(236), ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.rightMargin = dp(12) }
            background = Glass.card(c)
        }
        val thumb = FrameLayout(c)
        Glass.clipRound(thumb, 13f)
        val img = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF14110E.toInt())
        }
        thumb.addView(img, FrameLayout.LayoutParams(dp(224), dp(126)))

        // Lớp hiển thị riêng logo đội nhà / đội khách cho dữ liệu trận đấu.
        val teams = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
            setBackgroundColor(0xFF171A20.toInt())
            visibility = View.GONE
        }
        fun teamImage(): ImageView = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF252A33.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(70))
        }
        val homeImg = teamImage()
        val awayImg = teamImage()
        val vs = TextView(c).apply {
            text = "VS"
            setTextColor(0xFFFFA33A.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(70))
        }
        teams.addView(homeImg)
        teams.addView(vs)
        teams.addView(awayImg)
        thumb.addView(teams, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val badge = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            compoundDrawablePadding = dp(5)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        thumb.addView(badge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).also { it.setMargins(0, dp(7), dp(7), 0) })
        card.addView(thumb)

        val title = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            ellipsize = TextUtils.TruncateAt.END; setPadding(dp(4), dp(7), dp(4), 0)
        }
        val sub = TextView(c).apply { textSize = 12f; setPadding(dp(4), dp(2), dp(4), 0) }
        card.addView(title); card.addView(sub)
        return VH(card, img, badge, title, sub, teams, homeImg, awayImg)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val (ch, st) = items[position]
        h.title.text = ch.name
        bindSub(h, ch, st)
        val pad = if (ch.logoFit) (18 * h.card.resources.displayMetrics.density).toInt() else 0
        h.img.scaleType = if (ch.logoFit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        h.img.setPadding(pad, pad, pad, pad)
        val hasTeamLogos = !ch.homeLogo.isNullOrBlank() || !ch.awayLogo.isNullOrBlank()
        if (hasTeamLogos) {
            h.img.dispose()
            h.img.visibility = View.GONE
            h.teams.visibility = View.VISIBLE
            h.homeImg.dispose()
            h.awayImg.dispose()
            h.homeImg.setImageDrawable(null)
            h.awayImg.setImageDrawable(null)
            ch.homeLogo?.takeIf { it.isNotBlank() }?.let { h.homeImg.load(it) { crossfade(true) } }
            ch.awayLogo?.takeIf { it.isNotBlank() }?.let { h.awayImg.load(it) { crossfade(true) } }
        } else {
            h.teams.visibility = View.GONE
            h.img.visibility = View.VISIBLE
            h.homeImg.dispose()
            h.awayImg.dispose()
            if (ch.logo.isNullOrBlank()) {
                h.img.dispose()
                h.img.setImageDrawable(null)
                h.img.setBackgroundColor(0xFF14110E.toInt())
            } else {
                h.img.load(ch.logo) {
                    crossfade(true)
                    error(android.graphics.drawable.ColorDrawable(0xFF252A33.toInt()))
                    placeholder(android.graphics.drawable.ColorDrawable(0xFF14110E.toInt()))
                }
            }
        }

        val d = h.card.resources.displayMetrics.density
        val bg = GradientDrawable().apply { cornerRadius = 8 * d }
        h.badge.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        when (st) {
            State.LIVE -> {
                bg.orientation = GradientDrawable.Orientation.LEFT_RIGHT
                bg.setColors(intArrayOf(0xFFFF3D00.toInt(), 0xFFFF8A00.toInt()))
                h.badge.text = "TRỰC TIẾP"
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize((7 * d).toInt(), (7 * d).toInt())
                }
                h.badge.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                h.badge.visibility = View.VISIBLE
            }
            State.UPCOMING -> {
                bg.setColor(0xB3000000.toInt())
                h.badge.text = ch.start?.let { hm.format(Date(it)) } ?: "SẮP DIỄN RA"
                h.badge.visibility = View.VISIBLE
            }
            State.ENDED -> {
                bg.setColor(0xB3555555.toInt()); h.badge.text = "ĐÃ XONG"; h.badge.visibility = View.VISIBLE
            }
            State.ALWAYS -> h.badge.visibility = View.GONE
        }
        h.badge.background = bg
        h.card.setOnClickListener { onClick(ch) }
    }

    private fun bindSub(h: VH, ch: Channel, st: State) {
        val parts = mutableListOf<String>()
        ch.detail?.takeIf { it.isNotBlank() }?.let { parts += it }
        ch.start?.let { s -> parts += fmt.format(Date(s)) }
        if (ch.start == null && st == State.LIVE) parts += "Đang phát trực tiếp"
        if (ch.start == null && st == State.UPCOMING) parts += "Chưa có giờ phát"
        h.sub.text = parts.joinToString("  |  ")
        h.sub.setTextColor(if (st == State.LIVE) 0xFFFF9A3C.toInt() else Glass.TEXT2)
    }

    override fun getItemCount() = items.size
}
