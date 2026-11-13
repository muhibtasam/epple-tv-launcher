package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring

/**
 * Liquid-glass platter behind the top row. Built from the real backdrop pixels, not a flat translucent fill:
 *  - body: the artwork behind the platter, slightly magnified (lens), softly diffused (GPU blur) and lifted in brightness;
 *  - sheen: a faint milky body that thickens toward the bottom edge, where the glass refracts most;
 *  - rim: a thin specular edge, brightest along the top, plus a moving highlight that follows the focused tile.
 * No extra bitmaps: the body draws the backdrop's own bitmaps. Redrawn only when the backdrop or the page moves.
 */
@SuppressLint("ViewConstructor")
class GlassPlatter(ctx: Context, private val l: Layout, private val backdrop: Backdrop, private val clock: FrameClock) :
    FrameLayout(ctx), FrameClock.Tick {

    private val radius = l.platterRadius
    private val body = Body(ctx)
    private val rim = Rim(ctx)
    private val spot = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val glow = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)

    init {
        clipChildren = false
        val clip = FrameLayout(ctx).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
            }
        }
        clip.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clip.addView(Sheen(ctx), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        backdrop.onChange = { body.invalidate() }
    }

    /** The page moved: the glass must re-sample what is behind it. */
    fun moved() = body.invalidate()

    /** Focus entered (x = focused tile centre in platter coordinates) or left (null) the top row. */
    fun focusAt(x: Float?) {
        if (x != null) { if (glow.value == 0f) spot.snapTo(x) else spot.target = x }
        glow.target = if (x != null) 1f else 0f
        clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        spot.step(dt); glow.step(dt); rim.invalidate()
        return !(spot.atRest() && glow.atRest())
    }

    /** The backdrop behind the platter: magnified, diffused, brightened. */
    private inner class Body(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val m = Matrix()
        private val loc = IntArray(2)
        init {
            if (Build.VERSION.SDK_INT >= 31) {
                val cm = ColorMatrix().apply { setSaturation(1.18f) }
                cm.postConcat(ColorMatrix(floatArrayOf(1.06f, 0f, 0f, 0f, 14f, 0f, 1.06f, 0f, 0f, 14f, 0f, 0f, 1.06f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
                setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm)),
                    RenderEffect.createBlurEffect(l.pxf(10f), l.pxf(10f), Shader.TileMode.CLAMP)))
            }
        }
        override fun onDraw(c: Canvas) {
            val sw = rootView.width.toFloat(); val sh = rootView.height.toFloat()
            if (sw <= 0f) return
            getLocationInWindow(loc)
            c.save()
            // Lens: the glass magnifies what is behind it by a few percent around its own centre.
            val lens = 1.05f
            c.scale(lens, lens, width / 2f, height / 2f)
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            draw(c, backdrop.bottomBitmap, 1f, sw, sh)
            draw(c, backdrop.topBitmap, backdrop.topAlpha, sw, sh)
            c.restore()
        }
        private fun draw(c: Canvas, b: Bitmap?, alpha: Float, sw: Float, sh: Float) {
            if (b == null || alpha <= 0.01f) return
            val s = maxOf(sw / b.width, sh / b.height)                           // same centre-crop as the backdrop
            m.setScale(s, s); m.postTranslate((sw - b.width * s) / 2f, (sh - b.height * s) / 2f)
            paint.alpha = (alpha * 255).toInt()
            c.drawBitmap(b, m, paint)
        }
    }

    /** Milky body, thicker toward the lower edge. */
    private inner class Sheen(ctx: Context) : View(ctx) {
        private val p = Paint()
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0x1FFFFFFF, 0x0AFFFFFF, 0x0DFFFFFF, 0x2EFFFFFF), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        }
        override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
    }

    /** Specular edge: bright at the top, fading down the sides, a softer return along the bottom; plus the focus glint. */
    private inner class Rim(ctx: Context) : View(ctx) {
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(2.4f) }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(1.2f) }
        private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = l.pxf(3f)
            shader = RadialGradient(0f, 0f, l.pxf(260f), intArrayOf(0xE6FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
        private val gm = Matrix()
        private val r = RectF(); private val ri = RectF()
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val sw = edge.strokeWidth / 2f
            r.set(sw, sw, w - sw, h - sw)
            val i = l.pxf(4f); ri.set(i, i, w - i, h - i)
            edge.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xB3FFFFFF.toInt(), 0x33FFFFFF, 0x1AFFFFFF, 0x59FFFFFF), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
            inner.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0x40FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x26000000), floatArrayOf(0f, 0.25f, 0.8f, 1f), Shader.TileMode.CLAMP)
        }
        override fun onDraw(c: Canvas) {
            c.drawRoundRect(ri, radius - l.pxf(4f), radius - l.pxf(4f), inner)
            c.drawRoundRect(r, radius, radius, edge)
            val g = glow.value
            if (g > 0.01f) {
                gm.setTranslate(spot.value, 0f); glint.shader.setLocalMatrix(gm); glint.alpha = (255 * g).toInt()
                c.drawRoundRect(r, radius, radius, glint)
            }
        }
    }
}
