package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.content.ContentItem
import com.eppletv.launcher.content.ContentRepository
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style
import com.eppletv.launcher.ui.sentences

interface PageHost : TileHost {
    /** [deep] = focus is in the app grid below the top row (artwork gives way to the grid). */
    fun onPageDepth(deep: Boolean)
}

internal fun makeShadow(l: Layout): Bitmap {
    val p = l.shadowPad
    val b = Bitmap.createBitmap(l.tileW + 2 * p, l.tileH + 2 * p, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xC0000000.toInt(); maskFilter = BlurMaskFilter(p * 0.6f, BlurMaskFilter.Blur.NORMAL)
    }
    Canvas(b).drawRoundRect(RectF(p.toFloat(), p.toFloat(), (p + l.tileW).toFloat(), (p + l.tileH).toFloat()), l.corner, l.corner, paint)
    b.prepareToDraw()
    return b
}

/**
 * Home: the top row of pinned apps on a glass platter, and every other app in a grid below it.
 * Focus movement is explicit: this class decides where each key press lands, so it is always restorable.
 */
class HomePage(ctx: Context, private val l: Layout, private val clock: FrameClock, private val host: PageHost, private val shadow: Bitmap,
               backdrop: Backdrop) : FrameClock.Tick {
    val view = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val platter = GlassPlatter(ctx, l, backdrop, clock)
    private val strip = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val dock = ArrayList<Tile>()
    private val grid = ArrayList<Tile>()
    private var r = 0; private var c = 0; private var first = 0
    private val sy = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val sx = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val shelf = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS)

    private val rowCount get() = 1 + (grid.size + Layout.COLS - 1) / Layout.COLS
    private fun rowSize(row: Int) = if (row == 0) dock.size else minOf(Layout.COLS, grid.size - (row - 1) * Layout.COLS)
    private fun tile(): Tile? = if (r == 0) dock.getOrNull(c) else grid.getOrNull((r - 1) * Layout.COLS + c)

    /**
     * Before the apps are in: the top row's glass bar with [n] tile-shaped placeholders in the tiles' places, so Home's
     * structure is there from the first frame. [setApps] replaces them.
     */
    fun skeleton(n: Int) {
        if (dock.isNotEmpty() || n <= 0) return
        view.removeAllViews(); strip.removeAllViews()
        val p = l.platterPad
        view.addView(platter, FrameLayout.LayoutParams(l.px(1920) - 2 * (l.left - p), l.tileH + 2 * p).apply { leftMargin = l.left - p; topMargin = l.dockY - p })
        platter.visibility = View.VISIBLE
        view.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, l.tileH).apply { topMargin = l.dockY })
        repeat(minOf(n, Layout.COLS)) { i ->
            strip.addView(View(view.context).apply { background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = l.corner.toFloat(); setColor(0x1FFFFFFF) } },
                FrameLayout.LayoutParams(l.tileW, l.tileH).apply { leftMargin = l.left + i * l.pitchX })
        }
    }

    fun setApps(pinned: List<AppEntry>, rest: List<AppEntry>) {
        val keep = tile()?.entry?.packageName
        view.removeAllViews(); strip.removeAllViews(); dock.clear(); grid.clear()
        val p = l.platterPad
        view.addView(platter, FrameLayout.LayoutParams(l.px(1920) - 2 * (l.left - p), l.tileH + 2 * p).apply { leftMargin = l.left - p; topMargin = l.dockY - p })
        platter.visibility = if (pinned.isEmpty()) View.INVISIBLE else View.VISIBLE
        view.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, l.tileH).apply { topMargin = l.dockY })
        pinned.forEachIndexed { i, app ->
            val t = Tile(view.context, app, shadow, null, l, clock, host).apply { inDock = true }
            strip.addView(t, FrameLayout.LayoutParams(l.tileW, l.tileH).apply { leftMargin = l.left + i * l.pitchX })
            dock.add(t)
        }
        val extra = l.px(22)
        rest.forEachIndexed { i, app ->
            val x = l.left + (i % Layout.COLS) * l.pitchX; val y = l.rowY(1 + i / Layout.COLS)
            val label = TextView(view.context).apply {
                text = app.label; style(Txt.LABEL, l.u); alpha = 0f; gravity = Gravity.CENTER
                setShadowLayer(l.pxf(10f), 0f, l.pxf(2f), 0x99000000.toInt())
            }
            view.addView(label, FrameLayout.LayoutParams(l.tileW + 2 * extra, l.labelH).apply { leftMargin = x - extra; topMargin = y + l.tileH + l.labelTop })
            val t = Tile(view.context, app, shadow, label, l, clock, host)
            view.addView(t, FrameLayout.LayoutParams(l.tileW, l.tileH).apply { leftMargin = x; topMargin = y })
            grid.add(t)
        }
        view.layoutParams?.let { it.height = l.height(rowCount); view.layoutParams = it }
        if (keep == null || !locate(keep)) {
            r = r.coerceIn(if (dock.isEmpty()) 1 else 0, rowCount - 1)
            c = c.coerceIn(0, maxOf(0, rowSize(r) - 1))
        }
        place()
    }

    /** Points the cursor at [pkg] if it is on the page. */
    fun locate(pkg: String): Boolean {
        val d = dock.indexOfFirst { it.entry.packageName == pkg }
        if (d >= 0) { r = 0; c = d; return true }
        val g = grid.indexOfFirst { it.entry.packageName == pkg }
        if (g >= 0) { r = 1 + g / Layout.COLS; c = g % Layout.COLS; return true }
        return false
    }

    /** Tiles of the current row focused since the row was entered (columns). */
    private val visited = HashSet<Int>()

    /** Focus left the top row (Up, Back or Home): its next visit starts again at its first tile. */
    fun startOver() { visited.clear(); if (r == 0) { c = 0; first = 0; place() } }

    /** Returns false only when moving up from the top row. */
    fun move(dx: Int, dy: Int): Boolean {
        if (tile() == null) return dy >= 0
        // A row stops at its ends until each of its tiles has been focused in this visit of the row; then it goes
        // round (Left on the first tile reaches the last, Right on the last the first). Changing rows ends the visit.
        if (dx != 0) {
            val n = rowSize(r); if (n < 2) return true
            visited.add(c)
            val next = c + dx
            c = if (visited.size >= n) ((next % n) + n) % n else next.takeIf { it in 0 until n } ?: return true
            visited.add(c); focusCurrent(); return true
        }
        val nr = r + dy
        if (nr < 0 || (nr == 0 && dock.isEmpty())) { startOver(); return false }   // left the page: the top row starts again at its first tile
        if (nr >= rowCount) return true
        visited.clear()
        val col = if (r == 0) c - first else c            // keep the same on-screen column
        r = nr
        c = (if (r == 0) first + col else col).coerceIn(0, rowSize(r) - 1)
        focusCurrent()
        return true
    }

    private fun place() {
        if (r == 0) { if (c < first) first = c; if (c > first + Layout.COLS - 1) first = c - Layout.COLS + 1 }
        first = first.coerceIn(0, maxOf(0, dock.size - Layout.COLS))
        sx.target = -(first * l.pitchX).toFloat(); sy.target = l.scroll(r)
        clock.add(this)
    }

    fun focusCurrent(): Boolean {
        val t = tile() ?: return false
        place(); t.requestFocus(); host.onPageDepth(r > 0)
        platter.focusAt(if (r == 0) (l.platterPad + (c - first) * l.pitchX + l.tileW / 2).toFloat() else null)
        return true
    }

    fun atTop() = r == 0 || dock.isEmpty() && r == 1
    /** Focus left the page for the menu or the clock: the stand's light goes out. */
    fun blur() = platter.focusAt(null)
    fun toTop() { r = if (dock.isEmpty()) 1 else 0; c = c.coerceIn(0, maxOf(0, rowSize(r) - 1)); focusCurrent() }

    /** Full-screen artwork mode: the apps slide down out of the way. */
    fun shelf(on: Boolean) { shelf.target = if (on) 1f else 0f; clock.add(this) }

    override fun tick(dt: Float): Boolean {
        val vertical = !(sy.atRest() && shelf.atRest())
        sx.step(dt); sy.step(dt); shelf.step(dt)
        strip.translationX = sx.value
        val s = shelf.value.coerceIn(0f, 1f)
        view.translationY = sy.value + s * l.pxf(300f)
        view.alpha = 1f - s
        if (vertical) platter.moved()          // the glass re-samples only when it moves over the artwork
        return !(sx.atRest() && sy.atRest() && shelf.atRest())
    }
}

