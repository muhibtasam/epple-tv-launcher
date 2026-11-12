package com.eppletv.launcher.home

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home geometry on a 1920x1080 canvas, scaled by [u] to the real window.
 * Six 16:9 tiles per row inside 80 px side margins (safe area), 40 px gutters.
 */
class Layout(val u: Float) {
    fun px(v: Int): Int = (v * u).roundToInt()
    fun pxf(v: Float): Float = v * u

    val tileW = px(260); val tileH = px(146)
    val pitchX = px(300)
    val left = px(80)
    val corner = pxf(14f)
    val shadowPad = px(40)
    val labelTop = px(30); val labelH = px(40)
    val stripH = tileH + px(90)

    // Home: hero above a top row ("dock"), titled sections below.
    val dockY = px(640)
    private val sectionPitch = px(300)
    fun sectionTitleY(k: Int) = px(884) + (k - 1) * sectionPitch
    fun sectionTilesY(k: Int) = if (k == 0) dockY else sectionTitleY(k) + px(60)
    /** Keeps the focused section at a fixed height, but never scrolls past the end of the content. */
    fun homeScroll(k: Int, count: Int): Float {
        if (k == 0) return 0f
        val wanted = sectionTilesY(k) - px(420)
        val end = sectionTilesY(count - 1) + tileH + px(170) - px(1080)
        return -minOf(wanted, max(0, end)).toFloat()
    }
    fun homeHeight(n: Int) = sectionTilesY(max(0, n - 1)) + px(700)

    // Apps: a plain grid under a page title.
    val gridTop = px(260); val gridPitchY = px(250)
    fun gridScroll(row: Int): Float = -(max(0, row - 1) * gridPitchY).toFloat()
    fun gridHeight(rows: Int) = gridTop + rows * gridPitchY + px(500)

    companion object {
        const val COLS = 6
        fun forWindow(w: Int, h: Int) = Layout(minOf(w / 1920f, h / 1080f))
    }
}
