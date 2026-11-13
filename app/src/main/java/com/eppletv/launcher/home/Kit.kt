package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eppletv.launcher.content.ContentItem
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Pill
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style
import kotlin.math.max
import kotlin.math.min

/** Design tokens shared by every section (1080p canvas px). */
object Look {
    const val CARD_W = 384; const val CARD_H = 216          // landscape content card, 16:9
    const val SMALL_W = 336; const val SMALL_H = 189        // episodes, seasons, search results
    const val GAP = 40                                       // between cards
    const val ROW_TITLE = 58                                 // row title band above the cards
    const val CARD_LABEL = 70                                // title under a card
    const val FOCUS_SCALE = 0.10f
    const val INFO_TOP = 150                                 // info block (title, meta, summary) under the tab bar
    const val ROWS_TOP = 540                                 // first row of a browse screen
    const val PLACEHOLDER = 0xFF2A2D35.toInt()
}

private val shadowCache = HashMap<Long, Bitmap>()
private val rimCache = HashMap<Long, Bitmap>()

/** Soft drop shadow for a card of w x h, rendered once per size. */
internal fun shadowFor(l: Layout, w: Int, h: Int): Bitmap = shadowCache.getOrPut((w.toLong() shl 32) or h.toLong()) {
    val p = l.shadowPad
    val b = Bitmap.createBitmap(w + 2 * p, h + 2 * p, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0000000.toInt(); maskFilter = BlurMaskFilter(p * 0.6f, BlurMaskFilter.Blur.NORMAL) }
    Canvas(b).drawRoundRect(RectF(p.toFloat(), p.toFloat(), (p + w).toFloat(), (p + h).toFloat()), l.corner, l.corner, paint)
    b.prepareToDraw(); b
}

/** Gloss and light rim for a focused card of w x h, rendered once per size (live gradients are costly on this GPU). */
internal fun rimFor(l: Layout, w: Int, h: Int): Bitmap = rimCache.getOrPut((w.toLong() shl 32) or h.toLong()) {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x05FFFFFF, 0x00000000, 0x14000000))
        .apply { cornerRadius = l.corner; setBounds(0, 0, w, h); draw(c) }
    paintRim(c, w, h, l.corner, l.pxf(2f).coerceAtLeast(1f), 255)
    b.prepareToDraw(); b
}

/** Artwork with rounded corners, centre-cropped through a bitmap shader (no view clipping); a flat fill until it loads. */
@SuppressLint("ViewConstructor")
class ArtView(ctx: Context, private val corner: Float) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.PLACEHOLDER }
    private val rect = RectF(); private val m = Matrix()
    var bitmap: Bitmap? = null
        set(v) { field = v; paint.shader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; update(); invalidate() }
    var fillColor: Int
        get() = fill.color
        set(v) { fill.color = v; invalidate() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rect.set(0f, 0f, w.toFloat(), h.toFloat()); update() }
    private fun update() {
        val b = bitmap ?: return; if (width == 0) return
        val s = max(width.toFloat() / b.width, height.toFloat() / b.height)
        m.setScale(s, s); m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        paint.shader?.setLocalMatrix(m)
    }
    override fun onDraw(c: Canvas) { c.drawRoundRect(rect, corner, corner, if (bitmap != null) paint else fill) }
}

/**
 * A focusable content card: artwork, a placeholder label until (or unless) artwork arrives, and a title underneath.
 * Focus scales it up with a soft shadow and a light rim; at rest it is a single opaque bitmap draw.
 * OK and a long OK reach [onClick] / [onLongClick] through the platform's click handling.
 */
