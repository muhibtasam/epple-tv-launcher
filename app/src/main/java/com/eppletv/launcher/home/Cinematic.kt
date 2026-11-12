package com.eppletv.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring

/**
 * Full-screen backdrop: the focused app's artwork, scaled up from a tiny copy so it becomes a soft colour field.
 * Two layers; the top one fades over an opaque bottom, so a change never dips through black.
 */
class Backdrop(ctx: Context, private val clock: FrameClock) : FrameClock.Tick {
    val bottom = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val top = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val fade = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS)
    private var topIsFront = false
    private var current: Bitmap? = null

    fun show(b: Bitmap) {
        if (b === current) return
        current = b
        if (bottom.drawable == null) { bottom.setImageBitmap(b); return }
        if (topIsFront) { bottom.setImageBitmap(b); fade.target = 0f; topIsFront = false }
        else { top.setImageBitmap(b); fade.target = 1f; topIsFront = true }
        clock.add(this)
    }

    override fun tick(dt: Float): Boolean { fade.step(dt); top.alpha = fade.value; return !fade.atRest() }
}

/** Large title of the focused app in the top shelf. Cross-fades with a small upward drift. */
class HeroTitle(ctx: Context, private val l: Layout, private val clock: FrameClock) : FrameClock.Tick {
    val view = FrameLayout(ctx)
    private val a = text(ctx); private val b = text(ctx)
    private val fa = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private val fb = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private var front = a

    init {
        view.addView(a, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        view.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun show(title: String) {
        if (front.text == title && (if (front === a) fa else fb).target == 1f) return
        val back = if (front === a) b else a
        back.text = title
        (if (back === a) fa else fb).target = 1f
        (if (front === a) fa else fb).target = 0f
        front = back
        clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        fa.step(dt); fb.step(dt)
        a.alpha = fa.value; a.translationY = (1f - fa.value) * l.pxf(14f)
        b.alpha = fb.value; b.translationY = (1f - fb.value) * l.pxf(14f)
        return !(fa.atRest() && fb.atRest())
    }

    private fun text(ctx: Context) = TextView(ctx).apply {
        setTextColor(Color.WHITE); alpha = 0f
        setTextSize(TypedValue.COMPLEX_UNIT_PX, l.pxf(68f)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        letterSpacing = -0.01f
        setShadowLayer(l.pxf(18f), 0f, l.pxf(3f), 0x88000000.toInt())
    }
}
