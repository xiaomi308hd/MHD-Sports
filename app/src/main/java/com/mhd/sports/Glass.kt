package com.mhd.sports

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView

/** Phong cách "liquid glass": lớp kính mờ trong suốt, viền sáng, vệt phản chiếu phía trên; tông đen cam. */
object Glass {
    val ORANGE = 0xFFFF7A00.toInt()
    val AMBER = 0xFFFFB347.toInt()
    val TEXT2 = 0xFFB9B2AA.toInt()

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    private fun shape(r: Float, fill: Int = 0, colors: IntArray? = null, stroke: Int = 0, sw: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = r
            if (colors != null) { orientation = GradientDrawable.Orientation.TOP_BOTTOM; setColors(colors) } else setColor(fill)
            if (sw > 0) setStroke(sw, stroke)
        }

    /** Tấm kính: nền tối trong suốt + viền sáng + vệt sáng phía trên. */
    fun glass(
        c: Context, rDp: Float, tint: Int = 0x66000000, stroke: Int = 0x59FFFFFF,
        swDp: Float = 1f, sheen: Int = 0x33FFFFFF
    ): Drawable {
        val r = dp(c, rDp)
        val sw = dp(c, swDp).toInt().coerceAtLeast(1)
        val base = shape(r, fill = tint, stroke = stroke, sw = sw)
        val hl = shape(r, colors = intArrayOf(sheen, 0x00FFFFFF))
        return LayerDrawable(arrayOf<Drawable>(base, hl)).also { it.setLayerInset(1, sw, sw, sw, sw) }
    }

    /** Kính có trạng thái: khi chọn bằng remote/chạm sẽ ánh cam. */
    fun button(c: Context, rDp: Float = 18f, focusTint: Int = 0xB3FF7A00.toInt()): Drawable =
        StateListDrawable().apply {
            val on = glass(c, rDp, tint = focusTint, stroke = 0xFFFFC27A.toInt(), swDp = 2f, sheen = 0x55FFFFFF)
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(android.R.attr.state_pressed), on)
            addState(intArrayOf(), glass(c, rDp))
        }

    fun card(c: Context): Drawable = button(c, 18f, focusTint = 0x66FF7A00)

    private fun accent(c: Context, rDp: Float, swDp: Float, stroke: Int): Drawable {
        val r = dp(c, rDp)
        val base = shape(r, colors = intArrayOf(0xFFFFA32B.toInt(), 0xFFFF5E00.toInt()),
            stroke = stroke, sw = dp(c, swDp).toInt().coerceAtLeast(1))
        val hl = shape(r, colors = intArrayOf(0x66FFFFFF, 0x00FFFFFF))
        return LayerDrawable(arrayOf<Drawable>(base, hl))
    }

    /** Nút cam nổi bật (đang bật / thông báo). */
    fun accentButton(c: Context, rDp: Float = 18f): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), accent(c, rDp, 3f, 0xFFFFFFFF.toInt()))
        addState(intArrayOf(android.R.attr.state_pressed), accent(c, rDp, 3f, 0xFFFFFFFF.toInt()))
        addState(intArrayOf(), accent(c, rDp, 1f, 0x99FFFFFF.toInt()))
    }

    fun chip(c: Context, text: String, sizeSp: Float = 13f): TextView = TextView(c).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt()); textSize = sizeSp
        setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        val h = dp(c, 14f).toInt(); val v = dp(c, 8f).toInt()
        setPadding(h, v, h, v)
        isFocusable = true; isClickable = true
        background = button(c)
    }

    fun clipRound(v: View, rDp: Float) {
        val r = dp(v.context, rDp)
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        v.clipToOutline = true
    }
}
