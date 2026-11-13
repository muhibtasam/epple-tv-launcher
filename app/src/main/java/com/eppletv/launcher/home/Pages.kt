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
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style

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

    /** Returns false only when moving up from the top row. */
    fun move(dx: Int, dy: Int): Boolean {
        if (tile() == null) return dy >= 0
        if (dx != 0) { if (c + dx in 0 until rowSize(r)) { c += dx; focusCurrent() }; return true }
        val nr = r + dy
        if (nr < 0 || (nr == 0 && dock.isEmpty())) return false
        if (nr >= rowCount) return true
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
    var onChange: (() -> Unit)? = null
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

    fun show(b: Bitmap) {
        if (b === current) return
        current = b
        if (bottom.drawable == null) { bottom.setImageBitmap(b); onChange?.invoke(); return }
        if (topIsFront) { bottom.setImageBitmap(b); fade.target = 0f; topIsFront = false }
        else { top.setImageBitmap(b); fade.target = 1f; topIsFront = true }
        bottom.visibility = View.VISIBLE; top.visibility = View.VISIBLE
        clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        fade.step(dt); top.alpha = fade.value; onChange?.invoke()
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
    val backdrop = Backdrop(ctx, clock)
    private class Layer(val box: LinearLayout, val eyebrow: TextView, val title: TextView, val logo: ImageView, val meta: TextView, val summary: TextView, val a: Spring)
    private var wantLogo: String? = null
    private val textBox = FrameLayout(ctx)
    private val layers: Array<Layer>
    private val dots = Dots(ctx, l)
    private val hint = Chevron(ctx, l)
    private var front = 0
    private var shown: String? = null
    private var entry: AppEntry? = null
    private var items: List<ContentItem> = emptyList()
    private var index = 0
    private var want: String? = null
    private val textVis = Spring(1f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private val shelf = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private var deep = false

    val item: ContentItem? get() = items.getOrNull(index)
    val hasItems get() = items.isNotEmpty()
    val count get() = items.size

    init {
        root.addView(backdrop.bottom, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(backdrop.top, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The scrims (top for the title, bottom for the app rows, top-left corner under the text) are painted into each
        // backdrop image once when it is decoded (see Scrim): as overlay views they cost two translucent full-screen passes a frame.
        root.addView(textBox, FrameLayout.LayoutParams(l.px(1100), l.px(420)).apply { leftMargin = l.left; topMargin = l.px(84) })
        layers = Array(2) { layer(ctx) }
        root.addView(hint, FrameLayout.LayoutParams(l.px(44), l.px(16), Gravity.CENTER_HORIZONTAL).apply { topMargin = l.dockY - l.platterPad - l.px(34) })
        root.addView(dots, FrameLayout.LayoutParams(MATCH_PARENT, l.px(12)).apply { topMargin = l.px(1010) })
        dots.alpha = 0f; hint.alpha = 0f
    }

    /** Shows [items] for a top-row app (empty = the app's own colours and name). */
    /** [restart]: the user switched to this app, so its carousel starts again at the first item. */
    fun bind(e: AppEntry, list: List<ContentItem>, restart: Boolean = false) {
        val sameApp = entry?.packageName == e.packageName
        if (sameApp && list === items && !deep && !(restart && index != 0)) return
        val wasDeep = deep
        entry = e; deep = false
        if (!sameApp || list !== items || restart) { items = list; index = 0 }
        textVis.target = 1f
        show(fresh = !sameApp || wasDeep)
    }

    /** Grid mode: only the focused app's colour field, no text. */
    fun field(e: AppEntry) {
        deep = true; want = null
        backdrop.show(e.backdrop)
        textVis.target = 0f; clock.add(this)
    }

    fun step(d: Int, wrap: Boolean = false): Boolean {
        if (items.size < 2) return false
        val n = if (wrap) (index + d + items.size) % items.size else index + d
        if (n !in items.indices) return false
        index = n; show(fresh = false)
        return true
    }

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
        y.eyebrow.text = eyebrow; y.title.text = title; y.meta.text = meta
        // The logo replaces the typeset title; it keeps its own proportions inside a 760x200 box (never stretched).
        y.logo.setImageBitmap(logo)
        y.logo.visibility = if (logo != null) View.VISIBLE else View.GONE
        y.title.visibility = if (logo != null) View.GONE else View.VISIBLE
        y.logo.contentDescription = title
        y.eyebrow.visibility = if (eyebrow.isEmpty()) View.GONE else View.VISIBLE
        y.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        y.summary.text = summary; y.summary.visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
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
        dots.alpha = shelf.value * textVis.value
        hint.alpha = (if (items.isNotEmpty()) 0.75f else 0f) * (1f - shelf.value) * textVis.value
        return moving || !textVis.atRest() || !shelf.atRest()
    }

    private fun layer(ctx: Context): Layer {
        fun shadowed(t: TextView, r: Float) = t.apply { setShadowLayer(l.pxf(r), 0f, l.pxf(2f), 0x99000000.toInt()) }
        val eyebrow = shadowed(TextView(ctx).apply { style(Txt.CAPTION, l.u, 0xD9FFFFFF.toInt()) }, 8f)
        val title = shadowed(TextView(ctx).apply { style(Txt.HERO, l.u) }, 20f)
        val meta = shadowed(TextView(ctx).apply { style(Txt.META, l.u, 0xE6FFFFFF.toInt()) }, 10f)
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
            addView(meta, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(16) })
            addView(summary, LinearLayout.LayoutParams(l.px(820), WRAP_CONTENT).apply { topMargin = l.px(14) })
        }
        textBox.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        return Layer(box, eyebrow, title, logo, meta, summary, Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f))
    }
}

