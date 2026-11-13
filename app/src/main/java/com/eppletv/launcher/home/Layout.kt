package com.eppletv.launcher.home

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home geometry on a 1920x1080 canvas, scaled by [u] to the real window. Measured from tvOS reference screens:
 * six tiles per row, a glass platter around the top row sitting low on the artwork, the next row just peeking in.
 */
class Layout(val u: Float) {
    fun px(v: Int): Int = (v * u).roundToInt()
    fun pxf(v: Float): Float = v * u

    val tileW = px(250); val tileH = px(141)      // 16:9, the native shape of Android TV banners
    val pitchX = px(294)
    val left = px(100)
    val corner = pxf(24f)
    val shadowPad = px(44)
    val labelTop = px(26); val labelH = px(40)

    val dockY = px(832)                            // top row of tiles
    val platterPad = px(38); val platterRadius = pxf(66f)
    val rowPitch = px(225)
    fun rowY(r: Int) = dockY + r * rowPitch

    /** Row 0 shows the artwork. Below it the page slides up so the top row sits at the top of the screen, then scrolls row by row. */
    fun scroll(r: Int): Float = if (r == 0) 0f else -(dockY - px(96) + max(0, r - 3) * rowPitch).toFloat()
    fun height(rows: Int) = rowY(rows) + px(900)

    companion object {
        const val COLS = 6
        fun forWindow(w: Int, h: Int) = Layout(minOf(w / 1920f, h / 1080f))
    }
}