@SuppressLint("ViewConstructor")
class Card(ctx: Context, private val l: Layout, private val clock: FrameClock, val w: Int, val h: Int, private val titled: Boolean = true) :
    FrameLayout(ctx), FrameClock.Tick {
    var item: ContentItem? = null
    var tag2: Any? = null                       // anything else the card stands for (an app, a season, a key)
    var requested = false
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val shadow = ImageView(ctx).apply { setImageBitmap(shadowFor(l, w, h)); scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f; visibility = INVISIBLE }
    val art = ArtView(ctx, l.corner)
    private val ph = TextView(ctx).apply {
        style(Txt.ROW, l.u, Colors.TEXT_DIM); gravity = Gravity.CENTER; isSingleLine = false; maxLines = 2
        val p = l.px(20); setPadding(p, p, p, p)
    }
    private val rim = ImageView(ctx).apply { setImageBitmap(rimFor(l, w, h)); scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f; visibility = INVISIBLE }
    private val title = TextView(ctx).apply { style(Txt.LABEL, l.u, Colors.TEXT_DIM) }
    private val sub = TextView(ctx).apply { style(Txt.FOOT, l.u, 0x80FFFFFF.toInt()); maxLines = 1; isSingleLine = true }

    init {
        isFocusable = true; isClickable = true; isLongClickable = true; clipChildren = false; clipToPadding = false
        val p = l.shadowPad
        addView(shadow, LayoutParams(w + 2 * p, h + 2 * p).apply { leftMargin = -p; topMargin = -p + l.px(14) })
        addView(art, LayoutParams(w, h)); addView(ph, LayoutParams(w, h)); addView(rim, LayoutParams(w, h))
        if (titled) {
            // Fixed heights: below the card's own bounds a WRAP_CONTENT child would be measured at zero height.
            addView(title, LayoutParams(w, l.px(38)).apply { topMargin = h + l.px(14) })
            addView(sub, LayoutParams(w, l.px(30)).apply { topMargin = h + l.px(50) })
        }
    }

    fun bind(text: String, subtitle: String = "", placeholder: String = text) {
        title.text = text; sub.text = subtitle; ph.text = placeholder; contentDescription = text
    }

    fun setBitmap(b: Bitmap?) { art.bitmap = b; ph.visibility = if (b == null) VISIBLE else GONE }

    /** Drops the artwork (it reloads from the memory or disk cache when the card comes back into view). */
    fun release() { if (art.bitmap != null || requested) { requested = false; setBitmap(null) } }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) { pivotX = w / 2f; pivotY = h / 2f }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        f.step(dt)
        val v = f.value
        val s = 1f + Look.FOCUS_SCALE * v
        scaleX = s; scaleY = s
        translationZ = v * l.pxf(8f)
        shadow.alpha = v; shadow.visibility = if (v > 0.002f) VISIBLE else INVISIBLE
        rim.alpha = v; rim.visibility = shadow.visibility
        title.setTextColor(Colors.lerp(Colors.TEXT_DIM, Colors.WHITE, v))
        // Titles stay put on screen while the card grows (the card scales around its centre).
        val down = h * (s - 1f) / 2f / s
        title.translationY = down; sub.translationY = down
        return !f.atRest()
    }
}

/** A titled horizontal row of cards. Images load only for cards on screen (plus two ahead). */
@SuppressLint("ViewConstructor")
class Shelf(ctx: Context, private val l: Layout, private val clock: FrameClock, val title: String,
            val cw: Int = Look.CARD_W, val ch: Int = Look.CARD_H, private val titled: Boolean = true) : FrameLayout(ctx), FrameClock.Tick {
    private val header = TextView(ctx).apply { style(Txt.TITLE, l.u); text = title }
    private val status = TextView(ctx).apply { style(Txt.META, l.u, Colors.TEXT_DIM); text = "Loading…" }
    private val strip = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    val cards = ArrayList<Card>()
    var col = 0
    private var first = 0
    private val sx = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val pitch = l.px(cw + Look.GAP)
    private val visible = max(1, (l.px(1920) - l.left) / pitch)
    val rowHeight = l.px(Look.ROW_TITLE) + l.px(ch) + l.px(if (titled) Look.CARD_LABEL + 44 else 46)
    var loader: ((Card) -> Unit)? = null
    var loaded = false; private set
    val item: ContentItem? get() = cards.getOrNull(col)?.item

    init {
        clipChildren = false; clipToPadding = false
        addView(header, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left })
        addView(status, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(Look.ROW_TITLE + 20) })
        addView(strip, LayoutParams(MATCH_PARENT, l.px(ch)).apply { topMargin = l.px(Look.ROW_TITLE) })
    }

    /** Fills the row; [bind] sets each card up. An empty list shows [emptyText] (or hides the row when null). */
    fun set(items: List<ContentItem>, emptyText: String? = null, bind: (Card, ContentItem) -> Unit) {
        strip.removeAllViews(); cards.clear()
        items.forEachIndexed { i, it ->
            val c = Card(context, l, clock, l.px(cw), l.px(ch), titled); c.item = it; bind(c, it)
            strip.addView(c, LayoutParams(l.px(cw), l.px(ch)).apply { leftMargin = l.left + i * pitch })
            cards.add(c)
        }
        loaded = true
        status.text = emptyText ?: ""
        status.visibility = if (items.isEmpty() && emptyText != null) VISIBLE else GONE
        visibility = if (items.isEmpty() && emptyText == null) GONE else VISIBLE
        col = col.coerceIn(0, max(0, cards.size - 1))
        place(); ensureLoaded()
    }

    fun failed(text: String) { loaded = true; status.text = text; status.visibility = VISIBLE }

    fun focus(): Boolean { val c = cards.getOrNull(col) ?: return false; place(); c.requestFocus(); return true }

    fun move(dx: Int): Boolean { val n = col + dx; if (n !in cards.indices) return false; col = n; focus(); return true }

    private fun place() {
        first = (col - 1).coerceIn(0, max(0, cards.size - visible))
        sx.target = -(first * pitch).toFloat(); clock.add(this); ensureLoaded()
    }

    private fun ensureLoaded() {
        val ld = loader ?: return
        for (i in first until min(cards.size, first + visible + 2)) { val c = cards[i]; if (!c.requested) { c.requested = true; ld(c) } }
        // Cards well off screen give their artwork back, so memory doesn't grow with browsing.
        for (i in cards.indices) if (i < first - 4 || i > first + visible + 6) cards[i].release()
    }

    /** Releases every card's artwork (row far from view, or its screen hidden). */
    fun releaseAll() { if (loader != null) cards.forEach { it.release() } }
    /** Loads the visible cards again after [releaseAll]. */
    fun reload() = ensureLoaded()

    override fun tick(dt: Float): Boolean { sx.step(dt); strip.translationX = sx.value; return !sx.atRest() }
}

