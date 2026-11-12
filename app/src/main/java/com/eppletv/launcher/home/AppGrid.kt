package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import kotlin.math.min

/** The app grid. Scrolls by springing its translationY so the focused row stays in a calm, predictable place. */
@SuppressLint("ViewConstructor")
class AppGrid(
    ctx: Context,
    private val l: Layout,
    private val clock: FrameClock,
    private val host: TileHost,
) : FrameLayout(ctx), FrameClock.Tick {

    private val scroll = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val shadow = makeShadow()
    private val tiles = ArrayList<Tile>()
    private var lastFocusedPackage: String? = null

    val count get() = tiles.size

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** Replaces the tiles. Focus stays on the same app if it still exists. */
    fun setApps(apps: List<AppEntry>) {
        val keep = (findFocus() as? Tile)?.entry?.packageName ?: lastFocusedPackage
        removeAllViews(); tiles.clear()
        apps.forEachIndexed { i, app ->
            val label = TextView(context).apply {
                text = app.label; setTextColor(Color.WHITE); alpha = 0f
                setTextSize(TypedValue.COMPLEX_UNIT_PX, l.pxf(28f)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_HORIZONTAL; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(l.pxf(10f), 0f, l.pxf(2f), 0x99000000.toInt())
            }
            addView(label, LayoutParams(l.tileW, l.labelH).apply { leftMargin = l.x(i); topMargin = l.y(i) + l.tileH + l.labelGap })
            val tile = Tile(context, i, app, shadow, label, l, clock, host)
            addView(tile, LayoutParams(l.tileW, l.tileH).apply { leftMargin = l.x(i); topMargin = l.y(i) })
            tiles.add(tile)
        }
        layoutParams?.let { it.height = l.contentHeight(apps.size); layoutParams = it }
        (tiles.firstOrNull { it.entry.packageName == keep } ?: tiles.firstOrNull())?.requestFocus()
    }

    fun onFocused(tile: Tile) {
        lastFocusedPackage = tile.entry.packageName
        scroll.target = l.scrollFor(tile.index / Layout.COLS)
        clock.add(this)
    }

    /** BACK from deep in the grid: return to the same column in the first row. */
    fun focusFirstRow(column: Int) {
        if (tiles.isNotEmpty()) tiles[min(column, tiles.size - 1)].requestFocus()
    }

    override fun tick(dt: Float): Boolean {
        scroll.step(dt)
        translationY = scroll.value
        return !scroll.atRest()
    }

    private fun makeShadow(): Bitmap {
        val p = l.shadowPad
        val b = Bitmap.createBitmap(l.tileW + 2 * p, l.tileH + 2 * p, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3000000.toInt(); maskFilter = BlurMaskFilter(p * 0.6f, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(b).drawRoundRect(RectF(p.toFloat(), p.toFloat(), (p + l.tileW).toFloat(), (p + l.tileH).toFloat()), l.corner, l.corner, paint)
        b.prepareToDraw()
        return b
    }

    companion object {
        fun params(l: Layout) = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, l.contentHeight(0))
    }
}
