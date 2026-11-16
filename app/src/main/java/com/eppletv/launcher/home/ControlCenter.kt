package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style
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
             val title: String, private val big: Boolean, val round: Boolean) : FrameLayout(ctx), FrameClock.Tick {
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
    /** A picture drawn instead of the glyph (the Epple TV app logo on its own settings button). */
    var art: android.graphics.drawable.Drawable? = null
        set(v) { field = v; face.invalidate() }
    /** Left/Right change this control's value while it is being adjusted (the Volume tile); null for other controls. */
    var adjust: ((Int) -> Unit)? = null
    /** The control is being adjusted: its status line shows arrows and Left/Right go to [adjust]. */
    var adjusting = false
        set(v) { if (field != v) { field = v; face.invalidate() } }

    init {
        isFocusable = true; contentDescription = title
        addView(TabGlass(ctx, l, backdrop, if (round) -1f else l.pxf(30f)), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(face, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        if (!gain) adjusting = false
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
                // The Home menu's treatment: the icon sits in a soft circle (an app's logo mark keeps its own shape).
                if (art == null) EppleIcons.well(glyph, c, gx, h / 2f, l.pxf(52f), l.pxf(30f), gs, fv, 0f) else icon(c, gx, h / 2f, l.pxf(32f), ink)
                val tx = l.pxf(78f)
                t1.color = ink; t1.textAlign = Paint.Align.LEFT
                if (sub.isEmpty()) c.drawText(title, tx, h / 2f - (t1.descent() + t1.ascent()) / 2f, t1)
                else {
                    c.drawText(title, tx, h / 2f - l.pxf(4f), t1)
                    t2.color = dim
                    val by = h / 2f + l.pxf(24f)
                    if (!adjusting) c.drawText(ellipsize(sub, w - tx - l.pxf(16f)), tx, by, t2)
                    else {
                        // Left and right adjust the value: the set's own arrows either side of it (not text glyphs).
                        val a = l.pxf(22f); val gap = l.pxf(8f); val cy = by + (t2.descent() + t2.ascent()) / 2f
                        val v = ellipsize(sub, w - tx - l.pxf(16f) - 2 * (a + gap))
                        gs.color = dim; EppleIcons.draw(EppleIcons.LEFT, c, tx + a / 2f, cy, a, gs, 1f)
                        c.drawText(v, tx + a + gap, by, t2)
                        EppleIcons.draw(EppleIcons.RIGHT, c, tx + a + gap + t2.measureText(v) + gap + a / 2f, cy, a, gs, 1f)
                    }
                }
            }
        }
    }

    /** The tile's icon in the Epple TV style, as in the Home menu: accent at 55 % at rest, in full when focused. */
    private fun icon(c: Canvas, x: Float, y: Float, size: Float, ink: Int) {
        val a = art
        // The app's logo mark (transparent, its own shading kept) takes the button's ink: white at rest, dark focused.
        if (a != null) { val s = if (round) (height * 0.66f).toInt() else size.toInt(); a.setTint(ink); a.setBounds((x - s / 2f).toInt(), (y - s / 2f).toInt(), (x + s / 2f).toInt(), (y + s / 2f).toInt()); a.draw(c); return }
        gs.color = ink; EppleIcons.draw(glyph, c, x, y, size, gs, f.value.coerceIn(0f, 1f))
    }

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
 * Control Center (design B, Split Hub), opened from the clock and profile badge (or the remote's Menu button): a glass
 * panel under the badge. Left, the quick controls (Wi-Fi, Standby, Inputs, Volume, Sleep Timer tiles and four round
 * buttons); right, the notifications with category tabs and Clear All. The focused notification opens up with its
 * artwork and details; OK opens it, holding OK dismisses it. D-pad movement is geometric (the nearest control in the
 * pressed direction) across both sides; Back or Menu closes it.
 */
class ControlCenter(ctx: Context, private val l: Layout, private val clock: FrameClock, backdrop: Backdrop,
                    /** Artwork for content notes, from the shared image cache. */
                    private val art: (String, (android.graphics.Bitmap?) -> Unit) -> Unit) : FrameClock.Tick {
    class Item(val glyph: Int, val title: String, val big: Boolean = false, val round: Boolean = false, val action: () -> Unit)

    val view = FrameLayout(ctx).apply { visibility = View.GONE; clipChildren = false; isClickable = true }
    private val dim = View(ctx).apply { setBackgroundColor(0x59000000) }
    private val panel = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    val tiles = ArrayList<CcTile>()
    private val p = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    var open = false; private set
    private var onClosed: (() -> Unit)? = null
    private val backdrop = backdrop
    private val W = l.px(1240); private val H = l.px(800); private val pad = l.px(36)
    private val leftW = l.px(520); private val rightX = pad + leftW + l.px(56); private val rightW = W - rightX - pad

    // Notifications side.
    private val I = com.eppletv.launcher.ui.EppleIcons
    private val tabs = listOf("All" to I.BELL, "Content" to I.TV, "System" to I.SETTINGS, "Updates" to I.DOWNLOAD).mapIndexed { i, (t, ic) ->
        com.eppletv.launcher.ui.Pill(ctx, t, l.pxf(22f), l.pxf(30f), l.px(16), clock, 0x00FFFFFF, Colors.WHITE, 0.03f).apply {
            icon(ic, l.px(24), l.px(8)); setOnClickListener { category = i } } }
    private val clear = com.eppletv.launcher.ui.Pill(ctx, "Clear All", l.pxf(22f), l.pxf(30f), l.px(20), clock, Colors.FILL, Colors.WHITE, 0.03f).apply {
        icon(I.TRASH, l.px(26), l.px(10)); setOnClickListener { onClearAll?.invoke() } }
    /**
     * The list's window under the tabs. The list scrolls by translation, and Android clips a child only to its own
     * (translated) bounds, while the panel lets its children draw outside it: so the window clips itself to its own
     * bounds (its outline), and lets the list measure its full height (not capped at the window's) so no row is cut.
     */
    private val listBox = object : FrameLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, h)
            list.measure(MeasureSpec.makeMeasureSpec(rightW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
    }.apply { outlineProvider = android.view.ViewOutlineProvider.BOUNDS; clipToOutline = true }
    private val list = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
    private val empty = android.widget.TextView(ctx).apply {
        style(Txt.META, l.u, Colors.TEXT_DIM); gravity = android.view.Gravity.CENTER
        setCompoundDrawablesRelative(null, com.eppletv.launcher.ui.IconDrawable(I.BELL, l.px(64), 0f).apply { setBounds(0, 0, l.px(64), l.px(64)); color = Colors.TEXT_DIM }, null, null)
        compoundDrawablePadding = l.px(16) }
    private val rows = ArrayList<NoteRow>()
    private val glass = TabGlass(ctx, l, backdrop, l.pxf(44f))
    private val divider = View(ctx).apply { setBackgroundColor(0x24FFFFFF) }
    /** The notifications side is shown (at least one notification); without any the panel is the controls alone. */
    private var side = true
    var onOpenNote: ((com.eppletv.launcher.system.NotificationHub.Note) -> Unit)? = null
    var onDismissNote: ((com.eppletv.launcher.system.NotificationHub.Note) -> Unit)? = null
    var onClearAll: (() -> Unit)? = null
    /** Text for the empty list (e.g. when other apps' notifications aren't allowed). */
    var emptyText = "You're all caught up"
    private var category = 0
        set(v) { field = v; tabs.forEachIndexed { i, t -> t.selectedState = i == v }; refresh() }

    init {
        view.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.addView(panel, FrameLayout.LayoutParams(W, H, android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = l.px(124); rightMargin = l.px(64) })
        panel.addView(glass, FrameLayout.LayoutParams(W, H))
        panel.addView(android.widget.TextView(ctx).apply { style(Txt.TITLE, l.u); text = "Control Center" },
            FrameLayout.LayoutParams(leftW, l.px(56)).apply { leftMargin = pad; topMargin = pad })
        panel.addView(divider, FrameLayout.LayoutParams(l.px(1).coerceAtLeast(1), H - 2 * pad).apply { leftMargin = pad + leftW + l.px(28); topMargin = pad })
        var x = rightX
        for (t in tabs) { val w = (t.paint.measureText(t.text.toString()) + l.px(24 + 8 + 32 + 4)).toInt()
            panel.addView(t, FrameLayout.LayoutParams(w, l.px(60)).apply { leftMargin = x; topMargin = pad }); x += w + l.px(6) }
        val cw = (clear.paint.measureText("Clear All") + l.px(26 + 10 + 40)).toInt()
        panel.addView(clear, FrameLayout.LayoutParams(cw, l.px(60)).apply { leftMargin = rightX + rightW - cw; topMargin = H - pad - l.px(60) })
        listBox.addView(list, FrameLayout.LayoutParams(rightW, FrameLayout.LayoutParams.WRAP_CONTENT))
        listBox.addView(empty, FrameLayout.LayoutParams(rightW, FrameLayout.LayoutParams.MATCH_PARENT))
        panel.addView(listBox, FrameLayout.LayoutParams(rightW, H - pad - l.px(84) - pad - l.px(76)).apply { leftMargin = rightX; topMargin = pad + l.px(84) })
        tabs[0].selectedState = true
    }

    /** Lays out the quick controls: Wi-Fi across the top, four tiles in two columns, then the round buttons. */
    fun build(items: List<Item>) {
        tiles.forEach { panel.removeView(it) }; tiles.clear()
        val gap = l.px(16); val tileH = l.px(96); val half = (leftW - gap) / 2; val top = pad + l.px(84)
        var placed = 0; var round = 0
        for (it in items) {
            val t = CcTile(view.context, l, clock, backdrop, it.glyph, it.title, false, it.round)
            t.setOnClickListener { _ -> it.action() }
            val lp = when {
                it.round -> { val d = l.px(92); val step = (leftW - 4 * d) / 3
                    FrameLayout.LayoutParams(d, d).apply { leftMargin = pad + round++ * (d + step); topMargin = top + 3 * (tileH + gap) + l.px(12) } }
                placed == 0 -> { placed++; FrameLayout.LayoutParams(leftW, tileH).apply { leftMargin = pad; topMargin = top } }
                else -> { val k = placed++ - 1
                    FrameLayout.LayoutParams(half, tileH).apply { leftMargin = pad + (k % 2) * (half + gap); topMargin = top + (1 + k / 2) * (tileH + gap) } }
            }
            panel.addView(t, lp); tiles.add(t)
        }
        // Each round button's name, under it.
        for (t in tiles.filter { it.round }) panel.addView(android.widget.TextView(view.context).apply {
            style(Txt.FOOT, l.u, Colors.TEXT_DIM); text = t.title; gravity = android.view.Gravity.CENTER; maxLines = 2 },
            FrameLayout.LayoutParams(l.px(150), l.px(64)).apply {
                val lp = t.layoutParams as FrameLayout.LayoutParams; leftMargin = lp.leftMargin + lp.width / 2 - l.px(75); topMargin = lp.topMargin + lp.height + l.px(10) })
    }

    /** Shows the notifications of the current category again (a note arrived, was read or dismissed). */
    fun refresh() {
        val focusedKey = rows.firstOrNull { it.hasFocus() }?.note?.key
        val all = com.eppletv.launcher.system.NotificationHub.notes
        if (all.isEmpty()) {
            // No notifications at all: the panel is the quick controls alone (focus first goes back to a control).
            if (open && (tabs + clear + rows).any { it.hasFocus() }) (lastTile ?: tiles.firstOrNull())?.requestFocus()
            list.removeAllViews(); rows.clear(); list.translationY = 0f
            showSide(false); return
        }
        showSide(true)
        val shown = when (category) { 1 -> all.filter { it.cat == com.eppletv.launcher.system.NotificationHub.CONTENT }
            2 -> all.filter { it.cat == com.eppletv.launcher.system.NotificationHub.SYSTEM }
            3 -> all.filter { it.cat == com.eppletv.launcher.system.NotificationHub.UPDATE }; else -> all }.take(20)
        list.removeAllViews(); rows.clear()
        for (n in shown) {
            val r = NoteRow(view.context, l, n)
            r.setOnClickListener { onOpenNote?.invoke(n) }
            r.setOnLongClickListener { onDismissNote?.invoke(n); true }
            r.onFocus = { place(r) }
            n.art?.let { u -> art(u) { b -> if (b != null) r.picture = b } }
            list.addView(r, android.widget.LinearLayout.LayoutParams(rightW, r.restH).apply { bottomMargin = l.px(10) }); rows.add(r)
        }
        empty.text = emptyText; empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        clear.alpha = if (all.isEmpty()) 0.5f else 1f
        list.translationY = 0f
        if (open) rows.firstOrNull { it.note.key == focusedKey }?.requestFocus() ?: run { if (focusedKey != null) (rows.firstOrNull() ?: tabs[category]).requestFocus() }
    }

    /** The notes changed: the list is rebuilt only if which notes it shows changed; otherwise rows just redraw. */
    fun changed() {
        val all = com.eppletv.launcher.system.NotificationHub.notes
        val keys = when (category) { 1 -> all.filter { it.cat == com.eppletv.launcher.system.NotificationHub.CONTENT }
            2 -> all.filter { it.cat == com.eppletv.launcher.system.NotificationHub.SYSTEM }
            3 -> all.filter { it.cat == com.eppletv.launcher.system.NotificationHub.UPDATE }; else -> all }.take(20).map { it.key }
        if (side == all.isNotEmpty() && keys == rows.map { it.note.key }) rows.forEach { it.invalidate() } else refresh()
    }

    /** Shows or hides the whole notifications side; without it the panel narrows to the controls (still under the clock). */
    private fun showSide(on: Boolean) {
        if (side == on) return
        side = on
        val w = if (on) W else pad + leftW + pad
        // Controls alone: the panel also ends just under the round buttons' names (no empty band at the bottom).
        val bottom = tiles.maxOfOrNull { (it.layoutParams as FrameLayout.LayoutParams).let { lp -> lp.topMargin + lp.height + if (it.round) l.px(70) else 0 } } ?: H
        val h = if (on) H else minOf(H, bottom + pad)
        (panel.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h }; panel.requestLayout()
        (glass.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h }; glass.requestLayout()
        val v = if (on) View.VISIBLE else View.GONE
        (tabs + clear + listOf(divider, listBox)).forEach { it.visibility = v }
    }

    /** Keeps the focused row inside the list's window. */
    private fun place(r: NoteRow) {
        val box = listBox.height; if (box == 0) return
        val top = r.top + list.translationY; val bottom = top + r.height
        if (bottom > box) list.translationY -= bottom - box
        if (top < 0) list.translationY -= top
    }

    fun show(closed: () -> Unit) {
        onClosed = closed; open = true
        view.visibility = View.VISIBLE
        refresh()
        p.target = 1f; clock.add(this)
        tiles.firstOrNull()?.requestFocus()
    }

    fun hide() {
        if (!open) return
        tiles.forEach { it.adjusting = false }
        open = false; p.target = 0f; clock.add(this)
        onClosed?.invoke()
    }

    /** Up from the top row (the profile orb above the panel takes the focus). */
    var onTop: (() -> Unit)? = null

    private fun focusables(): List<View> = tiles + tabs + clear + rows

    /** D-pad inside the panel; returns true when handled. */
    fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val dx = when (e.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (e.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        val adj = tiles.firstOrNull { it.adjusting }
        if (e.keyCode == KeyEvent.KEYCODE_BACK && adj != null) { adj.adjusting = false; return true }
        if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU) { if (e.repeatCount == 0) hide(); return true }
        if (dx == 0 && dy == 0) return false
        val all = focusables()
        val cur = all.firstOrNull { it.isFocused } ?: return true.also { tiles.firstOrNull()?.requestFocus() }
        // A control being adjusted (Volume): Left/Right change it, Up/Down leave it (OK and Back too, above/below).
        if (cur is CcTile && cur.adjusting) {
            if (dx != 0) { cur.adjust?.invoke(dx); return true }
            cur.adjusting = false
        }
        // Between the two sides: Right past the controls goes to the notifications, Left from them back to the last control.
        if (cur is CcTile) lastTile = cur
        if (dx > 0 && cur is CcTile && nearest(cur, tiles.filter { inLine(cur, it) }, dx, 0) == null) { (rows.firstOrNull() ?: tabs[category]).requestFocus(); return true }
        if (dx < 0 && cur !is CcTile && (cur is NoteRow || cur === clear || cur === tabs[0])) { (lastTile ?: tiles.first()).requestFocus(); return true }
        // Inside the list, Up and Down go row by row (and Up from the first row to the tabs).
        if (cur is NoteRow && dx == 0) {
            val i = rows.indexOf(cur) + dy
            if (i in rows.indices) rows[i].requestFocus() else if (i < 0) tabs[category].requestFocus() else clear.requestFocus()
            return true
        }
        val next = nearest(cur, all, dx, dy)
        if (next == null && dy < 0) onTop?.invoke() else next?.requestFocus()
        return true
    }

    private var lastTile: CcTile? = null
    private fun inLine(a: View, b: View) = kotlin.math.abs(center(a).second - center(b).second) < l.pxf(40f)

    private val rc = android.graphics.Rect()
    private fun center(v: View): Pair<Float, Float> { rc.set(0, 0, v.width, v.height); panel.offsetDescendantRectToMyCoords(v, rc); return rc.exactCenterX() to rc.exactCenterY() }

    /** The control whose centre is closest in the pressed direction, preferring ones in line with the current one. */
    private fun nearest(from: View, all: List<View>, dx: Int, dy: Int): View? {
        val (fx, fy) = center(from)
        return all.filter { it !== from && it.visibility == View.VISIBLE && it.isShown }.mapNotNull { t ->
            val (tx, ty) = center(t)
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
        view.alpha = v; panel.translationY = -(1f - v) * l.pxf(24f); panel.scaleX = 0.97f + 0.03f * v; panel.scaleY = panel.scaleX
        panel.pivotX = panel.width.toFloat(); panel.pivotY = 0f
        if (p.atRest() && !open) view.visibility = View.GONE
        return !p.atRest()
    }
}

/**
 * One notification in Control Center: unread dot, artwork (or the category's Epple TV icon, or the app's own icon),
 * title, time and text. Focused, it turns white and opens up: wide artwork, the full text and what OK and a long OK do.
 */
@SuppressLint("ViewConstructor")
class NoteRow(ctx: Context, private val l: Layout, val note: com.eppletv.launcher.system.NotificationHub.Note) : View(ctx) {
    private val Hub = com.eppletv.launcher.system.NotificationHub
    private val I = com.eppletv.launcher.ui.EppleIcons
    val restH = l.px(96); private val openH = l.px(236)
    var picture: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }
    var onFocus: (() -> Unit)? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG); private val ic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val t1 = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(24f) * Type.scale; typeface = Type.of(700) }
    private val t2 = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(20f) * Type.scale; typeface = Type.of(500) }
    private val r = RectF(); private val m = android.graphics.Matrix(); private val clip = Path()

    init { isFocusable = true; isClickable = true; isLongClickable = true; contentDescription = "${note.app}: ${note.title}" }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        (layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { it.height = if (gain) openH else restH; layoutParams = it }
        if (gain) { Hub.markRead(note.key); post { onFocus?.invoke() } }
        invalidate()
    }

    /** The app's own icon, Epple-style in a circle. A glyph (mostly transparent, usually white) sits in a grey well
     *  that stays dark enough to show it on the focused white card; a full-bleed icon (logo, avatar) is cropped to the
     *  circle. Its own colours are kept either way. */
    private fun appIcon(c: Canvas, icon: android.graphics.Bitmap, f: Boolean) {
        val d = r.width(); val cx = r.centerX(); val cy = r.centerY()
        val glyph = iconIsGlyph ?: isGlyph(icon).also { iconIsGlyph = it }
        clip.reset(); clip.addCircle(cx, cy, d / 2f, Path.Direction.CW)
        if (glyph) {
            bg.color = if (f) 0xFF7A7F89.toInt() else 0x38FFFFFF; c.drawCircle(cx, cy, d / 2f, bg)
            val s = d * 0.56f / maxOf(icon.width, icon.height)
            m.setScale(s, s); m.postTranslate(cx - icon.width * s / 2f, cy - icon.height * s / 2f)
            c.drawBitmap(icon, m, img)
        } else {
            c.save(); c.clipPath(clip)
            val s = d / minOf(icon.width, icon.height)
            m.setScale(s, s); m.postTranslate(cx - icon.width * s / 2f, cy - icon.height * s / 2f)
            c.drawBitmap(icon, m, img); c.restore()
        }
    }
    private var iconIsGlyph: Boolean? = null
    /** Mostly transparent (a glyph) rather than a picture filling its square. */
    private fun isGlyph(b: android.graphics.Bitmap): Boolean {
        var clear = 0; var n = 0; val step = maxOf(1, b.width / 12)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) { n++; if ((b.getPixel(x, y) ushr 24) < 0x40) clear++ }
        return clear * 2 > n
    }

    private fun ago(t: Long): String {
        val d = (System.currentTimeMillis() - t) / 60000
        return when { d < 1 -> "Now"; d < 60 -> "$d min"; d < 1440 -> "${d / 60} h"; else -> android.text.format.DateFormat.format("d MMM", t).toString() }
    }

    override fun onDraw(c: Canvas) {
        val f = isFocused; val w = width.toFloat(); val h = height.toFloat()
        val ink = if (f) Colors.INK else Colors.WHITE; val dim = if (f) 0xFF4A4E58.toInt() else 0xB8FFFFFF.toInt()
        r.set(0f, 0f, w, h); bg.color = if (f) Colors.WHITE else 0x14FFFFFF; c.drawRoundRect(r, l.pxf(22f), l.pxf(22f), bg)
        var x = l.pxf(18f)
        // Unread: a dot at the edge.
        if (!note.read) { bg.color = ink; c.drawCircle(x + l.pxf(5f), l.pxf(48f), l.pxf(5f), bg) }
        x += l.pxf(20f)
        val pic = picture ?: note.icon
        val pw = if (f && picture != null) l.pxf(300f) else l.pxf(64f); val ph = if (f && picture != null) l.pxf(169f) else l.pxf(64f)
        val py = if (f && picture != null) l.pxf(18f) else l.pxf(16f)
        r.set(x, py, x + pw, py + ph)
        if (picture == null && note.icon != null) appIcon(c, note.icon, f)
        else if (pic != null) {
            clip.reset(); clip.addRoundRect(r, l.pxf(14f), l.pxf(14f), Path.Direction.CW)
            c.save(); c.clipPath(clip)
            val s = maxOf(pw / pic.width, ph / pic.height); m.setScale(s, s); m.postTranslate(x + (pw - pic.width * s) / 2f, py + (ph - pic.height * s) / 2f)
            c.drawBitmap(pic, m, img); c.restore()
        } else {
            val icon = when { note.key == "wifi" && note.title.contains("disconnected") -> I.WIFI_OFF; note.key == "wifi" -> I.WIFI
                note.cat == Hub.UPDATE -> I.DOWNLOAD; note.cat == Hub.CONTENT -> I.TV; else -> I.BELL }
            I.well(icon, c, r.centerX(), r.centerY(), pw, l.pxf(30f), ic, if (f) 1f else 0f, 0f)   // the Home menu's treatment
        }
        val tx = r.right + l.pxf(18f); val tw = w - tx - l.pxf(20f)
        val time = ago(note.time)
        t2.color = dim; val timeW = t2.measureText(time)
        t1.color = ink; c.drawText(android.text.TextUtils.ellipsize(note.title, t1, tw - timeW - l.pxf(14f), android.text.TextUtils.TruncateAt.END).toString(), tx, l.pxf(44f), t1)
        c.drawText(time, w - l.pxf(20f) - timeW, l.pxf(44f), t2)
        val sub = (if (note.own) "" else note.app + "  ·  ") + note.text
        if (!f) { c.drawText(android.text.TextUtils.ellipsize(sub, t2, tw, android.text.TextUtils.TruncateAt.END).toString(), tx, l.pxf(76f), t2); return }
        // Focused: the whole text (up to four lines), then what the keys do.
        val sl = android.text.StaticLayout.Builder.obtain(sub, 0, sub.length, t2, tw.toInt()).setMaxLines(4).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save(); c.translate(tx, l.pxf(60f)); sl.draw(c); c.restore()
        var cx = tx; val cy = h - l.pxf(46f)
        for ((icon, label) in listOf(I.PLAY to "Open", I.REMOVE to "Hold OK to dismiss")) {
            val lw = t2.measureText(label) + l.pxf(30f + 10f + 28f)
            r.set(cx, cy - l.pxf(20f), cx + lw, cy + l.pxf(20f)); bg.color = if (icon == I.PLAY) Colors.INK else 0xFFECECF0.toInt(); c.drawRoundRect(r, l.pxf(10f), l.pxf(10f), bg)
            val col = if (icon == I.PLAY) Colors.WHITE else Colors.INK
            ic.color = col; I.draw(icon, c, cx + l.pxf(14f + 13f), cy, l.pxf(26f), ic, 1f)
            t2.color = col; c.drawText(label, cx + l.pxf(14f + 26f + 10f), cy - (t2.descent() + t2.ascent()) / 2f, t2)
            cx += lw + l.pxf(12f)
        }
    }
}