/**
 * A vertical stack of shelves. The focused row slides up to [topY]; rows above it fade out so they never cover the
 * info block. Movement is explicit: [key] returns false only when moving up from the first row.
 */
@SuppressLint("ViewConstructor")
class ShelfStack(ctx: Context, private val l: Layout, private val clock: FrameClock, var topY: Int) : FrameLayout(ctx), FrameClock.Tick {
    val shelves = ArrayList<Shelf>()
    var r = 0
    private val holder = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val sy = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    var onFocus: ((Shelf, Card) -> Unit)? = null
    var onRow: ((Int) -> Unit)? = null           // the focused row changed (lazy loading of rows further down)

    init { clipChildren = false; clipToPadding = false; addView(holder, LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

    fun clear() { holder.removeAllViews(); shelves.clear(); r = 0; sy.snapTo(0f); holder.translationY = 0f }

    fun add(s: Shelf): Shelf { shelves.add(s); holder.addView(s, LayoutParams(MATCH_PARENT, s.rowHeight)); relayout(); return s }

    /** Re-positions rows after one appears, disappears or changes. */
    fun relayout() {
        var y = topY
        for (s in shelves) {
            val lp = s.layoutParams as LayoutParams
            if (lp.topMargin != y) { lp.topMargin = y; s.layoutParams = lp }
            if (s.visibility != GONE) y += s.rowHeight
        }
        place()
    }

    private fun navigable(i: Int) = shelves.getOrNull(i)?.let { it.visibility == VISIBLE && it.cards.isNotEmpty() } ?: false
    fun firstNavigable() = shelves.indices.firstOrNull { navigable(it) } ?: -1
    val current: Shelf? get() = shelves.getOrNull(r)
    val item: ContentItem? get() = current?.item
    fun atFirst() = r <= firstNavigable()

    fun key(dx: Int, dy: Int): Boolean {
        if (dx != 0) { if (current?.move(dx) == true) notifyFocus(); return true }
        var n = r + dy
        while (n in shelves.indices && !navigable(n)) n += dy
        if (n !in shelves.indices) return dy > 0
        r = n; focus(); return true
    }

    fun focus(): Boolean {
        if (!navigable(r)) r = firstNavigable()
        val s = shelves.getOrNull(r) ?: return false
        s.focus(); place(); notifyFocus(); onRow?.invoke(r); trim()
        return true
    }

    /** Only rows near the focused one keep their artwork. */
    fun trim() { shelves.forEachIndexed { i, s -> if (kotlin.math.abs(i - r) > 2) s.releaseAll() else s.reload() } }
    fun releaseAll() = shelves.forEach { it.releaseAll() }

    private fun notifyFocus() { val s = current ?: return; s.cards.getOrNull(s.col)?.let { onFocus?.invoke(s, it) } }

    private fun place() {
        val s = shelves.getOrNull(r) ?: return
        sy.target = -((s.layoutParams as LayoutParams).topMargin - topY).toFloat(); clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        sy.step(dt); holder.translationY = sy.value
        val fade = l.pxf(160f)
        for (s in shelves) {
            val y = (s.layoutParams as LayoutParams).topMargin + sy.value
            s.alpha = ((y - (topY - fade)) / fade).coerceIn(0f, 1f)
        }
        return !sy.atRest()
    }
}

/** The tvOS-style tab bar: a glass capsule of text tabs, centred at the top. */
@SuppressLint("ViewConstructor")
class TabBar(ctx: Context, l: Layout, clock: FrameClock, labels: List<String>) : LinearLayout(ctx) {
    val tabs = labels.map { Pill(ctx, it, l.pxf(27f), l.pxf(30f), l.px(30), clock, 0x00000000, Colors.TEXT_DIM, 0.06f) }
    var selected = 0
        set(v) { field = v; tabs.forEachIndexed { i, t -> t.selectedState = i == v } }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
        background = GradientDrawable().apply { cornerRadius = l.pxf(40f); setColor(Colors.GLASS); setStroke(l.px(1).coerceAtLeast(1), Colors.HAIRLINE) }
        val p = l.px(8); setPadding(p, p, p, p)
        tabs.forEachIndexed { i, t -> addView(t, LayoutParams(WRAP_CONTENT, l.px(60)).apply { if (i > 0) leftMargin = l.px(4) }) }
        selected = 0
    }
}

/**
 * Remote-friendly on-screen keyboard: a 13-column grid (a–m, n–z, digits and actions). Moving off its bottom edge
 * returns false so the screen can move focus to the results.
 */
@SuppressLint("ViewConstructor")
class Keyboard(ctx: Context, private val l: Layout, clock: FrameClock, private val onKey: (String) -> Unit) : FrameLayout(ctx) {
    private val rows: List<List<String>> = listOf("abcdefghijklm".map { "$it" }, "nopqrstuvwxyz".map { "$it" },
        "1234567890".map { "$it" } + listOf(SPACE, DEL, CLEAR))
    private val keys = ArrayList<List<Pill>>()
    var r = 0; var c = 0

