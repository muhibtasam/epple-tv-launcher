package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import java.util.WeakHashMap

/**
 * Liquid-glass platter behind the top row, drawn by one view with shaders only (no view clipping or layers, which this
 * GPU pays for on every frame):
 *  - body: the artwork behind the platter, magnified a few percent (lens), diffused and lifted in brightness. The
 *    diffusion is baked once per artwork into a 240x135 texture (~130 KB), so a frame only samples cached textures;
 *  - sheen: a faint milky body that thickens toward the lower edge, where the glass refracts most;
 *  - rim: a thin specular edge, brightest along the top, an inner shadow line at the bottom, and a glint that follows focus.
 * Redrawn only when the artwork changes, the page moves vertically, or the glint moves.
 */
@SuppressLint("ViewConstructor")
class GlassPlatter(ctx: Context, private val l: Layout, private val backdrop: Backdrop, private val clock: FrameClock) :
    View(ctx), FrameClock.Tick {

    private val radius = l.platterRadius
    private val spot = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val glow = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(2.4f) }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(1.2f) }
    private val glintGrad = RadialGradient(0f, 0f, l.pxf(260f), intArrayOf(0xE6FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val glintPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var rimBmp: Bitmap? = null
    private val r = RectF(); private val ri = RectF(); private val rb = RectF()
    private val m = Matrix(); private val gm = Matrix()
    private val loc = IntArray(2)
    private val textures = WeakHashMap<Bitmap, BitmapShader>()

    init { backdrop.listeners.add { invalidate() } }

    /** The page moved vertically: the glass must re-sample what is behind it. */
    fun moved() = invalidate()

    /** Focus entered (x = focused tile centre in platter coordinates) or left (null) the top row. */
    fun focusAt(x: Float?) {
        if (x != null) { if (glow.value == 0f) spot.snapTo(x) else spot.target = x }
        glow.target = if (x != null) 1f else 0f
        clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        spot.step(dt); glow.step(dt); invalidate()
        return !(spot.atRest() && glow.atRest())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val sw = edge.strokeWidth / 2f
        rb.set(0f, 0f, w.toFloat(), h.toFloat())
        r.set(sw, sw, w - sw, h - sw)
        val i = l.pxf(4f); ri.set(i, i, w - i, h - i)
        val hf = h.toFloat()
        sheen.shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(0x1FFFFFFF, 0x0AFFFFFF, 0x0DFFFFFF, 0x2EFFFFFF),
            floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(0xB3FFFFFF.toInt(), 0x33FFFFFF, 0x1AFFFFFF, 0x59FFFFFF),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        inner.shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(0x40FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x26000000),
            floatArrayOf(0f, 0.25f, 0.8f, 1f), Shader.TileMode.CLAMP)
        if (w <= 0 || h <= 0) return
        rimBmp?.recycle()
        rimBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawRoundRect(ri, radius - l.pxf(4f), radius - l.pxf(4f), inner)
            c.drawRoundRect(r, radius, radius, edge)
            b.prepareToDraw()
        }
        // Glint: a white rim line, revealed only under a radial light that follows focus.
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(mask).drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(3f) })
        glintPaint.shader = android.graphics.ComposeShader(BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), glintGrad, android.graphics.PorterDuff.Mode.DST_IN)
    }

    private companion object { const val FROST = 0f } // Frost off: on the C6K any frost under the rim/glint costs ~15 ms a frame, whatever its strength.

    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4D13151A }

    override fun onDraw(c: Canvas) {
        val sw = rootView.width.toFloat(); val sh = rootView.height.toFloat()
        // Frosted body, the same as the tab bar's: the current artwork's shared 240x135 frost texture, screen-aligned,
        // under a light tint. One textured draw plus one flat fill (the per-layer lens body stays off: see FROST).
        val b = backdrop.currentBitmap
        if (b != null && sw > 0f) {
            getLocationInWindow(loc)
            val fs = Frost.shader(b)
            m.setScale(sw / 240f, sh / 135f); m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            fs.setLocalMatrix(m); bodyPaint.shader = fs; bodyPaint.alpha = 255
            c.drawRoundRect(rb, radius, radius, bodyPaint)
        }
        c.drawRoundRect(rb, radius, radius, tint)
        // No sheen pass: with the frost under it, a second full-platter layer costs this GPU ~15 ms a frame.
        // Rims are pre-rendered: stroked rounded rects with gradients cost this GPU ~45 ms a frame when drawn live.
        rimBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        val g = glow.value
        if (g > 0.01f && glintPaint.shader != null) {
            gm.setTranslate(spot.value, 0f); glintGrad.setLocalMatrix(gm); glintPaint.alpha = (255 * g).toInt()
            c.drawRect(rb, glintPaint)
        }
    }

    /** One artwork layer seen through the glass: screen-mapped texture, magnified around the platter's centre. */
    private fun body(c: Canvas, b: Bitmap?, alpha: Float, sw: Float, sh: Float) {
        if (b == null || alpha * FROST <= 0.01f) return
        val shader = textures.getOrPut(b) { BitmapShader(frosted(b), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        m.setScale(sw / 240f, sh / 135f)
        m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
        val lens = 1.05f
        m.postScale(lens, lens, width / 2f, height / 2f)
        shader.setLocalMatrix(m)
        bodyPaint.shader = shader; bodyPaint.alpha = (alpha * FROST * 255).toInt()
        c.drawRoundRect(rb, radius, radius, bodyPaint)
    }

    /** The artwork as it fills the screen (centre-crop), shrunk to 240x135, box-blurred, saturated and brightened. */
    private fun frosted(src: Bitmap): Bitmap {
        val w = 240; val h = 135
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val mm = Matrix().apply { setScale(s, s); postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f) }
        val cm = ColorMatrix().apply { setSaturation(1.2f) }
        cm.postConcat(ColorMatrix(floatArrayOf(1.06f, 0f, 0f, 0f, 16f, 0f, 1.06f, 0f, 0f, 16f, 0f, 0f, 1.06f, 0f, 16f, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, mm, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        val px = IntArray(w * h); out.getPixels(px, 0, w, 0, 0, w, h)
        repeat(2) { boxBlur(px, w, h) }                       // about a 12 px blur at full size, plus bilinear upscaling
        out.setPixels(px, 0, w, 0, 0, w, h)
        out.prepareToDraw()
        return out
    }

    private fun boxBlur(p: IntArray, w: Int, h: Int) {
        val t = IntArray(p.size)
        fun pass(src: IntArray, dst: IntArray, n: Int, lines: Int, step: Int, lineStep: Int) {
            for (line in 0 until lines) {
                val base = line * lineStep
                for (i in 0 until n) {
                    var a = 0; var rr = 0; var g = 0; var bb = 0
                    for (d in -1..1) {
                        val c = src[base + (i + d).coerceIn(0, n - 1) * step]
                        a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bb += c and 0xFF
                    }
                    dst[base + i * step] = (a / 3 shl 24) or (rr / 3 shl 16) or (g / 3 shl 8) or (bb / 3)
                }
            }
        }
        pass(p, t, w, h, 1, w)      // horizontal
        pass(t, p, h, w, w, 1)      // vertical
    }

}
