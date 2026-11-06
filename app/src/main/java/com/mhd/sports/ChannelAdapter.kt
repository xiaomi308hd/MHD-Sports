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
        return VH(card, img, badge, title, sub)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val (ch, st) = items[position]
        h.title.text = ch.name
        bindSub(h, ch, st)
        if (ch.logo.isNullOrBlank()) { h.img.dispose(); h.img.setImageDrawable(null) }
        else h.img.load(ch.logo) { crossfade(true) }

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
                bg.setColor(0xB3000000.toInt()); h.badge.text = hm.format(Date(ch.start!!)); h.badge.visibility = View.VISIBLE
            }
            State.ENDED -> {
                bg.setColor(0xB3555555.toInt()); h.badge.text = "ĐÃ XONG"; h.badge.visibility = View.VISIBLE
            }
            State.ALWAYS -> h.badge.visibility = View.GONE
        }
        h.badge.background = bg
        h.card.setOnClickListener { onClick(ch) }
    }

    /** Chỉ cập nhật dòng thời gian (mỗi giây), không tải lại ảnh. */
    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val (ch, st) = items[position]
            bindSub(h, ch, st)
        } else super.onBindViewHolder(h, position, payloads)
    }

    private fun bindSub(h: VH, ch: Channel, st: State) {
        val s = ch.start
        if (s == null) { h.sub.text = ""; return }
        val base = fmt.format(Date(s))
        if (st == State.LIVE) {
            val sec = ((System.currentTimeMillis() - s) / 1000).coerceAtLeast(0)
            h.sub.text = base + " · Đang phát " + String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
            h.sub.setTextColor(0xFFFF9A3C.toInt())
        } else {
            h.sub.text = base
            h.sub.setTextColor(Glass.TEXT2)
        }
    }

    /** Gọi mỗi giây: đếm phút:giây cho các trận đang phát. */
    fun tickTimers() {
        items.forEachIndexed { i, p -> if (p.second == State.LIVE) notifyItemChanged(i, "t") }
    }

    override fun getItemCount() = items.size
}