/**
 * Full-screen backdrop. Two layers; the top one fades over an opaque bottom, so a change never dips through black.
 */
class Backdrop(ctx: Context, private val clock: FrameClock) : FrameClock.Tick {
    val bottom = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val top = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val fade = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS)
    private var topIsFront = false
    private var current: Bitmap? = null
    /** Views that sample the backdrop (the glass platter, the tab bar) redraw when it changes. */
    val listeners = ArrayList<() -> Unit>()
    val currentBitmap: Bitmap? get() = current
    val bottomBitmap: Bitmap? get() = (bottom.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    val topBitmap: Bitmap? get() = (top.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    private var dimLevel = 0
    /** Darkens both layers by [v] (0..1) inside their own draw: no full-screen overlay pass. */
    fun dim(v: Float) {
        val lvl = (v.coerceIn(0f, 1f) * 255).toInt()
        if (lvl == dimLevel) return
        dimLevel = lvl
        val f = if (lvl == 0) null else android.graphics.PorterDuffColorFilter(android.graphics.Color.argb(lvl, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP)
        bottom.colorFilter = f; top.colorFilter = f
    }
    val topAlpha: Float get() = if (top.visibility == View.VISIBLE) top.alpha else 0f
    val bottomVisible: Boolean get() = bottom.visibility == View.VISIBLE

    /** [drift]: a slow zoom in and out (Home's own wallpapers); everything else stands still. */
    fun show(b: Bitmap, drift: Boolean = false) {
        if (b === current) { drift(drift); return }
        current = b
        if (bottom.drawable == null) { bottom.setImageBitmap(b); listeners.forEach { it() }; drift(drift); return }
        if (topIsFront) { bottom.setImageBitmap(b); fade.target = 0f; topIsFront = false }
        else { top.setImageBitmap(b); fade.target = 1f; topIsFront = true }
        bottom.visibility = View.VISIBLE; top.visibility = View.VISIBLE
        // The incoming layer starts at its natural size; the outgoing one keeps its zoom while it fades out.
        front.animate().cancel(); front.scaleX = 1f; front.scaleY = 1f
        drifting = false; drift(drift)
        clock.add(this)
    }

    private val front: ImageView get() = if (topIsFront) top else bottom
    private var drifting = false
    private var driftPaused = false
    private var driftT = 0f

    private var lastInput = android.os.SystemClock.uptimeMillis()
    private var sinceApply = 0f

    /** A remote button was pressed: the drift holds where it is, and continues after a short quiet spell. */
    fun interacted() {
        lastInput = android.os.SystemClock.uptimeMillis()
        if (drifting && !driftPaused) clock.add(drifter)          // it may have stopped after the long idle
    }

    /**
     * Ken Burns drift on Home's wallpaper: the front layer zooms smoothly between 100 % and 108 % and back, one full
     * cycle every [DRIFT_PERIOD_S] seconds. Only the layer's transform changes (no redraw of the picture), but the
     * whole screen recomposites when it moves, so it is applied at ~20 fps (the motion is far under a pixel per step).
     * It holds still while the remote is in use (the GPU goes to focus motion) and stops after [DRIFT_STOP_MS] without
     * input. Holding and stopping keep the current zoom and time, so it always continues from where it was: no jump.
     */
    private val drifter = object : FrameClock.Tick {
        override fun tick(dt: Float): Boolean {
            if (!drifting || driftPaused) return false
            val quiet = android.os.SystemClock.uptimeMillis() - lastInput
            if (quiet > DRIFT_STOP_MS) return false
            if (quiet < DRIFT_HOLD_MS) return true
            driftT += dt; sinceApply += dt
            if (sinceApply >= DRIFT_STEP_S) {
                sinceApply = 0f
                val s = 1f + (DRIFT_SCALE - 1f) * 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * driftT / DRIFT_PERIOD_S).toFloat())
                front.scaleX = s; front.scaleY = s
            }
            return true
        }
    }

    fun drift(on: Boolean) {
        if (on == drifting) return
        drifting = on
        if (on) { driftT = 0f; if (!driftPaused) clock.add(drifter) }
        else front.animate().scaleX(1f).scaleY(1f).setDuration(700).start()
    }

    /** The window is hidden (another app is in front): stop the drift, resume it on return. */
    fun pauseDrift(paused: Boolean) {
        if (paused == driftPaused) return
        driftPaused = paused
        if (!paused && drifting) clock.add(drifter)
    }

    private companion object {
        const val DRIFT_PERIOD_S = 44f; const val DRIFT_SCALE = 1.08f
        const val DRIFT_STEP_S = 0.048f            // ~20 fps (three 60 Hz frames, with a little slack for timing jitter)
        const val DRIFT_HOLD_MS = 1500L            // quiet time after a button press before the drift continues
        const val DRIFT_STOP_MS = 3 * 60_000L      // no input for 3 minutes: the drift stops until the next press
    }

    override fun tick(dt: Float): Boolean {
        fade.step(dt); top.alpha = fade.value; listeners.forEach { it() }
        if (!fade.atRest()) return true
        // At rest only one full-screen layer is drawn: the GPU on this TV has little fill rate to spare at 1080p.
        if (fade.value >= 1f) bottom.visibility = View.INVISIBLE else top.visibility = View.INVISIBLE
        return false
    }
}

