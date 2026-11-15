package com.eppletv.launcher.home

import com.eppletv.launcher.ui.EppleIcons
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.eppletv.launcher.ui.Type

/**
 * Small shaded icons for a title's facts (rating star, film clapper, TV set, calendar), drawn from paths with
 * gradients, a highlight and a soft shadow on a 40-unit grid. Vector, so sharp at any size; no image assets.
 */
internal object MetaIcons {
    const val STAR = 0; const val MOVIE = 1; const val SERIES = 2; const val YEAR = 3
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ids = intArrayOf(EppleIcons.STAR, EppleIcons.MOVIES, EppleIcons.TV, EppleIcons.CALENDAR)

    /** The chip's icon from Epple TV's own set (white duotone, like the Home menu), [size] px square at [x],[y]. */
    fun draw(id: Int, c: Canvas, x: Float, y: Float, size: Float) {
        stroke.color = 0xFFFFFFFF.toInt()
        EppleIcons.draw(ids[id], c, x + size / 2f, y + size / 2f, size, stroke, 0f)
    }
}

/**
 * A title's facts as glass chips (Info design 1): rating with a star, Movie or Series with its icon, the year with a
 * calendar (all from Epple TV's duotone icon set), then the rest (language, runtime, genres) as plain text. The facts come from the "  ·  "-joined
 * meta line every source builds; a line with none of them (a YouTube video) is drawn as plain text.
 */
@SuppressLint("ViewConstructor")
class MetaRow(ctx: Context, private val l: Layout) : View(ctx) {
    private class Chip(val icon: Int, val text: String, val numeric: Boolean)
    private var chips: List<Chip> = emptyList()
    private var rest = ""
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(26f) * Type.scale; typeface = Type.of(600, Type.Face.NUM); color = 0xFFFFFFFF.toInt() }
    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.pxf(26f) * Type.scale; typeface = Type.of(600); color = 0xFFFFFFFF.toInt() }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = l.pxf(26f) * Type.scale; typeface = Type.of(500); color = 0xE6FFFFFF.toInt()
        setShadowLayer(l.pxf(8f), 0f, l.pxf(2f), 0x99000000.toInt())
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x29FFFFFF }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = l.pxf(1.2f).coerceAtLeast(1f); color = 0x38FFFFFF }
    private val rect = RectF()
    private val chipH = l.pxf(50f)

    /**
     * Set by screens drawn over artwork: the chips become the launcher's one glass material (see [GlassStyle]) over
     * this backdrop, like the tab bar, the clock badge and Control Center. Cost per chip: one draw of the shared,
     * pre-blurred 240x135 frost texture, a tint, and the edge light as a cached bitmap. Unset: plain translucent chips.
     */
    var backdrop: Backdrop? = null
        set(v) { if (field == null && v != null) { v.listeners.add { invalidate() }; GlassStyle.register(this) }; field = v; invalidate() }
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix(); private val loc = IntArray(2)
    /** Edge light per chip size and glass step: chip widths are rounded up to 8 px so the few sizes are reused. */
    private val edges = HashMap<Long, android.graphics.Bitmap>()
    private fun edge(w: Int, h: Int): android.graphics.Bitmap {
        val k = (w.toLong() shl 32) or (h.toLong() shl 8) or GlassStyle.key.toLong()
        edges[k]?.let { return it }
        if (edges.size >= 24) { edges.values.forEach { it.recycle() }; edges.clear() }
        return GlassStyle.overlay(l, w, h, h / 2f).also { edges[k] = it }
    }

    fun set(meta: String) {
        val parts = meta.split("  ·  ").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Chip>(); val other = ArrayList<String>()
        var rating: Chip? = null; var type: Chip? = null; var year: Chip? = null
        for (s in parts) when {
            rating == null && s.startsWith("★") -> rating = Chip(MetaIcons.STAR, s.removePrefix("★").trim(), true)
            rating == null && s.startsWith("IMDb ") -> rating = Chip(MetaIcons.STAR, s, true)
            type == null && (s == "Movie" || s == "Series") -> type = Chip(if (s == "Movie") MetaIcons.MOVIE else MetaIcons.SERIES, s, false)
            year == null && YEAR.matches(s) -> year = Chip(MetaIcons.YEAR, s, true)
            else -> other += s
        }
        listOfNotNull(rating, type, year).forEach { out += it }
        chips = out; rest = other.joinToString("  ·  ")
        gloss.shader = LinearGradient(0f, 0f, 0f, chipH, intArrayOf(0x33FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.6f), Shader.TileMode.CLAMP)
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (chipH + l.pxf(6f)).toInt())
    }

    override fun onDraw(c: Canvas) {
        var x = 0f
        val top = l.pxf(3f)
        val icon = l.pxf(34f); val pad = l.pxf(10f); val gap = l.pxf(10f); val padR = l.pxf(18f)
        val bd = backdrop; val art = bd?.currentBitmap
        if (art != null && !GlassStyle.clear) {
            // The frost texture mapped to the screen once for the row; each chip just draws its shape with it.
            getLocationInWindow(loc)
            val sh = Frost.shader(art)
            m.setScale(rootView.width / 240f, rootView.height / 135f); m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            sh.setLocalMatrix(m); frost.shader = sh; frost.alpha = GlassStyle.frostAlpha
        } else frost.shader = null
        for (ch in chips) {
            val p = if (ch.numeric) num else word
            var w = pad + icon + gap + p.measureText(ch.text) + padR
            if (bd != null) w = Math.ceil(w / 8.0).toFloat() * 8f
            rect.set(x, top, x + w, top + chipH)
            if (bd != null) {
                if (frost.shader != null) c.drawRoundRect(rect, chipH / 2f, chipH / 2f, frost)
                tint.color = GlassStyle.tint; c.drawRoundRect(rect, chipH / 2f, chipH / 2f, tint)
                c.drawBitmap(edge(w.toInt(), chipH.toInt()), rect.left, rect.top, null)
            } else {
                c.drawRoundRect(rect, chipH / 2f, chipH / 2f, glass)
                c.save(); c.translate(0f, top); rect.offset(0f, -top); c.drawRoundRect(rect, chipH / 2f, chipH / 2f, gloss); rect.offset(0f, top); c.restore()
                c.drawRoundRect(rect, chipH / 2f, chipH / 2f, rim)
            }
            MetaIcons.draw(ch.icon, c, x + pad, top + (chipH - icon) / 2f, icon)
            c.drawText(ch.text, x + pad + icon + gap, top + chipH / 2f - (p.descent() + p.ascent()) / 2f, p)
            x += w + l.pxf(12f)
        }
        if (rest.isNotEmpty()) {
            val start = if (chips.isEmpty()) 0f else x + l.pxf(4f)
            var s = rest
            val max = width - start
            if (plain.measureText(s) > max) { while (s.length > 1 && plain.measureText("$s…") > max) s = s.dropLast(1); s += "…" }
            c.drawText(s, start, top + chipH / 2f - (plain.descent() + plain.ascent()) / 2f, plain)
        }
    }

    private companion object { val YEAR = Regex("^\\d{4}(\\s*[–-]\\s*(\\d{4})?)?$") }
}