    init {
        clipChildren = false
        val k = l.px(72); val gap = l.px(12)
        rows.forEachIndexed { ri, row ->
            val line = ArrayList<Pill>(); var x = 0
            row.forEach { v ->
                val wide = v.length > 1
                val w = if (wide) k * 2 + gap else k
                val label = when (v) { SPACE -> "Space"; DEL -> "Delete"; CLEAR -> "Clear"; else -> v }
                val p = Pill(ctx, label, l.pxf(if (wide) 24f else 30f), l.pxf(18f), 0, clock, Colors.FILL, Colors.WHITE, 0.12f)
                p.setOnClickListener { onKey(v) }
                addView(p, LayoutParams(w, k).apply { leftMargin = x; topMargin = ri * (k + gap) })
                line.add(p); x += w + gap
            }
            keys.add(line)
        }
    }

    fun focus() { keys[r][c.coerceIn(0, keys[r].size - 1)].requestFocus() }

    /** False when moving down from the last row or up from the first. */
    fun key(dx: Int, dy: Int): Boolean {
        if (dx != 0) { c = (c + dx).coerceIn(0, keys[r].size - 1); focus(); return true }
        val n = r + dy
        if (n !in keys.indices) return false
        // keep roughly the same horizontal position across rows of different key widths
        val x = keys[r][c.coerceIn(0, keys[r].size - 1)].let { (it.layoutParams as LayoutParams).leftMargin }
        r = n; c = keys[r].indices.minByOrNull { kotlin.math.abs((keys[r][it].layoutParams as LayoutParams).leftMargin - x) } ?: 0
        focus(); return true
    }

    companion object { const val SPACE = "space"; const val DEL = "del"; const val CLEAR = "clear" }
}

/** A settings row: label at the start and its current value at the end, both inverting to ink on focus. */
@SuppressLint("ViewConstructor")
class ValuePill(ctx: Context, private val l: Layout, label: String, clock: FrameClock) :
    Pill(ctx, label, l.pxf(29f), l.pxf(22f), l.px(30), clock, Colors.FILL, Colors.WHITE, 0.02f, startAligned = true) {
    var value: String = ""
        set(v) { field = v; invalidate() }
    private val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(27f) * com.eppletv.launcher.ui.Type.scale; typeface = com.eppletv.launcher.ui.Type.of(500); textAlign = Paint.Align.RIGHT }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (value.isEmpty()) return
        vp.color = currentTextColor; vp.alpha = (vp.alpha * 0.75f).toInt()
        val y = height / 2f - (vp.descent() + vp.ascent()) / 2f
        c.drawText(value, width - l.pxf(30f), y, vp)
    }
}