/**
 * The cinematic area: full-screen artwork for the focused app's content, with title and metadata at the top left.
 * Apps without a content source show their own colours and name instead, never invented content.
 */
class Hero(ctx: Context, private val l: Layout, private val clock: FrameClock, private val repo: ContentRepository, root: FrameLayout) : FrameClock.Tick {
    /** The next title arrives in [ms]: the active dot fills over that time (0: no rotation). */
    fun rotationTimer(ms: Long) = if (ms > 0) dots.timer(ms) else dots.stopTimer()
    val backdrop = Backdrop(ctx, clock)
    private companion object { const val CHIP = "play-chip" }
    private class Layer(val box: LinearLayout, val eyebrow: TextView, val title: TextView, val logo: ImageView, val meta: MetaRow, val summary: TextView, val a: Spring)
    private var wantLogo: String? = null
    private val textBox = FrameLayout(ctx)
    private val layers: Array<Layer>
    private val dots = Dots(ctx, l, clock)
    private var front = 0
    private var shown: String? = null
    private var entry: AppEntry? = null
    private var items: List<ContentItem> = emptyList()
    private var index = 0
    private var want: String? = null
    private val textVis = Spring(1f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private val shelf = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private var deep = false

    private val dotsY = l.dockY - l.platterPad - l.px(40)
    private val textBoxH = l.px(560)   // ends at 730, above the page dots
    /** Showing the Home wallpaper: no title, no dots, no carousel. */
    var resting = false
        private set

    val item: ContentItem? get() = items.getOrNull(index)
    val hasItems get() = items.isNotEmpty()
    val count get() = items.size

    init {
        root.addView(backdrop.bottom, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(backdrop.top, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The scrims (top for the title, bottom for the app rows, top-left corner under the text) are painted into each
        // backdrop image once when it is decoded (see Scrim): as overlay views they cost two translucent full-screen passes a frame.
        root.addView(textBox, FrameLayout.LayoutParams(l.px(1100), textBoxH).apply { leftMargin = l.left; topMargin = l.px(170) })
        layers = Array(2) { layer(ctx) }
        // The slider dots sit just above the top row; in full-screen artwork mode they slide down to the bottom edge.
        root.addView(dots, FrameLayout.LayoutParams(MATCH_PARENT, l.px(16)).apply { topMargin = dotsY })
        dots.alpha = 0f
    }

    /** Shows [items] for a top-row app (empty = the app's own colours and name). */
    /** [restart]: the user switched to this app, so its carousel starts again at the first item. */
    fun bind(e: AppEntry, list: List<ContentItem>, restart: Boolean = false) {
        val sameApp = entry?.packageName == e.packageName
        if (sameApp && list === items && !deep && !(restart && index != 0)) return
        val wasDeep = deep || resting
        entry = e; deep = false; resting = false
        if (!sameApp || list !== items || restart) { items = list; index = 0 }
        textVis.target = if (quietOn) 0f else 1f
        show(fresh = !sameApp || wasDeep)
    }

    /** Grid mode: only the focused app's colour field, no text. */
    fun field(e: AppEntry) {
        deep = true; want = null
        backdrop.show(e.backdrop)
        textVis.target = 0f; clock.add(this)
    }

    private var chipOn = false
    private val showChip = Runnable { layers.forEach { it.box.findViewWithTag<View>(CHIP)?.animate()?.alpha(if (chipOn) 1f else 0f)?.setDuration(160)?.start() } }
    /** The hero has focus or not: its Play chip shows only then, once focus rests on a title (not while stepping). */
    fun chip(on: Boolean) {
        if (chipOn == on && !on) return
        chipOn = on
        textBox.removeCallbacks(showChip)
        layers.forEach { it.box.findViewWithTag<View>(CHIP)?.let { v -> v.animate().cancel(); if (!on) v.alpha = 0f } }
        if (on) textBox.postDelayed(showChip, 280)
    }

    /** Titles shown while the remote was on the showcase (its own rotation doesn't count). */
    private val seen = HashSet<Int>()
    /** Left/Right from the remote: stops at the ends until every title has been seen in this visit, then goes round. */
    fun nav(d: Int): Boolean { seen.add(index); val ok = step(d, wrap = seen.size >= items.size); seen.add(index); return ok }
    /** The remote left the showcase: its visit ends. */
    fun leave() = seen.clear()

    fun step(d: Int, wrap: Boolean = false): Boolean {
        if (items.size < 2) return false
        val n = if (wrap) (index + d + items.size) % items.size else index + d
        if (n !in items.indices) return false
        index = n; show(fresh = false)
        // The Play chip stays on: each title's chip crossfades with its own text, so it moves to the new title's layout
        // without disappearing in between.
        if (chipOn) { textBox.removeCallbacks(showChip); layers.forEach { it.box.findViewWithTag<View>(CHIP)?.let { v -> v.animate().cancel(); v.alpha = 1f } } }
        return true
    }

    /** Home's own picture (a wallpaper): only the image, with no title text, dots or carousel. */
    fun rest(b: Bitmap) {
        resting = true; deep = false; entry = null; items = emptyList(); index = 0
        want = null; wantLogo = null; shown = null
        dots.set(0, 0)
        layers.forEach { it.a.target = 0f }
        backdrop.show(b, drift = true)
        textVis.target = 0f; clock.add(this)
    }

    /** Shows the current title's artwork again (returning to Home from another tab). */
    fun reshow() { if (entry != null && !deep) show(fresh = true) }

    private var quietOn = false
    /** Another section is in front: the title text fades out, the artwork stays as the shared backdrop. */
    fun quiet(on: Boolean) { quietOn = on; textVis.target = if (on || deep || resting) 0f else 1f; clock.add(this) }
    fun shelf(on: Boolean) { shelf.target = if (on) 1f else 0f; clock.add(this) }

    private fun show(fresh: Boolean) {
        val e = entry ?: return
        val it = item
        dots.set(items.size, index)
        if (it == null) {
            want = null; wantLogo = null; backdrop.show(e.backdrop); text("", e.label, "", "", null)
        } else {
            // Title logo when there is one: shown at once if it's already decoded; if it arrives later the text crossfades to it.
            val itemId = it.id; wantLogo = itemId
            var logo: Bitmap? = null; var sync = true
            repo.logo(it) { b -> if (sync) logo = b else if (b != null && wantLogo == itemId) text(it.kind.ifEmpty { e.label }, it.title, it.meta, it.summary, b) }
            sync = false
            text(it.kind.ifEmpty { e.label }, it.title, it.meta, it.summary, logo)
            val url = it.imageUrl; want = url
            var ready = false
            repo.image(url) { bmp -> ready = true; if (want == url && bmp != null) backdrop.show(bmp) }
            if (!ready && fresh) backdrop.show(e.backdrop)       // new app: its colours until the artwork arrives
            items.getOrNull((index + 1) % items.size)?.let { n -> repo.prefetch(n.imageUrl); repo.logo(n) {} }
        }
        clock.add(this)
    }

    private fun text(eyebrow: String, title: String, meta: String, summary: String, logo: Bitmap?) {
        val key = "$eyebrow\n$title\n$meta\n$summary\n${logo?.generationId ?: 0}@${System.identityHashCode(logo)}"
        if (key == shown) return
        shown = key
        layers[front].a.target = 0f
        front = 1 - front
        val y = layers[front]
        y.eyebrow.text = Brand.eyebrow(eyebrow); y.title.text = title; y.meta.set(meta)
        // The logo replaces the typeset title; it keeps its own proportions inside a 760x200 box (never stretched).
        y.logo.setImageBitmap(logo)
        y.logo.visibility = if (logo != null) View.VISIBLE else View.GONE
        y.title.visibility = if (logo != null) View.GONE else View.VISIBLE
        y.logo.contentDescription = title
        y.eyebrow.visibility = if (eyebrow.isEmpty()) View.GONE else View.VISIBLE
        y.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        // The summary gets only the whole lines left in the text box once everything above it and the Play chip are
        // placed, so the chip is never pushed out of the box (a tall logo or a long meta row leaves fewer lines).
        val lines = ((textBoxH - headHeight(y)) / y.summary.lineHeight).coerceIn(0, 4)
        y.summary.sentences(summary, l.px(820), lines, longest = lines); y.summary.visibility = if (y.summary.text.isEmpty()) View.GONE else View.VISIBLE
        y.a.target = 1f
    }

    override fun tick(dt: Float): Boolean {
        var moving = false
        for (y in layers) {
            y.a.step(dt)
            y.box.alpha = y.a.value; y.box.translationY = (1f - y.a.value) * l.pxf(12f)
            if (!y.a.atRest()) moving = true
        }
        textVis.step(dt); shelf.step(dt)
        textBox.alpha = textVis.value
        dots.alpha = (if (items.size > 1) 1f else 0f) * textVis.value
        dots.translationY = shelf.value * (l.px(1010) - dotsY)
        return moving || !textVis.atRest() || !shelf.atRest()
    }

    /** Everything in a layer except the summary's text, as laid out: eyebrow, title or logo, meta, summary margin, Play chip. */
    private fun headHeight(y: Layer): Int {
        val w = View.MeasureSpec.makeMeasureSpec(l.px(1100), View.MeasureSpec.AT_MOST); val u = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun h(v: View) = if (v.visibility == View.GONE) 0 else (v.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
            // A fixed height (the Play chip's 56) counts as laid out, not as the view's own measure.
            (if (lp.height > 0) lp.height else { v.measure(w, u); v.measuredHeight }) + lp.topMargin + lp.bottomMargin }
        val chip = y.box.findViewWithTag<View>(CHIP)
        return h(y.eyebrow) + h(y.title) + h(y.logo) + h(y.meta) + (y.summary.layoutParams as ViewGroup.MarginLayoutParams).topMargin + h(chip)
    }

    private fun layer(ctx: Context): Layer {
        fun shadowed(t: TextView, r: Float) = t.apply { setShadowLayer(l.pxf(r), 0f, l.pxf(2f), 0x99000000.toInt()) }
        val eyebrow = shadowed(TextView(ctx).apply { style(Txt.CAPTION, l.u, 0xD9FFFFFF.toInt()); isAllCaps = false }, 8f)
        val title = shadowed(TextView(ctx).apply { style(Txt.HERO, l.u) }, 20f)
        val meta = MetaRow(ctx, l).apply { backdrop = this@Hero.backdrop }
        val summary = shadowed(TextView(ctx).apply { style(Txt.BODY, l.u, 0xD9FFFFFF.toInt()) }, 10f)
        val logo = ImageView(ctx).apply {
            adjustViewBounds = true; maxWidth = l.px(760); maxHeight = l.px(200)
            scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f
            addView(eyebrow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(12) })
            addView(logo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = l.px(18); bottomMargin = l.px(6) })
            addView(meta, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(14) })
            addView(summary, LinearLayout.LayoutParams(l.px(820), WRAP_CONTENT).apply { topMargin = l.px(14) })
            // OK on the hero opens this title in its app: the Play chip says so.
            addView(KeyHints(ctx, l).apply { set("Play", more = false); alpha = 0f; tag = CHIP }, LinearLayout.LayoutParams(MATCH_PARENT, l.px(56)).apply { topMargin = l.px(20) })
        }
        textBox.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        return Layer(box, eyebrow, title, logo, meta, summary, Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f))
    }
}

