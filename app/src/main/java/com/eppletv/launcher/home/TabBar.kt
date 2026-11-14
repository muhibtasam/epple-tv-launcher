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
    private val cache = java.util.Collections.synchronizedMap(WeakHashMap<Bitmap, BitmapShader>())
    fun shader(src: Bitmap): BitmapShader = cache[src] ?: prepare(src)
    /**
     * Builds the frost for [src] (about 20-40 ms on the TV's CPU). Called on the worker that decodes each backdrop,
     * so the main thread only looks it up: building it there was a frame drop every time the artwork changed.
     */
    fun prepare(src: Bitmap): BitmapShader = cache[src] ?: BitmapShader(texture(src), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { cache[src] = it }

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

/**
 * Epple TV's tab icons, drawn from paths on a 24-unit grid (no image assets). One duotone style for all of them: a
 * rounded 2-unit outline plus one solid accent shape (Home's door, the clapper's top, the play mark on the TV, one
 * Apps tile, the Search lens). [accent] 0..1 lights the accent: the open tab and the focused one show it in full.
 */
internal object TabIcons {
    const val HOME = 0; const val MOVIES = 1; const val TV = 2; const val LIBRARY = 3; const val APPS = 4; const val SEARCH = 5; const val SETTINGS = 6
    private val path = Path()
    private val r = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun draw(icon: Int, c: Canvas, cx: Float, cy: Float, size: Float, p: Paint, accent: Float = 0f) {
        val u = size / 24f
        c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(u, u)
        p.strokeWidth = 2.1f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fill.color = p.color; fill.alpha = (p.alpha * (0.55f + 0.45f * accent.coerceIn(0f, 1f))).toInt()
        path.reset()
        fun rr(l: Float, t: Float, rt: Float, b: Float, rad: Float, paint: Paint = p) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, paint) }
        when (icon) {
            HOME -> {
                path.moveTo(3.2f, 11.2f); path.lineTo(12f, 3.9f); path.lineTo(20.8f, 11.2f)
                path.moveTo(5.6f, 9.6f); path.lineTo(5.6f, 18.4f); path.quadTo(5.6f, 20.6f, 7.8f, 20.6f); path.lineTo(16.2f, 20.6f)
                path.quadTo(18.4f, 20.6f, 18.4f, 18.4f); path.lineTo(18.4f, 9.6f); c.drawPath(path, p)
                rr(9.7f, 13.6f, 14.3f, 20.6f, 1.6f, fill)
            }
            MOVIES -> {
                rr(3.4f, 9.8f, 20.6f, 20.4f, 2.6f)
                // The clapper, tilted open, as the solid accent.
                path.moveTo(3.4f, 8.6f); path.lineTo(20.2f, 4.6f); path.lineTo(19.3f, 1.4f); path.lineTo(2.5f, 5.4f); path.close()
                c.drawPath(path, fill); path.reset()
                path.moveTo(3.4f, 13.6f); path.lineTo(20.6f, 13.6f); c.drawPath(path, p)
            }
            TV -> {
                rr(2.6f, 5.4f, 21.4f, 17.4f, 3f)
                path.moveTo(8.2f, 20.8f); path.lineTo(15.8f, 20.8f); c.drawPath(path, p); path.reset()
                path.moveTo(10.2f, 8.6f); path.lineTo(15.4f, 11.4f); path.lineTo(10.2f, 14.2f); path.close(); c.drawPath(path, fill)
            }
            LIBRARY -> { rr(4f, 9.5f, 20f, 20.5f, 2.5f)
                         path.moveTo(6.5f, 6.5f); path.lineTo(17.5f, 6.5f); path.moveTo(9f, 3.5f); path.lineTo(15f, 3.5f); c.drawPath(path, p) }
            APPS -> {
                rr(3.4f, 3.4f, 10.6f, 10.6f, 2.4f); rr(3.4f, 13.4f, 10.6f, 20.6f, 2.4f); rr(13.4f, 13.4f, 20.6f, 20.6f, 2.4f)
                c.drawCircle(17f, 7f, 3.8f, fill)
            }
            SEARCH -> {
                c.drawCircle(10.4f, 10.4f, 4.6f, fill)
                c.drawCircle(10.4f, 10.4f, 6.8f, p)
                p.strokeWidth = 2.8f; path.moveTo(15.4f, 15.4f); path.lineTo(20.6f, 20.6f); c.drawPath(path, p); p.strokeWidth = 2.1f
            }
            SETTINGS -> { c.drawCircle(12f, 12f, 3.2f, fill); c.drawCircle(12f, 12f, 7f, p)
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
        // No focus scaling: the pill keeps the same 8 px inset from the bar's edge on every side (concentric corners).
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
class TabGlass(ctx: Context, private val l: Layout, private val backdrop: Backdrop, private val corner: Float = -1f) : View(ctx) {
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF(); private val m = Matrix(); private val loc = IntArray(2)
    private var overlay: Bitmap? = null
    private var overlayKey: Int? = null
    private val radius get() = if (corner < 0f) height / 2f else corner

    init { backdrop.listeners.add { invalidate() }; GlassStyle.register(this) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        overlay?.recycle(); overlay = null
    }

    /** The shared glass material (see [GlassStyle]): frost at the slider's strength, the tint, then the edge light. */
    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        if (overlay == null || overlayKey != GlassStyle.key) { overlay?.recycle(); overlay = GlassStyle.overlay(l, width, height, radius); overlayKey = GlassStyle.key }
        val b = backdrop.currentBitmap
        val sw = rootView.width.toFloat(); val sh = rootView.height.toFloat()
        if (b != null && sw > 0f && !GlassStyle.clear) {
            getLocationInWindow(loc)
            val sh2 = Frost.shader(b)
            m.setScale(sw / 240f, sh / 135f); m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            sh2.setLocalMatrix(m); frost.shader = sh2; frost.alpha = GlassStyle.frostAlpha
            c.drawRoundRect(rect, radius, radius, frost)
        }
        tint.color = GlassStyle.tint
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

