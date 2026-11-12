package com.eppletv.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.apps.Section
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.textStyle
import kotlin.math.min
import kotlin.math.roundToInt

interface PageHost : TileHost {
    /** [deep] = focus has moved below the top of the page (top bar hides, backdrop dims). */
    fun onPageDepth(page: Page, deep: Boolean)
}

/** A tab's content. Focus movement is explicit and deterministic: the page decides where each key press lands. */
interface Page {
    val view: FrameLayout
    /** Returns false only when moving up from the top row (the caller then focuses the top bar). */
    fun move(dx: Int, dy: Int): Boolean
    fun focusCurrent(): Boolean
    fun atTop(): Boolean
    fun toTop()
}

internal fun makeShadow(l: Layout): Bitmap {
    val p = l.shadowPad
    val b = Bitmap.createBitmap(l.tileW + 2 * p, l.tileH + 2 * p, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8000000.toInt(); maskFilter = BlurMaskFilter(p * 0.62f, BlurMaskFilter.Blur.NORMAL)
    }
    Canvas(b).drawRoundRect(RectF(p.toFloat(), p.toFloat(), (p + l.tileW).toFloat(), (p + l.tileH).toFloat()), l.corner, l.corner, paint)
    b.prepareToDraw()
    return b
}

/** Adds a tile and its (focus-only) label to [parent] at x,y and returns the tile. */
private fun addTile(parent: FrameLayout, l: Layout, clock: FrameClock, host: TileHost, shadow: Bitmap, app: AppEntry, x: Int, y: Int): Tile {
    val ctx = parent.context
    val extra = l.px(40)
    val label = TextView(ctx).apply {
        text = app.label; textStyle(l.pxf(26f), 500); alpha = 0f; gravity = Gravity.CENTER
        setShadowLayer(l.pxf(10f), 0f, l.pxf(2f), 0x99000000.toInt())
    }
    parent.addView(label, FrameLayout.LayoutParams(l.tileW + 2 * extra, l.labelH).apply { leftMargin = x - extra; topMargin = y + l.tileH + l.labelTop })
    val tile = Tile(ctx, app, shadow, label, l, clock, host)
    parent.addView(tile, FrameLayout.LayoutParams(l.tileW, l.tileH).apply { leftMargin = x; topMargin = y })
    return tile
}

/** One horizontal row on Home. Scrolls by springing the strip so the focused tile stays inside the safe area. */
class RowView(
    ctx: Context, private val l: Layout, private val clock: FrameClock, host: TileHost, shadow: Bitmap,
    val section: Section, k: Int, parent: FrameLayout,
) : FrameClock.Tick {
    val tiles = ArrayList<Tile>(section.apps.size)
    private val strip = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val sx = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private var first = 0
    var col = 0; private set

    init {
        section.title?.let { t ->
            parent.addView(TextView(ctx).apply {
                text = t; textStyle(l.pxf(30f), 600, 0xEBFFFFFF.toInt())
                setShadowLayer(l.pxf(10f), 0f, l.pxf(2f), 0x80000000.toInt())
            }, FrameLayout.LayoutParams(l.px(1200), l.px(44)).apply { leftMargin = l.left; topMargin = l.sectionTitleY(k) })
        }
        parent.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, l.stripH).apply { topMargin = l.sectionTilesY(k) })
        section.apps.forEachIndexed { i, app -> tiles.add(addTile(strip, l, clock, host, shadow, app, l.left + i * l.pitchX, 0)) }
    }

    /** On-screen x of column [c] once the row has settled. */
    fun screenX(c: Int) = l.left + (c - first) * l.pitchX
    fun nearest(x: Int) = (first + ((x - l.left).toFloat() / l.pitchX).roundToInt()).coerceIn(0, tiles.size - 1)

    fun place(c: Int) {
        col = c.coerceIn(0, tiles.size - 1)
        if (col < first) first = col
        if (col > first + Layout.COLS - 1) first = col - Layout.COLS + 1
        sx.target = -(first * l.pitchX).toFloat()
        clock.add(this)
    }

    fun focus(c: Int) { place(c); tiles[col].requestFocus() }

    override fun tick(dt: Float): Boolean { sx.step(dt); strip.translationX = sx.value; return !sx.atRest() }
}