/**
 * Slider page indicator: small dots, the current one stretched into a short white capsule. Moving to another title
 * slides the capsule's width from the old dot to the new one (one spring). At most [MAX] dots are drawn; a longer
 * carousel shows a window of them around the current title.
 */
@SuppressLint("ViewConstructor")
internal class Dots(ctx: Context, private val l: Layout, private val clock: FrameClock) : View(ctx), FrameClock.Tick {
    // A soft shadow keeps the dots visible over bright artwork.
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(l.pxf(4f), 0f, l.pxf(1f), 0x59000000) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.WHITE }
    private val r = RectF(); private val clip = android.graphics.Path()
    private var n = 0; private var i = 0; private var from = 0
    private val t = Spring(1f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    /** The active pill fills with white over the time until the next title (0 = no timer: it is simply white). */
    private var since = 0L; private var span = 0L
    private val refill = Runnable { invalidate() }
    fun set(count: Int, index: Int) {
        if (count == n && index == i) return
        from = if (count == n) i else index
        n = count; i = index
        if (from == i) t.snapTo(1f) else { t.snapTo(0f); t.target = 1f; clock.add(this) }
        invalidate()
    }
    /** The next title comes in [ms] (0: no automatic rotation). */
    fun timer(ms: Long) { since = android.os.SystemClock.uptimeMillis(); span = ms; invalidate() }
    fun stopTimer() { span = 0L; invalidate() }
    override fun tick(dt: Float): Boolean { t.step(dt); invalidate(); return !t.atRest() }
    override fun onDraw(c: Canvas) {
        if (n < 2) return
        val shown = minOf(n, MAX)
        val first = (i - shown / 2).coerceIn(0, n - shown)
        val d = l.pxf(9f); val wide = l.pxf(34f); val gap = l.pxf(11f)
        val total = (shown - 1) * (d + gap) + wide
        var x = width / 2f - total / 2f
        val cy = height / 2f
        val k = t.value.coerceIn(0f, 1f)
        val progress = if (span > 0) ((android.os.SystemClock.uptimeMillis() - since).toFloat() / span).coerceIn(0f, 1f) else 1f
        var activeW = 0f
        for (j in first until first + shown) {
            val grow = when (j) { i -> k; from -> 1f - k; else -> 0f }
            // Dots at an edge with more titles beyond it get smaller, as on tvOS.
            val fromEnd = minOf(if (first + shown < n) first + shown - 1 - j else 99, if (first > 0) j - first else 99)
            val s = if (grow > 0f) 1f else when (fromEnd) { 0 -> 0.45f; 1 -> 0.75f; else -> 1f }
            val dd = d * s
            val w = dd + (wide - dd) * grow
            p.color = 0x73FFFFFF
            r.set(x, cy - dd / 2f, x + w, cy + dd / 2f)
            c.drawRoundRect(r, dd / 2f, dd / 2f, p)
            if (j == i) {                                            // the white part of the active pill
                activeW = w
                val fw = w * (if (grow >= 0.99f) progress else 1f).coerceAtLeast(dd / w)
                c.save(); clip.reset(); clip.addRoundRect(r, dd / 2f, dd / 2f, android.graphics.Path.Direction.CW); c.clipPath(clip)
                c.drawRect(x, cy - dd / 2f, x + fw, cy + dd / 2f, fill); c.restore()
            }
            x += w + gap
        }
        // Redraw when the fill has grown by half a pixel (a few times a second), not at a fixed rate: each redraw
        // recomposites the whole screen, and on this GPU an idle Home otherwise renders ~47 frames a second.
        // One pending redraw at most: posting a new one from every onDraw stacked up chains until the screen ran at 60 fps.
        removeCallbacks(refill)
        if (span > 0 && progress < 1f) postDelayed(refill, (span * 0.5f / activeW.coerceAtLeast(1f)).toLong().coerceIn(50L, 500L))
    }
    private companion object { const val MAX = 9 }
}

