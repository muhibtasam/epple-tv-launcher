package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.EppleIcons
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Type

/*
 * Home v1: the graphite side menu (top left), the graphite-orb clock (top right) and the colour-spill stand under the
 * top row. All three use the one glass material (GlassStyle), drawn directly with shaders: no bitmaps are made while
 * they animate, so the menu can grow and the clock can change size every frame without garbage.
 */

/** The shared glass, drawn into any rect of [v]: the frosted backdrop, the tint and a hairline rim. */
internal object GlassPaint {
    private val loc = IntArray(2); private val m = Matrix()
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r2 = RectF()

    fun draw(v: View, c: Canvas, backdrop: Backdrop, r: RectF, radius: Float, l: Layout, alpha: Float = 1f) {
        if (alpha <= 0.01f) return
        val b = backdrop.currentBitmap; val root = v.rootView
        if (b != null && root.width > 0 && !GlassStyle.clear) {
            v.getLocationInWindow(loc)
            val sh = Frost.shader(b)
            m.setScale(root.width / 240f, root.height / 135f); m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            sh.setLocalMatrix(m); frost.shader = sh; frost.alpha = (GlassStyle.frostAlpha * alpha).toInt()
            c.drawRoundRect(r, radius, radius, frost)
        }
        val t = GlassStyle.tint
        tint.color = t; tint.alpha = ((t ushr 24) * alpha).toInt()
        c.drawRoundRect(r, radius, radius, tint)
        val sw = l.pxf(1.2f).coerceAtLeast(1f)
        rim.strokeWidth = sw; rim.color = Colors.WHITE; rim.alpha = (0x3A * alpha).toInt()
        r2.set(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2)
        c.drawRoundRect(r2, radius - sw / 2, radius - sw / 2, rim)
    }
}

/**
 * The side menu (design "Top bar 1 · Glass sidebar"). Closed it is a glass hint pill in the top-left corner: the open
 * section's icon on a round chip, its name and "Home + OK for menu". When focus comes in, the pill grows into a glass
 * panel listing every section, each on a round chip; the focused one is a white pill.
 */
@SuppressLint("ViewConstructor")
class SideBar(ctx: Context, private val l: Layout, private val clock: FrameClock, labels: List<String>, private val icons: List<Int>,
              order: List<Int>, private val backdrop: Backdrop) : FrameLayout(ctx), FrameClock.Tick {
    val w1 = l.px(440)
    private val pad = l.px(18); private val pillH = l.pxf(88f); private val chip = l.pxf(58f)
    private val o = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val hf = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)       // the hint pill itself focused
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.WHITE }
    val openness get() = o.value.coerceIn(0f, 1f)
    /** Indexed by section, laid out in [order]. */
    val tabs = labels.mapIndexed { i, s -> SideItem(ctx, l, clock, s, icons[i]) }
    private val names = labels
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); alpha = 0f }
    private val rect = RectF()
    private val lift = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Colors.WHITE }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(28f) * Type.scale; typeface = Type.of(600); color = Colors.WHITE }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(23f) * Type.scale; typeface = Type.of(500); color = Colors.WHITE }
    private val hintText = "Home + OK for menu"

    var open = false
        set(v) { if (field != v) { field = v; o.target = if (v) 1f else 0f; clock.add(this) } }
    var selected = 0
        set(v) { field = v; tabs.forEachIndexed { i, t -> t.selectedState = i == v }; invalidate() }

    init {
        setWillNotDraw(false); clipChildren = true; isFocusable = true
        order.forEachIndexed { k, i -> col.addView(tabs[i], LinearLayout.LayoutParams(w1 - 2 * pad, l.px(80)).apply { if (k > 0) topMargin = l.px(4) }) }
        addView(col, LayoutParams(w1, LayoutParams.WRAP_CONTENT))
        backdrop.listeners.add { invalidate() }; GlassStyle.register(this)
        selected = 0
    }

    /** The hint pill itself has the focus (Home's resting place): it turns white. */
    var hint = false
        set(v) { if (field != v) { field = v; hf.target = if (v) 1f else 0f; clock.add(this) } }

    private fun pillW() = l.pxf(15f) + chip + l.pxf(18f) + maxOf(title.measureText(names[selected]), sub.measureText(hintText)) + l.pxf(34f)

    override fun onDraw(c: Canvas) {
        val k = openness
        val pw = pillW(); val ph = pillH
        rect.set(0f, 0f, pw + (w1 - pw) * k, ph + (height - ph) * k)
        val rad = ph / 2f + (l.pxf(44f) - ph / 2f) * k
        GlassPaint.draw(this, c, backdrop, rect, rad, l)
        c.drawRoundRect(rect, rad, rad, lift)
        val a = (1f - k) * (1f - k)
        if (a > 0.01f) {
            val h = hf.value.coerceIn(0f, 1f)
            if (h > 0.01f) { white.alpha = (255 * h * a).toInt(); c.drawRoundRect(rect, rad, rad, white) }
            val ink = Colors.lerp(Colors.WHITE, Colors.INK, h)
            val cx = l.pxf(15f) + chip / 2f; val cy = ph / 2f
            chipPaint.color = Colors.lerp(0x38FFFFFF, 0x14000000, h); chipPaint.alpha = ((chipPaint.color ushr 24) * a).toInt(); c.drawCircle(cx, cy, chip / 2f, chipPaint)
            stroke.color = ink; stroke.alpha = (255 * a).toInt(); EppleIcons.draw(icons[selected], c, cx, cy, l.pxf(36f), stroke, 1f)
            val tx = l.pxf(15f) + chip + l.pxf(18f)
            title.color = ink; sub.color = ink
            title.alpha = (255 * a).toInt(); sub.alpha = (200 * a).toInt()
            c.drawText(names[selected], tx, cy - l.pxf(6f), title)
            c.drawText(hintText, tx, cy + l.pxf(24f), sub)
        }
    }

    override fun tick(dt: Float): Boolean {
        o.step(dt); hf.step(dt); val k = openness
        col.alpha = k * k; col.translationY = -(1f - k) * l.pxf(12f)
        invalidate()
        return !(o.atRest() && hf.atRest())
    }
}

