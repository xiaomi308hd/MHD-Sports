package com.mhd.sports

import android.graphics.Color
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChannelAdapter(
    private val items: List<Pair<Channel, State>>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(
        val card: LinearLayout, val img: ImageView, val badge: TextView,
        val title: TextView, val sub: TextView
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
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = RecyclerView.LayoutParams(dp(232), ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.rightMargin = dp(10) }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setStroke(dp(2), Color.WHITE); cornerRadius = dp(10).toFloat()
                })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
        }
        val thumb = FrameLayout(c)
        val img = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1C2028.toInt())
        }
        thumb.addView(img, FrameLayout.LayoutParams(dp(224), dp(126)))
        val badge = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        thumb.addView(badge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).also { it.setMargins(0, dp(6), dp(6), 0) })
        card.addView(thumb)

        val title = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 14f; maxLines = 2
            ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
        }
        val sub = TextView(c).apply { setTextColor(0xFF9AA3B0.toInt()); textSize = 13f }
        card.addView(title); card.addView(sub)
        return VH(card, img, badge, title, sub)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val (ch, st) = items[position]
        h.title.text = ch.name
        h.sub.text = ch.start?.let { fmt.format(Date(it)) + if (st == State.LIVE) " · đang phát" else "" } ?: ""
        h.sub.setTextColor(if (st == State.LIVE) 0xFFFF6B6B.toInt() else 0xFF9AA3B0.toInt())
        if (ch.logo.isNullOrBlank()) { h.img.dispose(); h.img.setImageDrawable(null) }
        else h.img.load(ch.logo) { crossfade(true) }

        val d = h.card.resources.displayMetrics.density
        val bg = GradientDrawable().apply { cornerRadius = 6 * d }
        when (st) {
            State.LIVE -> { h.badge.text = "● TRỰC TIẾP"; bg.setColor(0xFFE53935.toInt()); h.badge.visibility = View.VISIBLE }
            State.UPCOMING -> { h.badge.text = hm.format(Date(ch.start!!)); bg.setColor(0xCC000000.toInt()); h.badge.visibility = View.VISIBLE }
            State.ENDED -> { h.badge.text = "ĐÃ XONG"; bg.setColor(0xCC555555.toInt()); h.badge.visibility = View.VISIBLE }
            State.ALWAYS -> h.badge.visibility = View.GONE
        }
        h.badge.background = bg
        h.card.setOnClickListener { onClick(ch) }
    }

    override fun getItemCount() = items.size
}
