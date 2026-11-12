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

/** Top navigation: a centred glass tab group and a status pill (time) that opens Control Center. */
class TopBar(
    ctx: Context, private val l: Layout, private val clock: FrameClock, tabs: List<String>,
    private val onTab: (Int) -> Unit, private val onConfirm: () -> Unit, onStatus: () -> Unit,
) : FrameClock.Tick {
    val view = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val pills = ArrayList<Pill>()
    private val status = Pill(ctx, "", l.pxf(28f), l.pxf(28f), l.px(28), clock, Colors.GLASS, Colors.WHITE, 0.05f)
    private var index = 0
    var tab = 0; private set
    private val vis = Spring(1f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 3f)

    init {
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            background = GradientDrawable().apply { cornerRadius = l.pxf(34f); setColor(Colors.GLASS) }
            val p = l.px(6); setPadding(p, p, p, p)
        }
        tabs.forEachIndexed { i, t ->
            val pill = Pill(ctx, t, l.pxf(28f), l.pxf(28f), l.px(34), clock, Color.TRANSPARENT, Colors.TEXT_DIM, 0.05f)
            pill.setOnClickListener { onConfirm() }
            group.addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, l.px(56)).apply { if (i > 0) leftMargin = l.px(6) })
            pills.add(pill)
        }
        pills[0].selectedState = true
        view.addView(group, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        status.setOnClickListener { onStatus() }
        view.addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, l.px(56), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = l.left })
    }

    fun setTime(text: String, description: String) { status.text = text; status.contentDescription = description }

    /** Focus enters the bar on the current tab. */
    fun enter() { index = tab; pills[index].requestFocus() }
    fun refocus() { target(index).requestFocus() }

    fun move(dx: Int) {
        val n = (index + dx).coerceIn(0, pills.size)
        if (n == index) return
        index = n
        target(n).requestFocus()
        if (n < pills.size) select(n)
    }

    fun select(i: Int) {
        if (i == tab) return
        tab = i
        pills.forEachIndexed { j, p -> p.selectedState = j == i }
        onTab(i)
    }

    fun show(visible: Boolean) { vis.target = if (visible) 1f else 0f; clock.add(this) }

    private fun target(i: Int): View = if (i < pills.size) pills[i] else status

    override fun tick(dt: Float): Boolean {
        vis.step(dt)
        view.alpha = vis.value; view.translationY = (vis.value - 1f) * l.pxf(30f)
        return !vis.atRest()
    }
}

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

    fun show(header: List<View>, items: List<Pair<String, () -> Unit>>, atCorner: Boolean) {
        panel.removeAllViews(); rows.clear()
        header.forEach { panel.addView(it) }
        items.forEachIndexed { i, (label, action) ->
            val row = Pill(panel.context, label, l.pxf(30f), l.pxf(22f), l.px(28), clock, Colors.FILL, Colors.WHITE, 0.03f, startAligned = true)
            row.setOnClickListener { hide(); action() }
            panel.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, l.px(84)).apply { topMargin = if (i == 0) l.px(26) else l.px(14) })
            rows.add(row)
        }
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