/** Home: hero title over the backdrop, a top row, then titled sections. */
class HomePage(ctx: Context, private val l: Layout, private val clock: FrameClock, private val host: PageHost, private val shadow: Bitmap) : Page, FrameClock.Tick {
    override val view = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    val hero = Hero(ctx, l, clock)
    private val rows = ArrayList<RowView>()
    private var r = 0
    private val sy = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)

    init { addHero() }

    private fun addHero() = view.addView(hero.view, FrameLayout.LayoutParams(l.px(1400), l.px(180)).apply { leftMargin = l.left; topMargin = l.dockY - l.px(260) })

    fun setSections(sections: List<Section>) {
        val key = rows.getOrNull(r)?.section?.key
        val pkg = rows.getOrNull(r)?.let { it.tiles.getOrNull(it.col)?.entry?.packageName }
        val oldCol = rows.getOrNull(r)?.col ?: 0
        view.removeAllViews(); rows.clear(); addHero()
        sections.forEachIndexed { k, s -> rows.add(RowView(view.context, l, clock, host, shadow, s, k, view)) }
        view.layoutParams?.let { it.height = l.homeHeight(rows.size); view.layoutParams = it }
        r = rows.indexOfFirst { it.section.key == key }.let { if (it < 0) 0 else it }
        rows.getOrNull(r)?.let { row ->
            val c = row.tiles.indexOfFirst { it.entry.packageName == pkg }
            row.place(if (c >= 0) c else oldCol)
        }
        sy.target = l.homeScroll(r, rows.size); clock.add(this)
    }

    override fun move(dx: Int, dy: Int): Boolean {
        val row = rows.getOrNull(r) ?: return dy >= 0
        if (dx != 0) { val c = row.col + dx; if (c in row.tiles.indices) row.focus(c); return true }
        val nr = r + dy
        if (nr < 0) return false
        if (nr >= rows.size) return true
        val x = row.screenX(row.col)
        r = nr
        rows[r].focus(rows[r].nearest(x))
        scrolled()
        return true
    }

    override fun focusCurrent(): Boolean {
        val row = rows.getOrNull(r) ?: return false
        row.focus(row.col); scrolled()
        return true
    }

    override fun atTop() = r == 0
    override fun toTop() { r = 0; focusCurrent() }

    private fun scrolled() { sy.target = l.homeScroll(r, rows.size); clock.add(this); host.onPageDepth(this, r > 0) }

    override fun tick(dt: Float): Boolean {
        sy.step(dt)
        view.translationY = sy.value
        hero.view.alpha = (1f + sy.value / l.pxf(260f)).coerceIn(0f, 1f)
        return !sy.atRest()
    }
}

