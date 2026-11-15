package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.EppleIcons
import com.eppletv.launcher.ui.Type

/**
 * One Control Center control: a frosted tile (or round button) with a glyph, a title and a status line. Focused, it
 * turns solid white with dark ink and grows slightly, as on tvOS.
 */
@SuppressLint("ViewConstructor")
class CcTile(ctx: Context, private val l: Layout, private val clock: FrameClock, backdrop: Backdrop, private val glyph: Int,
             val title: String, private val big: Boolean, private val round: Boolean) : FrameLayout(ctx), FrameClock.Tick {
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gs = Paint(Paint.ANTI_ALIAS_FLAG); private val gf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(23f) * Type.scale; typeface = Type.of(600) }
    private val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(20f) * Type.scale; typeface = Type.of(500) }
    private val rect = RectF()
    var sub: String = ""
        set(v) { if (field != v) { field = v; face.invalidate() } }
    private val corner get() = if (round) height / 2f else l.pxf(30f)
    private val face = object : View(ctx) { override fun onDraw(c: Canvas) = paintFace(c) }

    init {
        isFocusable = true; contentDescription = title
        addView(TabGlass(ctx, l, backdrop, if (round) -1f else l.pxf(30f)), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(face, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    private fun paintFace(c: Canvas) {
        val fv = f.value.coerceIn(0f, 1f)
        val w = width.toFloat(); val h = height.toFloat()
        if (fv > 0.01f) { fill.color = Colors.lerp(0x00FFFFFF, Colors.WHITE, fv); rect.set(0f, 0f, w, h); c.drawRoundRect(rect, corner, corner, fill) }
        val ink = Colors.lerp(Colors.WHITE, Colors.INK, fv)
        val dim = Colors.lerp(0xB8FFFFFF.toInt(), 0xFF5A5F69.toInt(), fv)
        when {
            round -> icon(c, w / 2f, h / 2f, l.pxf(34f), ink)
            big -> {
                icon(c, w / 2f, h * 0.42f, l.pxf(60f), ink)
                t1.color = ink; t1.textAlign = Paint.Align.CENTER; c.drawText(title, w / 2f, h * 0.78f, t1)
            }
            else -> {
                val gx = l.pxf(22f) + l.pxf(17f)
                icon(c, gx, h / 2f, l.pxf(32f), ink)
                val tx = l.pxf(78f)
                t1.color = ink; t1.textAlign = Paint.Align.LEFT
                if (sub.isEmpty()) c.drawText(title, tx, h / 2f - (t1.descent() + t1.ascent()) / 2f, t1)
                else {
                    c.drawText(title, tx, h / 2f - l.pxf(4f), t1)
                    t2.color = dim; c.drawText(ellipsize(sub, w - tx - l.pxf(16f)), tx, h / 2f + l.pxf(24f), t2)
                }
            }
        }
    }

    /** The tile's icon in the Epple TV style; a control is always "active", so its accent shows in full. */
    private fun icon(c: Canvas, x: Float, y: Float, size: Float, ink: Int) { gs.color = ink; EppleIcons.draw(glyph, c, x, y, size, gs, 1f) }

    private fun ellipsize(s: String, max: Float): String {
        if (t2.measureText(s) <= max) return s
        var n = s.length
        while (n > 1 && t2.measureText(s.substring(0, n) + "…") > max) n--
        return s.substring(0, n) + "…"
    }

    override fun tick(dt: Float): Boolean {
        f.step(dt); val sc = 1f + 0.05f * f.value; scaleX = sc; scaleY = sc; face.invalidate(); return !f.atRest()
    }
}

/**
 * Control Center, opened from the clock and profile badge (or the remote's Menu button): a frosted panel under the
 * badge with Standby, Wi-Fi, Inputs, Volume and Sleep Timer tiles and a row of round buttons. D-pad movement is
 * geometric (the nearest control in the pressed direction); Back or Menu closes it.
 */
class ControlCenter(ctx: Context, private val l: Layout, private val clock: FrameClock, backdrop: Backdrop) : FrameClock.Tick {
    class Item(val glyph: Int, val title: String, val big: Boolean = false, val round: Boolean = false, val action: () -> Unit)

    val view = FrameLayout(ctx).apply { visibility = View.GONE; clipChildren = false; isClickable = true }
    private val dim = View(ctx).apply { setBackgroundColor(0x59000000) }
    private val panel = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    val tiles = ArrayList<CcTile>()
    private val p = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    var open = false; private set
    private var onClosed: (() -> Unit)? = null
    private val backdrop = backdrop

    init {
        view.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.addView(panel, FrameLayout.LayoutParams(l.px(600), l.px(400), android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = l.px(132); rightMargin = l.px(64) })
    }

    /** Lays out the controls: Standby (tall) and four tiles in two columns, then up to five round buttons. */
    fun build(items: List<Item>) {
        panel.removeAllViews(); tiles.clear()
        val tileW = l.px(292); val tileH = l.px(84); val gap = l.px(16)
        val spots = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 2, 1 to 2)          // (column, row) of the first five
        var round = 0; var placed = 0
        for (it in items) {
            val t = CcTile(view.context, l, clock, backdrop, it.glyph, it.title, it.big, it.round)
            t.setOnClickListener { _ -> it.action() }
            val lp = if (it.round) {
                val d = l.px(84); val step = (l.px(600) - 5 * d) / 4
                FrameLayout.LayoutParams(d, d).apply { leftMargin = round++ * (d + step); topMargin = 3 * (tileH + gap) }
            } else {
                val (col, row) = spots[(placed++).coerceAtMost(4)]
                FrameLayout.LayoutParams(tileW, if (it.big) 2 * tileH + gap else tileH).apply { leftMargin = col * (tileW + gap); topMargin = row * (tileH + gap) }
            }
            panel.addView(t, lp); tiles.add(t)
        }
    }

    fun show(closed: () -> Unit) {
        onClosed = closed; open = true
        view.visibility = View.VISIBLE
        p.target = 1f; clock.add(this)
        tiles.firstOrNull()?.requestFocus()
    }

    fun hide() {
        if (!open) return
        open = false; p.target = 0f; clock.add(this)
        onClosed?.invoke()
    }

    /** Up from the top row (the profile orb above the panel takes the focus). */
    var onTop: (() -> Unit)? = null

    /** D-pad inside the panel; returns true when handled. */
    fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val dx = when (e.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (e.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU) { if (e.repeatCount == 0) hide(); return true }
        if (dx == 0 && dy == 0) return false
        val cur = tiles.firstOrNull { it.isFocused } ?: return true.also { tiles.firstOrNull()?.requestFocus() }
        val next = nearest(cur, dx, dy)
        if (next == null && dy < 0) onTop?.invoke() else next?.requestFocus()
        return true
    }

    /** The control whose centre is closest in the pressed direction, preferring ones in line with the current one. */
    private fun nearest(from: CcTile, dx: Int, dy: Int): CcTile? {
        val fx = from.left + from.width / 2f; val fy = from.top + from.height / 2f
        return tiles.filter { it !== from }.mapNotNull { t ->
            val tx = t.left + t.width / 2f; val ty = t.top + t.height / 2f
            val along = (tx - fx) * dx + (ty - fy) * dy
            if (along <= l.pxf(8f)) null else {
                val across = if (dx != 0) kotlin.math.abs(ty - fy) else kotlin.math.abs(tx - fx)
                t to (along + across * 2.2f)
            }
        }.minByOrNull { it.second }?.first
    }

    override fun tick(dt: Float): Boolean {
        p.step(dt)
        val v = p.value.coerceIn(0f, 1f)
        view.alpha = v; panel.translationY = -(1f - v) * l.pxf(24f); panel.scaleX = 0.96f + 0.04f * v; panel.scaleY = panel.scaleX
        panel.pivotX = panel.width.toFloat(); panel.pivotY = 0f
        if (p.atRest() && !open) view.visibility = View.GONE
        return !p.atRest()
    }
}
