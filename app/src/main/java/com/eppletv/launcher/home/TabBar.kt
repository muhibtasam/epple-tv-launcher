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
 * One tab of the glass capsule (tvOS 26 style): a text label, or for Search only its icon. The open section's tab sits
 * on a soft capsule; the focused tab becomes a solid white capsule with dark text and grows slightly.
 * Each tab keeps its own fixed width, so focus changes never move the others.
 */
@SuppressLint("ViewConstructor")
class TabItem(ctx: Context, private val l: Layout, private val clock: FrameClock, val label: String, private val icon: Int) : View(ctx), FrameClock.Tick {
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val s = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 3f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(27f) * Type.scale; typeface = Type.of(600); textAlign = Paint.Align.CENTER }
    private val iconOnly = icon == TabIcons.SEARCH
    private val iconSize = l.pxf(30f)
    private val pad = l.pxf(30f)
    private val rect = RectF()
    var selectedState = false
        set(v) { if (field != v) { field = v; s.target = if (v) 1f else 0f; clock.add(this) } }

    init { isFocusable = true; contentDescription = label }

    fun contentWidth() = if (iconOnly) iconSize + pad * 1.6f else text.measureText(label) + pad * 2

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    override fun onDraw(c: Canvas) {
        val fv = f.value.coerceIn(0f, 1f); val sv = s.value.coerceIn(0f, 1f)
        val h = height.toFloat(); val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        val bg = Colors.lerp(Colors.lerp(0x00FFFFFF, 0x2EFFFFFF, sv), Colors.WHITE, fv)
        if ((bg ushr 24) > 0) { fill.color = bg; c.drawRoundRect(rect, h / 2f, h / 2f, fill) }
        val ink = Colors.lerp(Colors.lerp(0xD1FFFFFF.toInt(), Colors.WHITE, sv), Colors.INK, fv)
        if (iconOnly) { stroke.color = ink; TabIcons.draw(icon, c, w / 2f, h / 2f, iconSize, stroke) }
        else { text.color = ink; c.drawText(label, w / 2f, h / 2f - (text.descent() + text.ascent()) / 2f, text) }
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
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x6613151A }
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
        val p = l.px(8); val gap = l.px(4)
        val widths = tabs.map { it.contentWidth().toInt() }
        val width = widths.sum() + gap * (tabs.size - 1) + 2 * p
        // Both children get the bar's real width: a MATCH_PARENT glass inside a WRAP_CONTENT bar would measure
        // itself to the whole screen and stretch the bar edge to edge.
        addView(TabGlass(ctx, l, backdrop), LayoutParams(width, MATCH_PARENT).apply { gravity = Gravity.CENTER })
        tabs.forEachIndexed { i, t -> row.addView(t, LinearLayout.LayoutParams(widths[i], l.px(64)).apply { if (i > 0) leftMargin = gap }) }
        addView(row, LayoutParams(width, MATCH_PARENT).apply { gravity = Gravity.CENTER })
        row.setPadding(p, 0, p, 0)
        selected = 0
    }
}

/**
 * The clock in the top-right corner, as on tvOS: a small frosted capsule with the time and a round profile badge.
 * Focusable (it opens Control Center); focused it turns solid white with dark text.
 */
@SuppressLint("ViewConstructor")
class ClockPill(ctx: Context, private val l: Layout, private val clock: FrameClock, backdrop: Backdrop) : FrameLayout(ctx), FrameClock.Tick {
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val glass = TabGlass(ctx, l, backdrop)
    private val face = object : View(ctx) {
        override fun onDraw(c: Canvas) = drawFace(c, width.toFloat(), height.toFloat())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(2.6f); strokeCap = Paint.Cap.ROUND; color = Colors.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(25f) * Type.scale; typeface = Type.of(600) }
    private val rect = RectF()
    private val path = Path()
    private val padL = l.pxf(22f); private val gap = l.pxf(14f); private val inset = l.pxf(7f)
    var label: String = ""
        set(v) { if (field != v) { field = v; requestLayout(); face.invalidate() } }
    /** The profile picture inside the round badge; null shows the person glyph. */
    var avatar: android.graphics.Bitmap? = null
        set(v) {
            field = v
            avatarPaint.shader = v?.let { android.graphics.BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            face.invalidate()
        }
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val am = Matrix()

    init {
        isFocusable = true; clipChildren = false
        addView(glass, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(face, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val w = (padL + text.measureText(label) + gap + (h - 2 * inset) + inset).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    private fun drawFace(c: Canvas, w: Float, h: Float) {
        val fv = f.value.coerceIn(0f, 1f)
        if (fv > 0.01f) { fill.color = Colors.lerp(0x00FFFFFF, Colors.WHITE, fv); rect.set(0f, 0f, w, h); c.drawRoundRect(rect, h / 2f, h / 2f, fill) }
        text.color = Colors.lerp(Colors.WHITE, Colors.INK, fv)
        c.drawText(label, padL, h / 2f - (text.descent() + text.ascent()) / 2f, text)
        // Profile badge: a grey disc with a simple person glyph.
        val d = h - 2 * inset; val cx = w - inset - d / 2f; val cy = h / 2f
        badge.shader = android.graphics.LinearGradient(cx, cy - d / 2f, cx, cy + d / 2f, 0xFFA3ABB8.toInt(), 0xFF5D6573.toInt(), Shader.TileMode.CLAMP)
        val av = avatar
        if (av != null && avatarPaint.shader != null) {
            val k = d / av.width; am.setScale(k, k); am.postTranslate(cx - d / 2f, cy - d / 2f)
            avatarPaint.shader.setLocalMatrix(am)
            c.drawCircle(cx, cy, d / 2f, avatarPaint); return
        }
        c.drawCircle(cx, cy, d / 2f, badge)
        val u = d / 24f
        c.drawCircle(cx, cy - 2.6f * u, 3.6f * u, glyph)
        path.reset(); path.moveTo(cx - 6.6f * u, cy + 7.2f * u)
        path.quadTo(cx, cy - 0.4f * u, cx + 6.6f * u, cy + 7.2f * u); c.drawPath(path, glyph)
    }

    override fun tick(dt: Float): Boolean {
        f.step(dt)
        val sc = 1f + 0.06f * f.value; scaleX = sc; scaleY = sc
        face.invalidate()
        return !f.atRest()
    }
}