/** Page indicator for full-screen artwork mode. */
@SuppressLint("ViewConstructor")
private class Dots(ctx: Context, private val l: Layout) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var n = 0; private var i = 0
    fun set(count: Int, index: Int) { if (count != n || index != i) { n = count; i = index; invalidate() } }
    override fun onDraw(c: Canvas) {
        if (n < 2) return
        val gap = l.pxf(22f); val x0 = width / 2f - gap * (n - 1) / 2f
        for (k in 0 until n) { p.color = if (k == i) 0xFFFFFFFF.toInt() else 0x66FFFFFF; c.drawCircle(x0 + k * gap, height / 2f, l.pxf(if (k == i) 5f else 4f), p) }
    }
}

/** Small upward chevron above the top row: more of this content is one press up. */
@SuppressLint("ViewConstructor")
private class Chevron(ctx: Context, l: Layout) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = l.pxf(4f); strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val m = p.strokeWidth
        c.drawLine(m, h - m, w / 2f, m, p); c.drawLine(w / 2f, m, w - m, h - m, p)
    }
}

/**
 * The hero scrim, painted into a backdrop bitmap of any size (it scales with the image, which fills the screen):
 * a top-to-bottom darkening for the title and the app rows, plus a radial darkening from the top-left corner,
 * limited to the 1250x760 (of 1920x1080) corner it covered as a view.
 */
object Scrim {
    private val linear = intArrayOf(0x8C000000.toInt(), 0x0D000000, 0xA6000000.toInt())
    private val radial = intArrayOf(0x99000000.toInt(), 0x4D000000, 0x00000000)
    fun paint(b: Bitmap) {
        if (!b.isMutable) return
        val w = b.width.toFloat(); val h = b.height.toFloat(); val c = Canvas(b)
        c.drawRect(0f, 0f, w, h, Paint().apply { isDither = true; shader = android.graphics.LinearGradient(0f, 0f, 0f, h, linear, null, android.graphics.Shader.TileMode.CLAMP) })
        val k = w / 1920f
        c.drawRect(0f, 0f, 1250f * k, 760f * h / 1080f, Paint().apply { isDither = true
            shader = android.graphics.RadialGradient(0f, 0f, 1150f * k, radial, null, android.graphics.Shader.TileMode.CLAMP)
        })
    }
}
