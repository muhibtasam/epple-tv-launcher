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
    // Netflix-style rows: portrait posters at rest; the focused title opens into a wide card of the same height.
    const val ROW_H = 300; const val POSTER_W = 200; const val WIDE_W = 533; const val ROW_GAP = 18
    // Continue Watching (design B): landscape cards with the title's logo; the focused one grows, a progress line under each.
    const val CW_W = 408; const val CW_WIDE = 528; const val CW_ART_H = 297; const val CW_GAP = 21; const val CW_H = 366
}

/** A card whose drawn width the row sets as it moves (posters opening into wide cards, Continue Watching growing). */
interface Widening { var shown: Float }

/**
 * A card in a Netflix-style row. At rest it shows the title's portrait poster; the focused card widens (driven by its
 * row) to a 16:9 card showing the landscape artwork, crossfading between the two as it grows. A white rim marks focus.
 * Everything is drawn by this one view: the row moves it with translationX and [shown], never with layout passes.
 */
@SuppressLint("ViewConstructor")
class PosterCard(ctx: Context, private val l: Layout, private val clock: FrameClock) :
    Card(ctx, l, clock, l.px(Look.WIDE_W), l.px(Look.ROW_H), titled = false), Widening {
    private val posterW = l.pxf(Look.POSTER_W.toFloat()); private val wideW = l.pxf(Look.WIDE_W.toFloat())
    /** The width drawn now, between the poster and the wide card. */
    override var shown = posterW
        set(v) { if (field != v) { field = v; invalidate() } }
    private var poster: Bitmap? = null
    private var wide: Bitmap? = null
    private val fv = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.PLACEHOLDER }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(4f); color = Colors.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(24f); typeface = com.eppletv.launcher.ui.Type.of(600); color = Colors.TEXT_DIM; textAlign = Paint.Align.CENTER }
    private val r = RectF(); private val m = Matrix(); private val clip = android.graphics.Path()
    private val radius = l.pxf(16f)

    init { removeAllViews(); setWillNotDraw(false); clipChildren = true }

    private val shaders = HashMap<Bitmap, BitmapShader>(2)
    override fun setBitmap(b: Bitmap?) { wide = b; invalidate() }
    fun setPoster(b: Bitmap?) { poster = b; invalidate() }
    override fun release() { if (wide != null || poster != null || requested) { requested = false; wide = null; poster = null; shaders.clear(); invalidate() } }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        fv.target = if (gain) 1f else 0f; clock.add(this)
    }

    override fun tick(dt: Float): Boolean { fv.step(dt); invalidate(); return !fv.atRest() }

    private fun drawCrop(c: Canvas, b: Bitmap, w: Float, h: Float, alpha: Int) {
        val s = maxOf(w / b.width, h / b.height)
        m.setScale(s, s); m.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
        if (shaders.size > 2) shaders.keys.retainAll(setOf(poster, wide))
        paint.shader = shaders.getOrPut(b) { BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }.apply { setLocalMatrix(m) }
        paint.alpha = alpha
        c.drawRect(0f, 0f, w, h, paint)
    }

    override fun onDraw(c: Canvas) {
        val w = shown; val h = height.toFloat()
        if (w < 1f) return
        r.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
        c.save(); c.clipPath(clip)
        val t = ((w - posterW) / (wideW - posterW)).coerceIn(0f, 1f)
        val p = poster ?: wide; val wd = wide ?: poster
        when {
            p == null -> { c.drawRect(r, fill); item?.title?.let { c.drawText(it.take(18), w / 2f, h / 2f, label) } }
            t <= 0.01f || p === wd -> drawCrop(c, if (t > 0.5f) wd!! else p, w, h, 255)
            t >= 0.99f -> drawCrop(c, wd!!, w, h, 255)
            else -> { drawCrop(c, p, w, h, 255); drawCrop(c, wd!!, w, h, (255 * t).toInt()) }
        }
        c.restore()
        val f = fv.value.coerceIn(0f, 1f)
        if (f > 0.01f) { rim.alpha = (255 * f).toInt(); r.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f); c.drawRoundRect(r, radius, radius, rim) }
    }
}

