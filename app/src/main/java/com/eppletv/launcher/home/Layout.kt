package com.eppletv.launcher.home

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home-screen geometry, designed on a 1920x1080 canvas and scaled by [u] to the real window.
 * Grid follows the tvOS 5-column rhythm: 320-wide tiles, 40 gaps, 80 side margins (safe area).
 */
class Layout(val u: Float) {
    fun px(v: Int): Int = (v * u).roundToInt()
    fun pxf(v: Float): Float = v * u

    val tileW = px(320)
    val tileH = px(180)
    val gapX = px(40)
    val pitchY = px(290)           // tile + label + gap
    val left = px(80)
    val top = px(430)              // first row sits below the top shelf
    val shelfShift = px(270)       // how far the shelf scrolls away when focus enters row 2+
    val corner = pxf(14f)
    val shadowPad = px(40)
    val labelGap = px(22)
    val labelH = px(44)

    fun x(i: Int) = left + (i % COLS) * (tileW + gapX)
    fun y(i: Int) = top + (i / COLS) * pitchY

    /** Grid translation that keeps the focused row in view: row 0 shows the shelf, deeper rows keep one row of context above. */
    fun scrollFor(row: Int): Float = if (row == 0) 0f else -(shelfShift + max(0, row - 1) * pitchY).toFloat()

    fun contentHeight(count: Int): Int = top + ((count + COLS - 1) / COLS) * pitchY + px(200)

    companion object {
        const val COLS = 5
        fun forWindow(w: Int, h: Int) = Layout(minOf(w / 1920f, h / 1080f))
    }
}
