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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Type
import java.util.WeakHashMap

/** Frosted copy of a backdrop image for glass surfaces: 240x135, saturated, lifted and box-blurred; one per image. */
internal object Frost {
    private val cache = WeakHashMap<Bitmap, BitmapShader>()
    fun shader(src: Bitmap): BitmapShader = cache.getOrPut(src) { BitmapShader(texture(src), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }

    private fun texture(src: Bitmap): Bitmap {
        val w = 240; val h = 135
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply { setScale(s, s); postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f) }
        val cm = ColorMatrix().apply { setSaturation(1.25f) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        val px = IntArray(w * h); out.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { blur(px, w, h) }
        out.setPixels(px, 0, w, 0, 0, w, h); out.prepareToDraw()
        return out
    }

    private fun blur(p: IntArray, w: Int, h: Int) {
        val t = IntArray(p.size)
        fun pass(src: IntArray, dst: IntArray, n: Int, lines: Int, step: Int, lineStep: Int) {
            for (line in 0 until lines) {
                val base = line * lineStep
                for (i in 0 until n) {
                    var r = 0; var g = 0; var b = 0
                    for (d in -2..2) { val c = src[base + (i + d).coerceIn(0, n - 1) * step]; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
                    dst[base + i * step] = (0xFF shl 24) or (r / 5 shl 16) or (g / 5 shl 8) or (b / 5)
                }
            }
        }
        pass(p, t, w, h, 1, w); pass(t, p, h, w, w, 1)
    }
}

/** Line icons for the tabs, drawn from paths on a 24-unit grid (no image assets). */
internal object TabIcons {
    const val HOME = 0; const val MOVIES = 1; const val TV = 2; const val LIBRARY = 3; const val APPS = 4; const val SEARCH = 5; const val SETTINGS = 6
    private val path = Path()
    private val r = RectF()

    fun draw(icon: Int, c: Canvas, cx: Float, cy: Float, size: Float, p: Paint) {
        val u = size / 24f
        c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(u, u)
        p.strokeWidth = 2f
        path.reset()
        fun rr(l: Float, t: Float, rt: Float, b: Float, rad: Float) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, p) }
        when (icon) {
            HOME -> { path.moveTo(3f, 11.5f); path.lineTo(12f, 3.8f); path.lineTo(21f, 11.5f)
                      path.moveTo(5.5f, 9.8f); path.lineTo(5.5f, 20.5f); path.lineTo(18.5f, 20.5f); path.lineTo(18.5f, 9.8f)
                      path.moveTo(10f, 20.5f); path.lineTo(10f, 14.8f); path.lineTo(14f, 14.8f); path.lineTo(14f, 20.5f); c.drawPath(path, p) }
            MOVIES -> { rr(3f, 4.5f, 21f, 19.5f, 2.5f)
                        path.moveTo(7.5f, 4.5f); path.lineTo(7.5f, 19.5f); path.moveTo(16.5f, 4.5f); path.lineTo(16.5f, 19.5f)
                        path.moveTo(3f, 12f); path.lineTo(7.5f, 12f); path.moveTo(16.5f, 12f); path.lineTo(21f, 12f)
                        path.moveTo(3f, 8.2f); path.lineTo(7.5f, 8.2f); path.moveTo(16.5f, 8.2f); path.lineTo(21f, 8.2f)
                        path.moveTo(3f, 15.8f); path.lineTo(7.5f, 15.8f); path.moveTo(16.5f, 15.8f); path.lineTo(21f, 15.8f); c.drawPath(path, p) }
            TV -> { rr(2.5f, 5.5f, 21.5f, 17.5f, 2.5f)
                    path.moveTo(8f, 21f); path.lineTo(16f, 21f); path.moveTo(9f, 2.5f); path.lineTo(12f, 5.5f); path.lineTo(15f, 2.5f); c.drawPath(path, p) }
            LIBRARY -> { rr(4f, 9.5f, 20f, 20.5f, 2.5f)
                         path.moveTo(6.5f, 6.5f); path.lineTo(17.5f, 6.5f); path.moveTo(9f, 3.5f); path.lineTo(15f, 3.5f); c.drawPath(path, p) }
            APPS -> { rr(3.5f, 3.5f, 10.5f, 10.5f, 2.2f); rr(13.5f, 3.5f, 20.5f, 10.5f, 2.2f); rr(3.5f, 13.5f, 10.5f, 20.5f, 2.2f); rr(13.5f, 13.5f, 20.5f, 20.5f, 2.2f) }
            SEARCH -> { c.drawCircle(10.5f, 10.5f, 6.5f, p); path.moveTo(15.4f, 15.4f); path.lineTo(20.5f, 20.5f); c.drawPath(path, p) }
            SETTINGS -> { c.drawCircle(12f, 12f, 3.2f, p); c.drawCircle(12f, 12f, 7f, p)
                          for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                              path.moveTo(12f + ca * 7f, 12f + sa * 7f); path.lineTo(12f + ca * 10f, 12f + sa * 10f) }
                          c.drawPath(path, p) }
        }
        c.restore()
    }
}

/**
 * One tab, in the style of a streaming app's floating tab bar: its icon above its label, always both. The tab of the
 * open section sits on a soft capsule in full white; the focused tab gets a brighter capsule and grows slightly.
 * Every tab has the same fixed slot, so focus changes never move the other tabs.
 */