/**
 * Continue Watching card (design B): the title's landscape artwork with its logo (or name) over a soft shade at the
 * bottom, and a progress line underneath. The focused card grows (the row drives [shown]), gets a white rim, full
 * brightness and a caption under the line (episode, how much is watched). One view draws all of it.
 */
@SuppressLint("ViewConstructor")
class ContinueCard(ctx: Context, private val l: Layout, private val clock: FrameClock) :
    Card(ctx, l, clock, l.px(Look.CW_WIDE), l.px(Look.CW_H), titled = false), Widening {
    private val restW = l.pxf(Look.CW_W.toFloat()); private val wideW = l.pxf(Look.CW_WIDE.toFloat())
    override var shown = restW
        set(v) { if (field != v) { field = v; invalidate() } }
    private var picture: Bitmap? = null
    private var logo: Bitmap? = null
    private var pct = -1f
    private var caption = ""
    private val fv = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.PLACEHOLDER }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f, 0x00000000, 0xC0000000.toInt(), Shader.TileMode.CLAMP) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(4f); color = Colors.WHITE }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = com.eppletv.launcher.ui.Type.of(800); color = Colors.WHITE
        setShadowLayer(l.pxf(8f), 0f, l.pxf(2f), 0x99000000.toInt()) }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(24f) * com.eppletv.launcher.ui.Type.scale; typeface = com.eppletv.launcher.ui.Type.of(600); color = 0xC7FFFFFF.toInt() }
    private val r = RectF(); private val m = Matrix(); private val sm = Matrix(); private val clip = android.graphics.Path()
    private val radius = l.pxf(12f)

    init { removeAllViews(); setWillNotDraw(false); clipChildren = true }

    /** Reads the progress and the caption from the item's meta line ("App  ·  Episode 1  ·  3% watched"). */
    fun describe(it: ContentItem) {
        val app = it.kind.removePrefix("Continue in ")
        val parts = it.meta.split("  ·  ").map { p -> p.trim() }.filter { p -> p.isNotEmpty() && p != app }
        pct = parts.firstNotNullOfOrNull { p -> PCT.find(p)?.groupValues?.get(1)?.toFloatOrNull() }?.div(100f) ?: -1f
        caption = parts.filter { p -> !p.startsWith("★") }.take(2).joinToString("  ·  ").ifEmpty { "Resume in $app" }
        invalidate()
    }

    override fun setBitmap(b: Bitmap?) { picture = b; paint.shader = b?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; invalidate() }
    fun setLogo(b: Bitmap?) { logo = b; invalidate() }
    override fun release() { if (picture != null || logo != null || requested) { requested = false; setBitmap(null); logo = null; invalidate() } }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        fv.target = if (gain) 1f else 0f; clock.add(this)
    }
    override fun tick(dt: Float): Boolean { fv.step(dt); invalidate(); return !fv.atRest() }

    override fun onDraw(c: Canvas) {
        val w = shown; if (w < 1f) return
        val t = ((w - restW) / (wideW - restW)).coerceIn(0f, 1f)
        val h = w * 9f / 16f
        val top = (l.pxf(Look.CW_ART_H.toFloat()) - h) / 2f
        r.set(0f, top, w, top + h)
        clip.reset(); clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
        c.save(); c.clipPath(clip)
        val b = picture
        if (b == null) c.drawRect(r, fill)
        else {
            val s = maxOf(w / b.width, h / b.height)
            m.setScale(s, s); m.postTranslate((w - b.width * s) / 2f, top + (h - b.height * s) / 2f)
            paint.shader?.setLocalMatrix(m); paint.alpha = (190 + 65 * t).toInt(); c.drawRect(r, paint)
        }
        // The shade under the logo: the lower 55 % of the card.
        val sh = h * 0.55f
        sm.setScale(1f, sh); sm.postTranslate(0f, top + h - sh); shade.shader.setLocalMatrix(sm)
        c.drawRect(0f, top + h - sh, w, top + h, shade)
        val pad = l.pxf(16f + 4f * t)
        val lg = logo
        if (titledArt) Unit                                // the app's artwork already carries the title
        else if (lg != null) {
            val mw = l.pxf(196f + 64f * t); val mh = l.pxf(60f + 20f * t)
            val k = minOf(mw / lg.width, mh / lg.height)
            m.setScale(k, k); m.postTranslate(pad, top + h - pad - lg.height * k)
            c.drawBitmap(lg, m, logoPaint)
        } else item?.title?.let { tx ->
            name.textSize = l.pxf(26f + 8f * t) * com.eppletv.launcher.ui.Type.scale
            c.drawText(ellipsize(tx, name, w - 2 * pad), pad, top + h - pad, name)
        }
        c.restore()
        val f = fv.value.coerceIn(0f, 1f)
        if (f > 0.01f) { rim.alpha = (255 * f).toInt(); r.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f); c.drawRoundRect(r, radius, radius, rim) }
        // Progress line under the card (only when the app reports one), then the caption under the focused card.
        val y = top + h + l.pxf(16f); val bh = l.pxf(4f + 2f * t)
        if (pct >= 0f) {
            bar.color = 0x38FFFFFF; r.set(0f, y, w, y + bh); c.drawRoundRect(r, bh / 2f, bh / 2f, bar)
            bar.color = Colors.WHITE; bar.alpha = (180 + 75 * t).toInt(); r.set(0f, y, maxOf(bh, w * pct), y + bh); c.drawRoundRect(r, bh / 2f, bh / 2f, bar)
        }
        if (t > 0.02f) { cap.alpha = (199 * t).toInt(); c.drawText(ellipsize(caption, cap, w), 0f, y + bh + l.pxf(14f) - cap.ascent(), cap) }
    }

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var n = s.length
        while (n > 1 && p.measureText(s, 0, n) + p.measureText("…") > max) n--
        return s.take(n).trimEnd() + "…"
    }

    /** Prime Video draws the title into its Watch Next artwork: no second logo on top. */
    private val titledArt get() = item?.linkPkg in TITLED_ART

    private companion object {
        val PCT = Regex("(\\d+)% watched")
        val TITLED_ART = setOf("com.amazon.amazonvideo.livingroom")
    }
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
open class Card(ctx: Context, private val l: Layout, private val clock: FrameClock, val w: Int, val h: Int, private val titled: Boolean = true) :
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

    open fun setBitmap(b: Bitmap?) { art.bitmap = b; ph.visibility = if (b == null) VISIBLE else GONE }

    /** Drops the artwork (it reloads from the memory or disk cache when the card comes back into view). */
    open fun release() { if (art.bitmap != null || requested) { requested = false; setBitmap(null) } }

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
            val cw: Int = Look.CARD_W, val ch: Int = Look.CARD_H, private val titled: Boolean = true,
            /** Netflix-style: posters with a wide focused card, and an endless loop (after the last title comes the first). */
            poster: Boolean = false,
            /** Continue Watching (design B): landscape cards that grow when focused, on the same endless loop. */
            val cont: Boolean = false) : FrameLayout(ctx), FrameClock.Tick {
    val poster = poster || cont
    // Card widths at rest and focused, and the gap, for the looping rows.
    private val restPx = l.pxf((if (cont) Look.CW_W else Look.POSTER_W).toFloat())
    private val widePx = l.pxf((if (cont) Look.CW_WIDE else Look.WIDE_W).toFloat())
    private val gapPx = l.pxf((if (cont) Look.CW_GAP else Look.ROW_GAP).toFloat())
    private val stripH = if (cont) Look.CW_H else Look.ROW_H
    private val header = TextView(ctx).apply { style(Txt.TITLE, l.u); text = title }
    private val status = TextView(ctx).apply { style(Txt.META, l.u, Colors.TEXT_DIM); text = "Loading…" }
    private val strip = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    val cards = ArrayList<Card>()
    var col = 0
    private var first = 0
    private val sx = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val pitch = l.px(cw + Look.GAP)
    private val visible = max(1, (l.px(1920) - l.left) / pitch)
    val rowHeight = if (poster) l.px(Look.ROW_TITLE) + l.px(stripH) + l.px(44) else l.px(Look.ROW_TITLE) + l.px(ch) + l.px(if (titled) Look.CARD_LABEL + 44 else 46)
    // Poster mode: a continuous position (in titles, unbounded) springs to [pt]; each card's slot is derived from it.
    private val ps = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, 0.002f)
    private var pt = 0f
    var loader: ((Card) -> Unit)? = null
    var loaded = false; private set
    val item: ContentItem? get() = cards.getOrNull(col)?.item

    init {
        clipChildren = false; clipToPadding = false
        addView(header, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left })
        addView(status, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(Look.ROW_TITLE + 20) })
        addView(strip, LayoutParams(MATCH_PARENT, l.px(if (poster) stripH else ch)).apply { topMargin = l.px(Look.ROW_TITLE) })
    }

    /** Fills the row; [bind] sets each card up. An empty list shows [emptyText] (or hides the row when null). */
    fun set(list: List<ContentItem>, emptyText: String? = null, bind: (Card, ContentItem) -> Unit) {
        // Cards of titles still in the row are kept with their artwork (a reload after one title changed or left the row
        // rebuilds nothing else and fetches nothing again); only new titles get new cards.
        val old = HashMap<String, ArrayDeque<Card>>()
        if (poster) cards.forEach { c -> c.item?.id?.let { id -> old.getOrPut(id) { ArrayDeque() }.add(c) } }
        val focused = cards.getOrNull(col)?.hasFocus() == true
        strip.removeAllViews(); cards.clear()
        // One card per title, never repeated: a short row loops over its own titles (no duplicate cards on screen).
        val items = list
        items.forEachIndexed { i, it ->
            val kept = old[it.id]?.removeFirstOrNull()?.takeIf { k -> k.item?.imageUrl == it.imageUrl }
            val c = kept ?: if (cont) ContinueCard(context, l, clock) else if (poster) PosterCard(context, l, clock) else Card(context, l, clock, l.px(cw), l.px(ch), titled)
            c.item = it; bind(c, it)
            if (c is ContinueCard) c.describe(it)
            if (poster) strip.addView(c, LayoutParams(widePx.toInt(), l.px(stripH)).apply { leftMargin = l.left })
            else strip.addView(c, LayoutParams(l.px(cw), l.px(ch)).apply { leftMargin = l.left + i * pitch })
            cards.add(c)
        }
        loaded = true
        status.text = emptyText ?: ""
        status.visibility = if (items.isEmpty() && emptyText != null) VISIBLE else GONE
        visibility = if (items.isEmpty() && emptyText == null) GONE else VISIBLE
        col = col.coerceIn(0, max(0, cards.size - 1))
        // Titles that left the row leave the visited set; a new title means the loop waits until it is visited too.
        visited.retainAll(cards.map { id(it) }.toSet()); loop = cards.size > 1 && cards.all { id(it) in visited }
        if (poster) { pt = col.toFloat(); ps.snapTo(pt); layoutPosters() }
        place(); ensureLoaded()
        if (focused) cards.getOrNull(col)?.requestFocus()
    }

    // ---- the loop: a row is bounded (Left on the first title and Right on the last do nothing) until every one of its
    // titles has been focused in this visit of the row; then it loops at both ends. Leaving the row ends the visit. ----
    private val visited = HashSet<String>()
    private var loop = false
    private fun id(c: Card) = c.item?.id ?: System.identityHashCode(c).toString()
    private fun visit() {
        val c = cards.getOrNull(col) ?: return
        visited.add(id(c))
        if (!loop && cards.size > 1 && cards.all { id(it) in visited }) loop = true
    }
    /** Place of card [i] for the target [p]: in the loop's window when looping, else simply its distance. */
    private fun pos(i: Int, p: Float) = if (loop) slot(i, p) else i - p

    /** Back to the first title, animated (the row was left for another one); its visit ends. */
    fun rewind() {
        if (cards.isEmpty()) return
        val shown = if (poster) pos(0, pt) + (pt - ps.value) else 0f       // where the first title is on screen now
        visited.clear(); loop = false
        col = 0; first = 0
        if (poster) { pt = 0f; ps.snapTo(-shown); ps.target = 0f; clock.add(this) } else { sx.target = 0f; clock.add(this) }
        ensureLoaded()
    }

    /** Back to the first title, without animation (the section was left); its visit ends. */
    fun reset() {
        visited.clear(); loop = false
        col = 0; first = 0
        if (poster) { pt = 0f; ps.snapTo(0f); layoutPosters() } else { sx.snapTo(0f); strip.translationX = 0f }
        ensureLoaded()
    }

    /** First slot of the loop's window: two cards behind the focus in a long row, one in a short one, none for two. */
    private fun low(n: Int) = when { n >= 6 -> -2; n >= 3 -> -1; else -> 0 }

    /**
     * Slot of card [i] for the focus target [p] (an integer): its place in the loop's window low..low+n-1, so every
     * title has exactly one place and the loop has no ends.
     */
    private fun slot(i: Int, p: Float): Float {
        val n = cards.size; if (n == 0) return 0f
        val lo = low(n)
        return (((i - p - lo) % n + n) % n) + lo
    }

    /**
     * Places every poster card. Each card's place is fixed by the target (so no card jumps across the row while it
     * slides); the spring only shifts the whole window. A card entering at either end of the window fades in, so a
     * wrap never shows a jump, a blank or a duplicate.
     */
    private fun layoutPosters() {
        val pw = restPx; val ww = widePx; val gap = gapPx
        val screen = l.pxf(1920f)
        val n = cards.size; val lo = low(n).toFloat(); val hi = lo + n - 1
        val lag = pt - ps.value
        for ((i, card) in cards.withIndex()) {
            val c = card as? Widening ?: continue
            val d = pos(i, pt) + lag
            val x = l.left + (pw + gap) * d + (ww - pw) * d.coerceIn(0f, 1f)
            val w = pw + (ww - pw) * (1f - kotlin.math.abs(d).coerceIn(0f, 1f))
            card.translationX = x - l.left
            c.shown = w
            card.alpha = if (!loop || n < 2) 1f else ((d - (lo - 1f)).coerceIn(0f, 1f) * ((hi + 1f) - d).coerceIn(0f, 1f))
            card.visibility = if (i == col || (card.alpha > 0.01f && x < screen && x + w > -pw)) VISIBLE else INVISIBLE   // an invisible view can't take focus
        }
    }

    fun failed(text: String) { loaded = true; status.text = text; status.visibility = VISIBLE }

    fun focus(): Boolean { val c = cards.getOrNull(col) ?: return false; visit(); c.visibility = VISIBLE; place(); c.requestFocus(); return true }

    /** One title left or right; false (nothing happens) at an end of a row that doesn't loop yet. */
    fun move(dx: Int): Boolean {
        val n = cards.size
        if (n < 2) return false
        if (!loop) {
            val next = col + dx
            if (next !in 0 until n) return false
            col = next; if (poster) pt = next.toFloat()
        } else if (poster) { pt += dx; col = ((Math.round(pt) % n) + n) % n }
        else col = ((col + dx) % n + n) % n
        focus(); return true
    }

    private fun place() {
        if (poster) { ps.target = pt; clock.add(this); ensureLoaded(); return }
        first = (col - 1).coerceIn(0, max(0, cards.size - visible))
        sx.target = -(first * pitch).toFloat(); clock.add(this); ensureLoaded()
    }

    private fun ensureLoaded() {
        val ld = loader ?: return
        if (poster) {
            // The focus, one behind and the posters that fit after it; the rest give their artwork back.
            val ahead = ((l.pxf(1920f) - l.left) / (restPx + gapPx)).toInt() + 1
            for ((i, c) in cards.withIndex()) {
                val d = pos(i, pt)
                if (d >= -1f && d <= ahead) { if (!c.requested) { c.requested = true; ld(c) } } else if (d > ahead + 4) c.release()
            }
            return
        }
        for (i in first until min(cards.size, first + visible + 2)) { val c = cards[i]; if (!c.requested) { c.requested = true; ld(c) } }
        // Cards well off screen give their artwork back, so memory doesn't grow with browsing.
        for (i in cards.indices) if (i < first - 4 || i > first + visible + 6) cards[i].release()
    }

    /** Releases every card's artwork (row far from view, or its screen hidden). */
    fun releaseAll() { if (loader != null) cards.forEach { it.release() } }
    /** Loads the visible cards again after [releaseAll]. */
    fun reload() = ensureLoaded()

    override fun tick(dt: Float): Boolean {
        if (poster) { ps.step(dt); layoutPosters(); return !ps.atRest() }
        sx.step(dt); strip.translationX = sx.value; return !sx.atRest()
    }
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

    /** Every row back to its first title, the first row at the top, no animation. */
    fun reset() {
        shelves.forEach { it.reset() }
        r = maxOf(0, firstNavigable()); sy.snapTo(0f); holder.translationY = 0f
        place(); sy.snapTo(sy.target); holder.translationY = sy.value; tick(0f)
    }

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
        if (n !in shelves.indices) { if (dy < 0) current?.rewind(); return dy > 0 }    // leaving the rows upwards ends the row's visit too
        current?.rewind()                                   // the row left behind goes back to its first title (and stops looping)
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

