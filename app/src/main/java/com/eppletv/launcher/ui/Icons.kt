package com.eppletv.launcher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Epple TV's icon set: every icon in the launcher, in the style of the Home menu icons. No image assets: each icon is
 * drawn from paths on a 24-unit grid, so it is sharp at any size and takes its colour from the text beside it.
 *
 * The style, measured from the Home menu set:
 *  - geometry: a 24 x 24 grid, artwork kept within 2.5..21.5, optical centre at 12,12;
 *  - stroke: 2.1 units, round caps and round joins (a heavier 2.8 only for a handle that must read at small sizes);
 *  - corners: rounded rectangles at 2.4..3 units, never sharp;
 *  - colour: one colour, duotone: an outline plus at most one solid accent shape;
 *  - states: the accent shows at 55 % at rest and 100 % when active or focused ([accent] 0..1); focus also swaps the
 *    colour (white on glass, ink on a white focused surface), which callers do through the paint's colour.
 */
internal object EppleIcons {
    // Sections (the Home menu set).
    const val HOME = 0; const val MOVIES = 1; const val TV = 2; const val LIBRARY = 3; const val APPS = 4; const val SEARCH = 5; const val SETTINGS = 6
    // Actions.
    const val PLAY = 7; const val INFO = 8; const val MORE = 9; const val REMOVE = 10; const val WATCHLIST = 11; const val WATCHLIST_ON = 12
    const val FAVOURITE = 13; const val FAVOURITE_ON = 14; const val OPEN = 15; const val LEFT = 16; const val RIGHT = 17; const val PIN = 18
    const val TRASH = 19; const val PROFILE = 20; const val TOGGLES = 21; const val PICTURE = 22
    // Control Center.
    const val POWER = 23; const val WIFI = 24; const val INPUT = 25; const val VOLUME = 26; const val MOON = 27; const val BLUETOOTH = 28
    const val SLIDERS = 29; const val BELL = 30
    // Notifications.
    const val DOWNLOAD = 31; const val WIFI_OFF = 32
    // Title facts (the info rows' rating and year chips).
    const val STAR = 33; const val CALENDAR = 34

