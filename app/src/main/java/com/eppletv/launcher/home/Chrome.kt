package com.eppletv.launcher.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Pill

/**
 * Overlay panel with a header and a vertical list of actions. Used for Control Center (top-right)
 * and the app context menu (centred). Scales and fades in from its anchor; dims the content behind.
 */
class MenuPanel(ctx: Context, private val l: Layout, private val clock: FrameClock, private val onClosed: () -> Unit) : FrameClock.Tick {
    val dim = View(ctx).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        background = GradientDrawable().apply { cornerRadius = l.pxf(40f); setColor(Colors.GLASS_SOLID); setStroke(l.px(1).coerceAtLeast(1), Colors.HAIRLINE) }
        val p = l.px(36); setPadding(p, p, p, p)
        visibility = View.INVISIBLE; alpha = 0f
    }
    private val rows = ArrayList<Pill>()
    private var idx = 0
    private var corner = true
    private val p = Spring(0f, 0.86f, 300f)
    var open = false; private set

    fun params() = FrameLayout.LayoutParams(l.px(520), WRAP_CONTENT)

    fun show(header: List<View>, items: List<Pair<String, () -> Unit>>, atCorner: Boolean, footer: View? = null) {
        panel.removeAllViews(); rows.clear()
        header.forEach { panel.addView(it) }
        items.forEachIndexed { i, (label, action) ->
            val row = Pill(panel.context, label, l.pxf(30f), l.pxf(22f), l.px(28), clock, Colors.FILL, Colors.WHITE, 0.03f, startAligned = true)
            row.setOnClickListener { hide(); action() }
            panel.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, l.px(84)).apply { topMargin = if (i == 0) l.px(26) else l.px(14) })
            rows.add(row)
        }
        footer?.let { panel.addView(it) }
        corner = atCorner
        (panel.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (atCorner) Gravity.TOP or Gravity.END else Gravity.CENTER
            topMargin = if (atCorner) l.px(40) else 0
            rightMargin = if (atCorner) l.left else 0
        }
        panel.requestLayout()
        open = true; idx = 0
        panel.visibility = View.VISIBLE
        rows.firstOrNull()?.requestFocus()
        p.target = 1f; clock.add(this)
    }

    fun hide() {
        if (!open) return
        open = false
        p.target = 0f; clock.add(this)
        onClosed()
    }

    /** Handles navigation keys while open. OK reaches the focused row through normal dispatch. */
    fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (idx > 0) rows[--idx].requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (idx < rows.size - 1) rows[++idx].requestFocus()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (e.repeatCount == 0) hide()
        }
        return true
    }

    override fun tick(dt: Float): Boolean {
        p.step(dt)
        val v = p.value.coerceIn(0f, 1.05f)
        panel.pivotX = if (corner) panel.width.toFloat() else panel.width / 2f
        panel.pivotY = if (corner) 0f else panel.height / 2f
        val s = 0.92f + 0.08f * v
        panel.scaleX = s; panel.scaleY = s
        panel.alpha = v.coerceAtMost(1f); dim.alpha = 0.45f * v.coerceAtMost(1f)
        val moving = !p.atRest()
        if (!moving && !open) panel.visibility = View.INVISIBLE
        return moving
    }
}