/** Apps: every app in a six-column grid under a page title. */
class AppsPage(ctx: Context, private val l: Layout, private val clock: FrameClock, private val host: PageHost, private val shadow: Bitmap, private val title: String) : Page, FrameClock.Tick {
    override val view = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val tiles = ArrayList<Tile>()
    private var i = 0
    private val sy = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)

    fun setApps(apps: List<AppEntry>) {
        val pkg = tiles.getOrNull(i)?.entry?.packageName
        view.removeAllViews(); tiles.clear()
        view.addView(TextView(view.context).apply { text = title; textStyle(l.pxf(56f), 600); letterSpacing = -0.02f },
            FrameLayout.LayoutParams(l.px(1200), l.px(72)).apply { leftMargin = l.left; topMargin = l.px(150) })
        apps.forEachIndexed { n, app ->
            tiles.add(addTile(view, l, clock, host, shadow, app, l.left + (n % Layout.COLS) * l.pitchX, l.gridTop + (n / Layout.COLS) * l.gridPitchY))
        }
        view.layoutParams?.let { it.height = l.gridHeight((apps.size + Layout.COLS - 1) / Layout.COLS); view.layoutParams = it }
        val keep = tiles.indexOfFirst { it.entry.packageName == pkg }
        i = if (keep >= 0) keep else min(i, tiles.size - 1).coerceAtLeast(0)
        sy.target = l.gridScroll(i / Layout.COLS); clock.add(this)
    }

    override fun move(dx: Int, dy: Int): Boolean {
        if (tiles.isEmpty()) return dy >= 0
        if (dx != 0) {
            val n = i + dx
            if (n in tiles.indices && n / Layout.COLS == i / Layout.COLS) focus(n)
            return true
        }
        if (i / Layout.COLS + dy < 0) return false
        val n = min(i + dy * Layout.COLS, tiles.size - 1)
        if (n / Layout.COLS != i / Layout.COLS) focus(n)
        return true
    }

    private fun focus(n: Int) {
        i = n
        tiles[n].requestFocus()
        sy.target = l.gridScroll(n / Layout.COLS); clock.add(this)
        host.onPageDepth(this, n / Layout.COLS >= 2)
    }

    override fun focusCurrent(): Boolean { if (tiles.isEmpty()) return false; focus(i); return true }
    override fun atTop() = i < Layout.COLS
    override fun toTop() { if (tiles.isNotEmpty()) focus(i % Layout.COLS) }

    override fun tick(dt: Float): Boolean { sy.step(dt); view.translationY = sy.value; return !sy.atRest() }
}

/**
 * Full-screen backdrop: the focused app's own artwork as a soft colour field.
 * Two layers; the top one fades over an opaque bottom, so a change never dips through black.
 */
class Backdrop(ctx: Context, private val clock: FrameClock) : FrameClock.Tick {
    val bottom = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val top = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val fade = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS)
    private var topIsFront = false
    private var current: Bitmap? = null

    fun show(b: Bitmap) {
        if (b === current) return
        current = b
        if (bottom.drawable == null) { bottom.setImageBitmap(b); return }
        if (topIsFront) { bottom.setImageBitmap(b); fade.target = 0f; topIsFront = false }
        else { top.setImageBitmap(b); fade.target = 1f; topIsFront = true }
        clock.add(this)
    }

    override fun tick(dt: Float): Boolean { fade.step(dt); top.alpha = fade.value; return !fade.atRest() }
}

/** Hero text for the focused app: small category eyebrow over a large title. Cross-fades with a slight upward drift. */
class Hero(ctx: Context, private val l: Layout, private val clock: FrameClock) : FrameClock.Tick {
    val view = FrameLayout(ctx)
    private class Layer(val box: LinearLayout, val eyebrow: TextView, val title: TextView, val a: Spring)
    private val layers = Array(2) { layer(ctx) }
    private var front = 0
    private var shown: String? = null

    fun show(eyebrow: String, title: String) {
        val key = "$eyebrow\n$title"
        if (key == shown) return
        shown = key
        layers[front].a.target = 0f
        front = 1 - front
        layers[front].eyebrow.text = eyebrow; layers[front].title.text = title
        layers[front].eyebrow.visibility = if (eyebrow.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        layers[front].a.target = 1f
        clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        var moving = false
        for (y in layers) {
            y.a.step(dt)
            y.box.alpha = y.a.value; y.box.translationY = (1f - y.a.value) * l.pxf(14f)
            if (!y.a.atRest()) moving = true
        }
        return moving
    }

    private fun layer(ctx: Context): Layer {
        val eyebrow = TextView(ctx).apply {
            textStyle(l.pxf(22f), 600, 0xC7FFFFFF.toInt()); letterSpacing = 0.12f; isAllCaps = true
            setShadowLayer(l.pxf(8f), 0f, l.pxf(2f), 0x80000000.toInt())
        }
        val title = TextView(ctx).apply {
            textStyle(l.pxf(76f), 600); letterSpacing = -0.02f
            setShadowLayer(l.pxf(18f), 0f, l.pxf(3f), 0x80000000.toInt())
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f
            addView(eyebrow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(10) })
        }
        view.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        return Layer(box, eyebrow, title, Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f))
    }
}