@SuppressLint("ViewConstructor")
class TabItem(ctx: Context, private val l: Layout, private val clock: FrameClock, val label: String, private val icon: Int) : View(ctx), FrameClock.Tick {
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val s = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 3f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(21f) * Type.scale; typeface = Type.of(600); textAlign = Paint.Align.CENTER }
    private val iconSize = l.pxf(32f)
    private val pad = l.pxf(20f)
    private val rect = RectF()
    var selectedState = false
        set(v) { if (field != v) { field = v; s.target = if (v) 1f else 0f; clock.add(this) } }

    init { isFocusable = true; contentDescription = label }

    fun contentWidth() = maxOf(text.measureText(label) + pad * 2, l.pxf(118f))

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    override fun onDraw(c: Canvas) {
        val fv = f.value.coerceIn(0f, 1f); val sv = s.value.coerceIn(0f, 1f)
        val h = height.toFloat(); val w = width.toFloat()
        val inset = l.pxf(5f)
        rect.set(inset, inset, w - inset, h - inset)
        val bg = Colors.lerp(Colors.lerp(0x00FFFFFF, 0x24FFFFFF, sv), 0x45FFFFFF, fv)
        if ((bg ushr 24) > 0) { fill.color = bg; val rad = rect.height() * 0.32f; c.drawRoundRect(rect, rad, rad, fill) }
        val ink = Colors.lerp(0x9EFFFFFF.toInt(), Colors.WHITE, maxOf(sv, fv))
        stroke.color = ink
        TabIcons.draw(icon, c, w / 2f, h * 0.39f, iconSize, stroke)
        text.color = ink
        c.drawText(label, w / 2f, h * 0.80f, text)
    }

    override fun tick(dt: Float): Boolean {
        f.step(dt); s.step(dt)
        val sc = 1f + 0.06f * f.value; scaleX = sc; scaleY = sc
        invalidate()
        return !(f.atRest() && s.atRest())
    }
}

/**
 * Glass behind the tabs: the current backdrop, frosted and screen-aligned (so it reads as blurred background),
 * a dark tint that keeps icons readable over bright artwork, and a pre-rendered top highlight and hairline rim.
 * Three cheap draws over a small area; it redraws only when the backdrop changes.
 */
@SuppressLint("ViewConstructor")
class TabGlass(ctx: Context, private val l: Layout, private val backdrop: Backdrop) : View(ctx) {
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8013151A.toInt() }
    private val rect = RectF(); private val m = Matrix(); private val loc = IntArray(2)
    private var overlay: Bitmap? = null
    private val radius get() = height / 2f

    init { backdrop.listeners.add { invalidate() } }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        if (w <= 0 || h <= 0) return
        overlay?.recycle()
        overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawRoundRect(rect, h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x1AFFFFFF, 0x05FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) })
            val sw = l.pxf(1.5f).coerceAtLeast(1f)
            c.drawRoundRect(RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2), h / 2f - sw / 2, h / 2f - sw / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x59FFFFFF, 0x1AFFFFFF, 0x26FFFFFF), null, Shader.TileMode.CLAMP) })
            b.prepareToDraw()
        }
    }

    override fun onDraw(c: Canvas) {
        val b = backdrop.currentBitmap
        val sw = rootView.width.toFloat(); val sh = rootView.height.toFloat()
        if (b != null && sw > 0f) {
            getLocationInWindow(loc)
            val sh2 = Frost.shader(b)
            m.setScale(sw / 240f, sh / 135f); m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            sh2.setLocalMatrix(m); frost.shader = sh2
            c.drawRoundRect(rect, radius, radius, frost)
        }
        c.drawRoundRect(rect, radius, radius, tint)
        overlay?.let { c.drawBitmap(it, 0f, 0f, null) }
    }
}

/** The tab bar: a compact floating glass capsule, centred at the top, with the tabs in fixed slots. */
@SuppressLint("ViewConstructor")
class TabBar(ctx: Context, l: Layout, clock: FrameClock, labels: List<String>, icons: List<Int>, backdrop: Backdrop) : FrameLayout(ctx) {
    val tabs = labels.mapIndexed { i, s -> TabItem(ctx, l, clock, s, icons[i]) }
    private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    var selected = 0
        set(v) { field = v; tabs.forEachIndexed { i, t -> t.selectedState = i == v } }

    init {
        clipChildren = false; clipToPadding = false
        val slot = tabs.maxOf { it.contentWidth() }.toInt()
        val p = l.px(14)
        val width = slot * tabs.size + 2 * p
        // Both children get the bar's real width: a MATCH_PARENT glass inside a WRAP_CONTENT bar would measure
        // itself to the whole screen and stretch the bar edge to edge.
        addView(TabGlass(ctx, l, backdrop), LayoutParams(width, MATCH_PARENT).apply { gravity = Gravity.CENTER })
        tabs.forEach { t -> row.addView(t, LinearLayout.LayoutParams(slot, l.px(96))) }
        addView(row, LayoutParams(width, MATCH_PARENT).apply { gravity = Gravity.CENTER })
        row.setPadding(p, 0, p, 0)
        selected = 0
    }
}