    const val STROKE = 2.1f
    private val path = Path(); private val q = Path()
    private val r = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val heart = Path().apply {
        moveTo(12f, 20.2f); cubicTo(6f, 16.2f, 3.2f, 12.8f, 3.2f, 9.3f); cubicTo(3.2f, 6.5f, 5.3f, 4.4f, 7.9f, 4.4f)
        cubicTo(9.6f, 4.4f, 11.1f, 5.3f, 12f, 6.7f); cubicTo(12.9f, 5.3f, 14.4f, 4.4f, 16.1f, 4.4f)
        cubicTo(18.7f, 4.4f, 20.8f, 6.5f, 20.8f, 9.3f); cubicTo(20.8f, 12.8f, 18f, 16.2f, 12f, 20.2f); close()
    }
    private val star = Path().apply {
        for (k in 0 until 10) {
            val a = Math.toRadians(-90.0 + k * 36.0); val rad = if (k % 2 == 0) 9.2 else 4.1
            val x = (12.0 + rad * Math.cos(a)).toFloat(); val y = (12.9 + rad * Math.sin(a)).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    private val bookmark = Path().apply {
        moveTo(8f, 3.4f); lineTo(16f, 3.4f); quadTo(18.4f, 3.4f, 18.4f, 5.8f); lineTo(18.4f, 20.4f); lineTo(12f, 16.2f)
        lineTo(5.6f, 20.4f); lineTo(5.6f, 5.8f); quadTo(5.6f, 3.4f, 8f, 3.4f); close()
    }

    private val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The Home menu's icon treatment, shared: a soft circle behind the icon, [d] px across, centred at [cx],[cy].
     *  - rest: white icon, accent at 55 %, on a 22 % white circle;
     *  - selected (the current section, still shown after focus leaves): a brighter 36 % circle, accent in full;
     *  - focused (the row has become a white surface): the circle turns a faint dark tint and the icon dark ink.
     * [focus] and [selected] run 0..1 (they animate). [p] is the caller's stroke paint; its colour is set here.
     */
    fun well(icon: Int, c: Canvas, cx: Float, cy: Float, d: Float, iconSize: Float, p: Paint, focus: Float, selected: Float, alpha: Float = 1f) {
        val f = focus.coerceIn(0f, 1f)
        wellPaint.color = Colors.lerp(if (selected > 0.5f) 0x5CFFFFFF else 0x38FFFFFF, 0x14000000, f)
        wellPaint.alpha = ((wellPaint.color ushr 24) * alpha).toInt()
        c.drawCircle(cx, cy, d / 2f, wellPaint)
        p.color = Colors.lerp(Colors.WHITE, Colors.INK, f); p.alpha = (255 * alpha).toInt()
        draw(icon, c, cx, cy, iconSize, p, maxOf(f, selected))
    }

    /**
     * Draws [icon] centred at [cx],[cy], [size] px square, with [p] (a STROKE paint whose colour and alpha are the icon's).
     * [accent] 0..1 lights the solid accent shape: the active or focused state shows it in full.
     */
    fun draw(icon: Int, c: Canvas, cx: Float, cy: Float, size: Float, p: Paint, accent: Float = 0f) {
        val u = size / 24f
        c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(u, u)
        p.style = Paint.Style.STROKE; p.strokeWidth = STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fill.color = p.color; fill.alpha = (p.alpha * (0.55f + 0.45f * accent.coerceIn(0f, 1f))).toInt()
        path.reset(); q.reset()
        fun rr(l: Float, t: Float, rt: Float, b: Float, rad: Float, paint: Paint = p) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, paint) }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = c.drawLine(x0, y0, x1, y1, p)
        when (icon) {
            HOME -> {
                path.moveTo(3.2f, 11.2f); path.lineTo(12f, 3.9f); path.lineTo(20.8f, 11.2f)
                path.moveTo(5.6f, 9.6f); path.lineTo(5.6f, 18.4f); path.quadTo(5.6f, 20.6f, 7.8f, 20.6f); path.lineTo(16.2f, 20.6f)
                path.quadTo(18.4f, 20.6f, 18.4f, 18.4f); path.lineTo(18.4f, 9.6f); c.drawPath(path, p)
                rr(9.7f, 13.6f, 14.3f, 20.6f, 1.6f, fill)
            }
            MOVIES -> {
                rr(3.4f, 9.8f, 20.6f, 20.4f, 2.6f)
                // The clapper, tilted open, as the solid accent.
                path.moveTo(3.4f, 8.6f); path.lineTo(20.2f, 4.6f); path.lineTo(19.3f, 1.4f); path.lineTo(2.5f, 5.4f); path.close()
                c.drawPath(path, fill); path.reset()
                line(3.4f, 13.6f, 20.6f, 13.6f)
            }
            TV -> {
                rr(2.6f, 5.4f, 21.4f, 17.4f, 3f)
                line(8.2f, 20.8f, 15.8f, 20.8f)
                path.moveTo(10.2f, 8.6f); path.lineTo(15.4f, 11.4f); path.lineTo(10.2f, 14.2f); path.close(); c.drawPath(path, fill)
            }
            LIBRARY -> { rr(4f, 9.5f, 20f, 20.5f, 2.5f); line(6.5f, 6.5f, 17.5f, 6.5f); line(9f, 3.5f, 15f, 3.5f) }
            APPS -> {
                rr(3.4f, 3.4f, 10.6f, 10.6f, 2.4f); rr(3.4f, 13.4f, 10.6f, 20.6f, 2.4f); rr(13.4f, 13.4f, 20.6f, 20.6f, 2.4f)
                c.drawCircle(17f, 7f, 3.8f, fill)
            }
            SEARCH -> {
                c.drawCircle(10.4f, 10.4f, 4.6f, fill)
                c.drawCircle(10.4f, 10.4f, 6.8f, p)
                p.strokeWidth = 2.8f; line(15.4f, 15.4f, 20.6f, 20.6f); p.strokeWidth = STROKE
            }
            SETTINGS -> {
                c.drawCircle(12f, 12f, 3.2f, fill); c.drawCircle(12f, 12f, 7f, p)
                for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                    path.moveTo(12f + ca * 7f, 12f + sa * 7f); path.lineTo(12f + ca * 10f, 12f + sa * 10f) }
                c.drawPath(path, p)
            }
            PLAY -> {
                c.drawCircle(12f, 12f, 9.2f, p)
                path.moveTo(10f, 8.1f); path.lineTo(16.3f, 12f); path.lineTo(10f, 15.9f); path.close(); c.drawPath(path, fill)
            }
            INFO -> { c.drawCircle(12f, 12f, 9.2f, p); c.drawCircle(12f, 7.9f, 1.5f, fill); rr(10.9f, 10.6f, 13.1f, 16.6f, 1.1f, fill) }
            MORE -> { c.drawCircle(12f, 12f, 9.2f, p); for (x in floatArrayOf(7.7f, 12f, 16.3f)) c.drawCircle(x, 12f, 1.7f, fill) }
            REMOVE -> { c.drawCircle(12f, 12f, 9.2f, p); line(9f, 9f, 15f, 15f); line(15f, 9f, 9f, 15f) }
            WATCHLIST -> { c.drawPath(bookmark, p); line(12f, 7.6f, 12f, 12.8f); line(9.4f, 10.2f, 14.6f, 10.2f) }
            WATCHLIST_ON -> { c.drawPath(bookmark, fill); c.drawPath(bookmark, p) }
            FAVOURITE -> c.drawPath(heart, p)
            FAVOURITE_ON -> { c.drawPath(heart, fill); c.drawPath(heart, p) }
            OPEN -> {
                rr(3.6f, 6.4f, 17.6f, 20.4f, 2.6f)
                line(11.2f, 12.8f, 20.4f, 3.6f); path.moveTo(14.6f, 3.6f); path.lineTo(20.4f, 3.6f); path.lineTo(20.4f, 9.4f); c.drawPath(path, p)
            }
            LEFT -> { path.moveTo(14.4f, 5.4f); path.lineTo(7.8f, 12f); path.lineTo(14.4f, 18.6f); c.drawPath(path, p) }
            RIGHT -> { path.moveTo(9.6f, 5.4f); path.lineTo(16.2f, 12f); path.lineTo(9.6f, 18.6f); c.drawPath(path, p) }
            PIN -> {
                path.moveTo(9.2f, 3.6f); path.lineTo(14.8f, 3.6f); path.lineTo(14.2f, 9.2f); path.lineTo(17.4f, 12.8f)
                path.lineTo(6.6f, 12.8f); path.lineTo(9.8f, 9.2f); path.close(); c.drawPath(path, fill); c.drawPath(path, p)
                line(12f, 12.8f, 12f, 20.6f)
            }
            TRASH -> {
                line(4.4f, 6.6f, 19.6f, 6.6f)
                path.moveTo(9.4f, 6.6f); path.lineTo(9.4f, 4.6f); path.quadTo(9.4f, 3.4f, 10.6f, 3.4f); path.lineTo(13.4f, 3.4f)
                path.quadTo(14.6f, 3.4f, 14.6f, 4.6f); path.lineTo(14.6f, 6.6f); c.drawPath(path, p)
                path.reset(); path.moveTo(6.4f, 6.6f); path.lineTo(7.2f, 18.6f); path.quadTo(7.4f, 20.6f, 9.4f, 20.6f); path.lineTo(14.6f, 20.6f)
                path.quadTo(16.6f, 20.6f, 16.8f, 18.6f); path.lineTo(17.6f, 6.6f); c.drawPath(path, p)
                rr(10.9f, 10.2f, 13.1f, 17f, 1.1f, fill)
            }
            PROFILE -> {
                c.drawCircle(12f, 8.4f, 3.9f, fill)
                path.moveTo(4.6f, 20.2f); path.quadTo(4.6f, 13.8f, 12f, 13.8f); path.quadTo(19.4f, 13.8f, 19.4f, 20.2f); c.drawPath(path, p)
            }
            TOGGLES -> {
                rr(3.4f, 4.4f, 20.6f, 10.8f, 3.2f); rr(3.4f, 13.2f, 20.6f, 19.6f, 3.2f)
                c.drawCircle(17.2f, 7.6f, 1.9f, fill); c.drawCircle(6.8f, 16.4f, 1.9f, fill)
            }
            PICTURE -> {
                rr(3f, 4.5f, 21f, 19.5f, 2.6f)
                path.moveTo(5.5f, 17f); path.lineTo(10f, 11.5f); path.lineTo(13f, 14.8f); path.lineTo(15f, 12.8f); path.lineTo(18.5f, 17f); path.close(); c.drawPath(path, fill)
                c.drawCircle(15.8f, 8.8f, 1.6f, fill)
            }
            POWER -> { r.set(4.5f, 5.5f, 19.5f, 20.5f); c.drawArc(r, -55f, 290f, false, p); line(12f, 3f, 12f, 11.5f) }
            WIFI -> {
                for (rad in floatArrayOf(4.2f, 8.4f, 12.6f)) { r.set(12f - rad, 19f - rad, 12f + rad, 19f + rad); c.drawArc(r, 225f, 90f, false, p) }
                c.drawCircle(12f, 19f, 1.7f, fill)
            }
            INPUT -> {
                rr(3f, 4.5f, 21f, 16.5f, 2.6f); line(8.5f, 20f, 15.5f, 20f)
                path.moveTo(8f, 10.5f); path.lineTo(14.5f, 10.5f); path.moveTo(12f, 8f); path.lineTo(14.5f, 10.5f); path.lineTo(12f, 13f); c.drawPath(path, p)
            }
            VOLUME -> {
                path.moveTo(3.5f, 9.3f); path.lineTo(7f, 9.3f); path.lineTo(11.6f, 5.4f); path.lineTo(11.6f, 18.6f); path.lineTo(7f, 14.7f); path.lineTo(3.5f, 14.7f); path.close(); c.drawPath(path, fill)
                r.set(10.5f, 8.5f, 17.5f, 15.5f); c.drawArc(r, -50f, 100f, false, p)
                r.set(9f, 5f, 21f, 19f); c.drawArc(r, -50f, 100f, false, p)
            }
            MOON -> { path.addCircle(12f, 12f, 8.5f, Path.Direction.CW); q.addCircle(16.8f, 7.6f, 7.4f, Path.Direction.CW); path.op(q, Path.Op.DIFFERENCE); c.drawPath(path, fill) }
            BLUETOOTH -> { path.moveTo(6.8f, 7.6f); path.lineTo(16.8f, 16.4f); path.lineTo(12f, 20.6f); path.lineTo(12f, 3.4f); path.lineTo(16.8f, 7.6f); path.lineTo(6.8f, 16.4f); c.drawPath(path, p) }
            SLIDERS -> {
                line(3.5f, 7f, 20.5f, 7f); line(3.5f, 12f, 20.5f, 12f); line(3.5f, 17f, 20.5f, 17f)
                c.drawCircle(15.5f, 7f, 2.4f, fill); c.drawCircle(8.5f, 12f, 2.4f, fill); c.drawCircle(13.5f, 17f, 2.4f, fill)
            }
            DOWNLOAD -> {
                rr(3.4f, 3.4f, 20.6f, 20.6f, 3f)
                line(12f, 7.4f, 12f, 14.6f); path.moveTo(8.8f, 11.4f); path.lineTo(12f, 14.6f); path.lineTo(15.2f, 11.4f); c.drawPath(path, p)
                rr(7.4f, 16.6f, 16.6f, 18.4f, 0.9f, fill)
            }
            WIFI_OFF -> {
                val a = p.alpha; p.alpha = (a * 0.45f).toInt()
                for (rad in floatArrayOf(8.4f, 12.6f)) { r.set(12f - rad, 19f - rad, 12f + rad, 19f + rad); c.drawArc(r, 225f, 90f, false, p) }
                p.alpha = a; line(4.2f, 4.2f, 19.8f, 19.8f)
                c.drawCircle(12f, 19f, 1.7f, fill)
            }
            STAR -> { c.drawPath(star, fill); c.drawPath(star, p) }
            CALENDAR -> {
                // The header band is the solid accent; the rings and the body are the outline.
                path.addRoundRect(RectF(3.6f, 5.2f, 20.4f, 20.6f), 2.8f, 2.8f, Path.Direction.CW)
                c.save(); c.clipPath(path); c.drawRect(3.6f, 5.2f, 20.4f, 10f, fill); c.restore()
                c.drawPath(path, p); line(8.2f, 3.2f, 8.2f, 6.8f); line(15.8f, 3.2f, 15.8f, 6.8f)
            }
            BELL -> {
                path.moveTo(5.5f, 16.5f); path.lineTo(18.5f, 16.5f)
                path.moveTo(7f, 16.5f); path.lineTo(7f, 11f); path.cubicTo(7f, 7.6f, 9.2f, 5.4f, 12f, 5.4f); path.cubicTo(14.8f, 5.4f, 17f, 7.6f, 17f, 11f); path.lineTo(17f, 16.5f)
                c.drawPath(path, p); line(10.3f, 19.6f, 13.7f, 19.6f); line(12f, 3.6f, 12f, 5.4f)
            }
        }
        c.restore()
    }
}

/** An [EppleIcons] icon as a Drawable, for text views (a pill's leading icon, an empty state's picture). */
internal class IconDrawable(private val icon: Int, private val px: Int, var accent: Float = 1f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var color: Int
        get() = p.color
        set(v) { if (p.color != v) { p.color = v; invalidateSelf() } }
    override fun draw(c: Canvas) { EppleIcons.draw(icon, c, bounds.exactCenterX(), bounds.exactCenterY(), px.toFloat(), p, accent) }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun setAlpha(a: Int) { p.alpha = a; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