/** One menu row: the section's icon on a round chip and its name; focused, a white pill. */
@SuppressLint("ViewConstructor")
class SideItem(ctx: Context, private val l: Layout, private val clock: FrameClock, val label: String, private val icon: Int) : View(ctx), FrameClock.Tick {
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val s = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 3f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(30f) * Type.scale; typeface = Type.of(600) }
    private val rect = RectF()
    var selectedState = false
        set(v) { if (field != v) { field = v; s.target = if (v) 1f else 0f; clock.add(this) } }

    init { isFocusable = true; contentDescription = label }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    override fun onDraw(c: Canvas) {
        val fv = f.value.coerceIn(0f, 1f); val sv = s.value.coerceIn(0f, 1f)
        val h = height.toFloat(); val w = width.toFloat()
        if (fv > 0.01f) {
            // The pill grows out of the chip as focus arrives.
            val pw = h + (w - h) * fv
            rect.set(0f, 0f, pw, h); fill.color = Colors.WHITE; fill.alpha = (255 * fv).toInt(); c.drawRoundRect(rect, h / 2f, h / 2f, fill)
        }
        val d = l.pxf(58f); val cx = (h - d) / 2f + d / 2f; val cy = h / 2f
        EppleIcons.well(icon, c, cx, cy, d, l.pxf(36f), stroke, fv, sv)
        val ink = Colors.lerp(Colors.WHITE, Colors.INK, fv)
        text.color = ink
        c.drawText(label, cx + d / 2f + l.pxf(20f), cy - (text.descent() + text.ascent()) / 2f, text)
    }

    override fun tick(dt: Float): Boolean {
        f.step(dt); s.step(dt); invalidate()
        return !(f.atRest() && s.atRest())
    }
}

/**
 * The clock, top right (design "C · Graphite orb"): a glass capsule with the time and the profile orb. Focused it turns
 * white and 10 px taller, and the time gives way to the Control Center switches; Right moves to the orb (profile).
 */
@SuppressLint("ViewConstructor")
class ClockPill(ctx: Context, private val l: Layout, private val clock: FrameClock, private val backdrop: Backdrop) : View(ctx), FrameClock.Tick {
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val p = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(25f) * Type.scale; typeface = Type.of(600, Type.Face.NUM) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sphere = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFE9E9EE.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val am = Matrix(); private val sm = Matrix()
    private val rect = RectF(); private val r2 = RectF(); private val path = Path()
    private val h0 = l.pxf(60f); private val inset0 = l.pxf(7f); private val inset1 = l.pxf(6f)
    private val padL = l.pxf(22f); private val gap = l.pxf(14f)
    private var ringShader: SweepGradient? = null; private var sphereShader: RadialGradient? = null; private var shaderD = -1f