/**
 * The hero scrim, painted into a backdrop bitmap of any size (it scales with the image, which fills the screen):
 * a top-to-bottom darkening for the title and the app rows, plus an elliptical darkening from the top-left corner
 * under the title text. The ellipse is drawn over the whole image and fades to nothing well inside it, so it never
 * ends in an edge (it used to be clipped to a rectangle, which showed as a dark band across the artwork).
 */
object Scrim {
    private val linear = intArrayOf(0x8C000000.toInt(), 0x40000000, 0x0D000000, 0x14000000, 0x59000000, 0xA6000000.toInt())
    private val linearAt = floatArrayOf(0f, 0.18f, 0.42f, 0.6f, 0.8f, 1f)
    private val radial = intArrayOf(0x99000000.toInt(), 0x80000000.toInt(), 0x4D000000, 0x1F000000, 0x08000000, 0x00000000)
    private val radialAt = floatArrayOf(0f, 0.2f, 0.45f, 0.7f, 0.88f, 1f)
    fun paint(b: Bitmap) {
        if (!b.isMutable) return
        val w = b.width.toFloat(); val h = b.height.toFloat(); val c = Canvas(b)
        c.drawRect(0f, 0f, w, h, Paint().apply { isDither = true; shader = android.graphics.LinearGradient(0f, 0f, 0f, h, linear, linearAt, android.graphics.Shader.TileMode.CLAMP) })
        // A circle of radius w*0.78, squashed to an ellipse 0.78w x 0.86h; transparent at its rim and beyond.
        val g = android.graphics.RadialGradient(0f, 0f, w * 0.78f, radial, radialAt, android.graphics.Shader.TileMode.CLAMP)
        g.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, (h * 0.86f) / (w * 0.78f)) })
        c.drawRect(0f, 0f, w, h, Paint().apply { isDither = true; shader = g })
    }
}

