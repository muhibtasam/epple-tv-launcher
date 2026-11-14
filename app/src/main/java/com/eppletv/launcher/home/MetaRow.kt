package com.eppletv.launcher.home

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
    private fun lg(y0: Float, y1: Float, vararg c: Int) = LinearGradient(0f, y0, 0f, y1, c, null, Shader.TileMode.CLAMP)
    private val starFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 4f, 0f, 34f, intArrayOf(0xFFFFF1A6.toInt(), 0xFFFFC21A.toInt(), 0xFFC46A00.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) }
    private val starEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.ROUND; color = 0xFF9A5200.toInt() }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lg(6f, 21f, 0xB3FFFFFF.toInt(), 0x00FFFFFF) }
    private val slate = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lg(10f, 36f, 0xFF5B6474.toInt(), 0xFF1B1F27.toInt()) }
    private val slateEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF0E1116.toInt() }
    private val clap = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(5f, 0f, 35f, 0f, 0xFFF4F6FA.toInt(), 0xFFB9C1CE.toInt(), Shader.TileMode.CLAMP) }
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF20252D.toInt() }
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lg(12f, 28f, 0xFF8FD3FF.toInt(), 0xFF1667C9.toInt()) }
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lg(8f, 36f, 0xFFFFFFFF.toInt(), 0xFFCDD3DD.toInt()) }
    private val paperEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF8C95A3.toInt() }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lg(8f, 16f, 0xFFFF7A6E.toInt(), 0xFFD3261C.toInt()) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7380.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; color = 0xFF2B3038.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val star = Path().apply {
        moveTo(20f, 3.5f); lineTo(24.9f, 13.7f); lineTo(36.1f, 15.1f); lineTo(27.9f, 22.9f); lineTo(30f, 34f)
        lineTo(20f, 28.6f); lineTo(10f, 34f); lineTo(12.1f, 22.9f); lineTo(3.9f, 15.1f); lineTo(15.1f, 13.7f); close()
    }
    private val starShine = Path().apply { moveTo(20f, 7f); lineTo(23.8f, 14.9f); lineTo(32.2f, 16f); lineTo(20f, 20.5f); lineTo(7.8f, 16f); lineTo(16.2f, 14.9f); close() }
    private val r = RectF(); private val p = Path(); private val m = Matrix()

    fun draw(id: Int, c: Canvas, x: Float, y: Float, size: Float) {
        c.save(); c.translate(x, y); c.scale(size / 40f, size / 40f)
        // Soft contact shadow under every icon (a blurred ellipse would cost a layer; an offset translucent copy is enough).
        when (id) {
            STAR -> {
                c.save(); c.translate(0.6f, 2.2f); c.drawPath(star, shadow); c.restore()
                c.drawPath(star, starFill); c.drawPath(star, starEdge); c.drawPath(starShine, shine)
            }
            MOVIE -> {
                r.set(5f, 17f, 35f, 37f); c.drawRoundRect(r, 3.5f, 3.5f, shadow)
                r.set(5f, 15f, 35f, 35f); c.drawRoundRect(r, 3.5f, 3.5f, slate); c.drawRoundRect(r, 3.5f, 3.5f, slateEdge)
                r.set(7f, 17f, 33f, 20f); c.drawRoundRect(r, 1.5f, 1.5f, shine)
                c.save(); c.rotate(-14f, 6f, 13f)
                r.set(5f, 7f, 35f, 14f); c.drawRoundRect(r, 2f, 2f, clap)
                p.reset(); for (sx in intArrayOf(10, 17, 24, 31)) { p.moveTo(sx.toFloat(), 7f); p.lineTo(sx - 4f, 14f) }
                c.save(); c.clipRect(r); c.drawPath(p, stripe); c.restore()
                c.restore()
            }
            SERIES -> {
                r.set(4f, 11f, 36f, 33f); c.drawRoundRect(r, 4f, 4f, shadow)
                r.set(4f, 9f, 36f, 31f); c.drawRoundRect(r, 4f, 4f, slate); c.drawRoundRect(r, 4f, 4f, slateEdge)
                r.set(7.5f, 12f, 32.5f, 27.5f); c.drawRoundRect(r, 2f, 2f, screen)
                r.set(8.5f, 13f, 31.5f, 17.5f); c.drawRoundRect(r, 1.5f, 1.5f, shine)
                c.drawLine(14f, 35f, 26f, 35f, line.apply { color = 0xFF9AA3B0.toInt(); strokeWidth = 2.4f })
                line.color = 0xFF2B3038.toInt(); line.strokeWidth = 2f
            }
            YEAR -> {
                r.set(5f, 10f, 35f, 38f); c.drawRoundRect(r, 5f, 5f, shadow)
                r.set(5f, 8f, 35f, 36f); c.drawRoundRect(r, 5f, 5f, paper); c.drawRoundRect(r, 5f, 5f, paperEdge)
                p.reset(); p.addRoundRect(r, 5f, 5f, Path.Direction.CW)
                c.save(); c.clipPath(p); c.drawRect(5f, 8f, 35f, 16f, band); c.restore()
                r.set(12f, 4f, 15f, 12f); c.drawRoundRect(r, 1.5f, 1.5f, ring); r.set(25f, 4f, 28f, 12f); c.drawRoundRect(r, 1.5f, 1.5f, ring)
                c.drawLine(11f, 23f, 29f, 23f, line); c.drawLine(11f, 29f, 23f, 29f, line)
            }
        }
        c.restore()
    }
}

/**
 * A title's facts as glass chips (Info design 1): rating with a gold star, Movie or Series with its icon, the year
 * with a calendar, then the rest (language, runtime, genres) as plain text. The facts come from the "  ·  "-joined
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
        for (ch in chips) {
            val p = if (ch.numeric) num else word
            val w = pad + icon + gap + p.measureText(ch.text) + padR
            rect.set(x, top, x + w, top + chipH)
            c.drawRoundRect(rect, chipH / 2f, chipH / 2f, glass)
            c.save(); c.translate(0f, top); rect.offset(0f, -top); c.drawRoundRect(rect, chipH / 2f, chipH / 2f, gloss); rect.offset(0f, top); c.restore()
            c.drawRoundRect(rect, chipH / 2f, chipH / 2f, rim)
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