/**
 * Remote-friendly on-screen keyboard: a 13-column grid (a–m, n–z, digits and actions). Moving off its bottom edge
 * returns false so the screen can move focus to the results.
 */
@SuppressLint("ViewConstructor")
class Keyboard(ctx: Context, private val l: Layout, clock: FrameClock, private val onKey: (String) -> Unit) : FrameLayout(ctx) {
    // QWERTY, with digits on top and Space, Delete and Clear after the last letters; rows are staggered as on a keyboard.
    private val rows: List<List<String>> = listOf("1234567890".map { "$it" }, "qwertyuiop".map { "$it" },
        "asdfghjkl".map { "$it" }, "zxcvbnm".map { "$it" } + listOf(SPACE, DEL, CLEAR))
    private val indent = floatArrayOf(0f, 0.5f, 1f, 1.5f)
    private val keys = ArrayList<List<Pill>>()
    var r = 0; var c = 0

    init {
        clipChildren = false
        val k = l.px(60); val gap = l.px(10)
        rows.forEachIndexed { ri, row ->
            val line = ArrayList<Pill>(); var x = ((k + gap) * indent[ri]).toInt()
            row.forEach { v ->
                val wide = v.length > 1
                val w = if (wide) k * 2 + gap else k
                val label = when (v) { SPACE -> "Space"; DEL -> "Delete"; CLEAR -> "Clear"; else -> v }
                val p = Pill(ctx, label, l.pxf(if (wide) 22f else 27f), l.pxf(16f), 0, clock, Colors.FILL, Colors.WHITE, 0.12f)
                p.setOnClickListener { onKey(v) }
                addView(p, LayoutParams(w, k).apply { leftMargin = x; topMargin = ri * (k + gap) })
                line.add(p); x += w + gap
            }
            keys.add(line)
        }
    }

    fun focus() { keys[r][c.coerceIn(0, keys[r].size - 1)].requestFocus() }
    fun home() { r = 1; c = 0 }

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
open class ValuePill(ctx: Context, private val l: Layout, label: String, clock: FrameClock) :
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