/**
 * "New on Netflix", "Trending on YouTube": the service's name in bold and its brand colour, the rest as the usual
 * upper-case eyebrow. (Upper-cased here: the platform's all-caps transformation would drop the styling.)
 */
object Brand {
    /** Exact brand colours for the default apps; every other app's colour is learned from its own tile ([learn]). */
    private val fixed = linkedMapOf(
        "Prime Video" to 0xFF1A98FF.toInt(), "Netflix" to 0xFFE50914.toInt(), "Crunchyroll" to 0xFFF47521.toInt(),
        "YouTube" to 0xFFFF2D2D.toInt(), "Stremio" to 0xFF8F6BFF.toInt(), "tapmad" to 0xFF3BD16F.toInt(),
    )
    @Volatile private var learned: Map<String, Int> = emptyMap()

    /** Worker or main thread: each installed app's colour from its tile artwork (cheap: a 24x14 sample per app). */
    fun learn(apps: List<com.eppletv.launcher.apps.AppEntry>) {
        val m = HashMap<String, Int>()
        for (e in apps) if (fixed.keys.none { it.equals(e.label, true) } && e.label.length >= 3) m[e.label] = colorOf(e.banner)
        learned = m
    }

    /**
     * An app's brand colour, readable on the dark hero: the one hue that clearly dominates its tile's colourful pixels,
     * lifted if it is too dark to read. A tile with no single dominant hue (Apple TV's rainbow mark on black) or no colour
     * at all (black or white tiles) gives white: the name stays bold and legible over any artwork.
     */
    fun colorOf(b: android.graphics.Bitmap): Int {
        val s = android.graphics.Bitmap.createScaledBitmap(b, 24, 14, true)
        val hsv = FloatArray(3); val buckets = IntArray(12); val sums = Array(12) { FloatArray(3) }
        var colourful = 0
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val c = s.getPixel(x, y); if ((c ushr 24) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.45f || hsv[2] < 0.35f) continue
            val k = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            buckets[k]++; colourful++
            sums[k][0] += android.graphics.Color.red(c).toFloat(); sums[k][1] += android.graphics.Color.green(c).toFloat(); sums[k][2] += android.graphics.Color.blue(c).toFloat()
        }
        if (s !== b) s.recycle()
        val top = buckets.indices.maxByOrNull { buckets[it] } ?: return Colors.WHITE
        if (colourful < 24 || buckets[top] < colourful * 0.55f) return Colors.WHITE
        val n = buckets[top].toFloat()
        var c = android.graphics.Color.rgb((sums[top][0] / n).toInt(), (sums[top][1] / n).toInt(), (sums[top][2] / n).toInt())
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[2] < 0.85f) { hsv[2] = 0.95f; c = android.graphics.Color.HSVToColor(hsv) }          // bright enough for the dark hero
        return c
    }

    /** An app's own colour for the stand's light: its brand colour, else the one learned from its tile, else white. */
    fun of(e: com.eppletv.launcher.apps.AppEntry): Int =
        fixed.entries.firstOrNull { it.key.equals(e.label, true) }?.value ?: learned[e.label] ?: Colors.WHITE

    fun eyebrow(text: String): CharSequence {
        val up = text.uppercase()
        val out = android.text.SpannableString(up)
        // The longest name that appears wins ("Apple TV" over "TV").
        val all = (fixed + learned).entries.sortedByDescending { it.key.length }
        for ((name, color) in all) {
            val i = up.indexOf(name.uppercase()); if (i < 0) continue
            val end = i + name.length
            val face = com.eppletv.launcher.ui.Type.of(800, com.eppletv.launcher.ui.Type.Face.HEAD)
            out.setSpan(android.text.style.ForegroundColorSpan(color), i, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(android.text.style.TypefaceSpan(face), i, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            break
        }
        return out
    }
}