    var label: String = ""
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    var avatar: android.graphics.Bitmap? = null
        set(v) { field = v; avatarPaint.shader = v?.let { android.graphics.BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; invalidate() }
    var dot = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** While focused: 0 = Control Center, 1 = the profile orb. */
    var part = 0
        set(v) { val n = v.coerceIn(0, 1); if (field != n) { field = n; p.target = n.toFloat(); clock.add(this) } }

    init { isFocusable = true; isLongClickable = true; backdrop.listeners.add { invalidate() }; GlassStyle.register(this) }

    private fun restW() = padL + text.measureText(label) + gap + (h0 - 2 * inset0) + inset0
    private fun focusW(h: Float) = l.pxf(18f) + l.pxf(32f) + l.pxf(14f) + l.pxf(1f) + l.pxf(12f) + (h - 2 * inset1) + inset1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(maxOf(restW(), focusW(h.toFloat())).toInt() + 1, h)
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        if (!gain) part = 0
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    override fun onDraw(c: Canvas) {
        val fv = f.value.coerceIn(0f, 1f); val pv = p.value.coerceIn(0f, 1f)
        val W = width.toFloat(); val H = height.toFloat()
        val rh = h0 + (H - h0) * fv
        val rw = restW() + (focusW(H) - restW()) * fv
        rect.set(W - rw, (H - rh) / 2f, W, (H + rh) / 2f)
        val rad = rh / 2f
        GlassPaint.draw(this, c, backdrop, rect, rad, l, 1f - fv)
        if (fv > 0.01f) { fill.color = Colors.WHITE; fill.alpha = (255 * fv).toInt(); c.drawRoundRect(rect, rad, rad, fill) }
        val cy = H / 2f
        val inset = inset0 + (inset1 - inset0) * fv
        val d = rh - 2 * inset; val ox = rect.right - inset - d / 2f
        // Time: fades and lifts away as focus arrives.
        if (fv < 0.99f) {
            text.color = Colors.WHITE; text.alpha = (255 * (1f - fv) * (1f - fv)).toInt()
            c.drawText(label, rect.left + padL, cy - (text.descent() + text.ascent()) / 2f - fv * l.pxf(8f), text)
        }
        if (fv > 0.01f) {
            val ix = rect.left + l.pxf(18f) + l.pxf(16f)
            // The selected part sits on a light grey disc.
            fill.color = 0xFFE4E4E8.toInt()
            fill.alpha = (255 * fv * (1f - pv)).toInt(); if (fill.alpha > 2) c.drawCircle(ix, cy, l.pxf(25f), fill)
            fill.alpha = (255 * fv * pv).toInt(); if (fill.alpha > 2) c.drawCircle(ox, cy, d / 2f + l.pxf(4f), fill)
            icon.color = Colors.INK; icon.alpha = (255 * fv).toInt()
            EppleIcons.draw(EppleIcons.TOGGLES, c, ix, cy + (1f - fv) * l.pxf(8f), l.pxf(30f), icon, 1f - pv)
            fill.color = 0x24000000; fill.alpha = (0x24 * fv).toInt()
            c.drawRect(ix + l.pxf(30f), cy - l.pxf(17f), ix + l.pxf(31f), cy + l.pxf(17f), fill)
        }
        drawOrb(c, ox, cy, d)
        if (dot) { val x = ox + d * 0.36f; val y = cy - d * 0.36f; val r = d * 0.15f
            dotPaint.color = 0xFF111114.toInt(); c.drawCircle(x, y, r + l.pxf(2.5f), dotPaint); dotPaint.color = Colors.WHITE; c.drawCircle(x, y, r, dotPaint) }
    }

    /** The profile orb: the picture, or a graphite sphere with a fine silhouette, inside a brushed-metal ring. */
    private fun drawOrb(c: Canvas, cx: Float, cy: Float, d: Float) {
        if (d != shaderD) {
            shaderD = d
            ringShader = SweepGradient(0f, 0f, intArrayOf(0xFFF5F5F7.toInt(), 0xFF8E8E96.toInt(), 0xFF3A3A40.toInt(), 0xFFB8B8C0.toInt(), 0xFFF5F5F7.toInt()), null)
            sphereShader = RadialGradient(-d * 0.15f, -d * 0.3f, d * 0.75f, intArrayOf(0xFF4A4A52.toInt(), 0xFF232327.toInt(), 0xFF141416.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        sm.setRotate(210f); sm.postTranslate(cx, cy); ringShader!!.setLocalMatrix(sm); ring.shader = ringShader
        c.drawCircle(cx, cy, d / 2f, ring)
        val inner = d / 2f - maxOf(l.pxf(2f), d / 24f)
        val av = avatar
        if (av != null && avatarPaint.shader != null) {
            val k = inner * 2 / minOf(av.width, av.height); am.setScale(k, k); am.postTranslate(cx - av.width * k / 2f, cy - av.height * k / 2f)
            avatarPaint.shader.setLocalMatrix(am); c.drawCircle(cx, cy, inner, avatarPaint); return
        }
        sm.setTranslate(cx, cy); sphereShader!!.setLocalMatrix(sm); sphere.shader = sphereShader
        c.drawCircle(cx, cy, inner, sphere)
        // No picture: the Epple TV profile icon, sized to the sphere.
        EppleIcons.draw(EppleIcons.PROFILE, c, cx, cy, inner * 1.25f, glyph, 0f)
    }

    override fun tick(dt: Float): Boolean {
        f.step(dt); p.step(dt); invalidate()
        return !(f.atRest() && p.atRest())
    }
}

/**
 * The stand under Home's top row (design "Stand C · Colour spill"): a thin glass ledge the tiles stand on, fading out at
 * its ends, and the focused app's own colour lighting the air behind it and the ledge beneath it.
 */
@SuppressLint("ViewConstructor")
class StandLedge(ctx: Context, private val l: Layout, private val backdrop: Backdrop, private val clock: FrameClock) : View(ctx), FrameClock.Tick {
    /** Where the top row's tiles start inside this view. */
    val top0 = l.px(260)
    var count = Layout.COLS
        set(v) { if (field != v) { field = v; invalidate() } }
    private val spot = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS, Motion.REST_PX)
    private val glow = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS)
    private val mix = Spring(1f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS)
    private var from = Colors.WHITE; private var to = Colors.WHITE
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val gm = Matrix()
    private var glowShader: RadialGradient? = null; private var glowColor = 0
    private val rect = RectF()

    init { backdrop.listeners.add { invalidate() }; GlassStyle.register(this) }

    fun moved() = invalidate()

    /** Focus entered (x = the focused tile's centre in this view) or left (null) the top row; [color] is the app's. */
    fun focusAt(x: Float?, color: Int) {
        if (x != null) {
            if (glow.value < 0.02f) { spot.snapTo(x); from = color; to = color; mix.snapTo(1f) }
            else { spot.target = x; if (color != to) { from = current(); to = color; mix.snapTo(0f); mix.target = 1f } }
        }
        glow.target = if (x != null) 1f else 0f
        clock.add(this)
    }

    private fun current() = Colors.lerp(from, to, mix.value.coerceIn(0f, 1f))

    override fun onDraw(c: Canvas) {
        val g = glow.value.coerceIn(0f, 1f); val col = current() or 0xFF000000.toInt()
        val cx = spot.value
        val ly = top0 + l.tileH + l.pxf(24f); val lh = l.pxf(20f)
        val x0 = l.left - l.pxf(6f); val x1 = l.left + (count - 1) * l.pitchX + l.tileW + l.pxf(6f)
        // The app's light lives on the stand only: it spreads under the ledge and a little past it, never up behind the tiles.
        if (g > 0.01f) {
            if (glowShader == null || glowColor != col) {
                glowColor = col
                glowShader = RadialGradient(0f, 0f, 1f, intArrayOf(col and 0x99FFFFFF.toInt(), col and 0x33FFFFFF, col and 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            }
            val rx = l.pxf(300f); val ry = l.pxf(78f); val cy = ly + lh / 2f
            gm.setScale(rx, ry); gm.postTranslate(cx, cy); glowShader!!.setLocalMatrix(gm)
            glowPaint.shader = glowShader; glowPaint.alpha = (255 * g).toInt()
            val save = c.save(); c.clipRect(0f, ly + lh * 0.3f, width.toFloat(), height.toFloat())
            c.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, glowPaint)
            c.restoreToCount(save)
        }
        rect.set(x0, ly, x1, ly + lh)
        GlassPaint.draw(this, c, backdrop, rect, lh / 2f, l)
        if (g > 0.01f) {
            val half = l.pxf(190f)
            streak.shader = LinearGradient(cx - half, 0f, cx + half, 0f, intArrayOf(col and 0x00FFFFFF, col, col and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            streak.alpha = (190 * g).toInt()
            val save = c.saveLayer(x0, ly, x1, ly + lh, null)
            c.drawRoundRect(rect, lh / 2f, lh / 2f, streak)
            c.restoreToCount(save)
        }
    }

    override fun tick(dt: Float): Boolean {
        spot.step(dt); glow.step(dt); mix.step(dt); invalidate()
        return !(spot.atRest() && glow.atRest() && mix.atRest())
    }
}
