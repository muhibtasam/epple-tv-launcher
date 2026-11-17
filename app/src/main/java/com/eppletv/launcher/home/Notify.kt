package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.system.NotificationHub
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style

/**
 * A new notification, briefly, top right under the clock: a glass card with the app's icon, its name, the title and
 * two lines of text. It never takes focus; while it shows, OK opens it and Back dismisses it, every other button
 * works as usual. Hides itself after six seconds.
 */
@SuppressLint("ViewConstructor")
class NotifBanner(ctx: Context, private val l: Layout, private val clock: FrameClock, backdrop: Backdrop) : FrameLayout(ctx), FrameClock.Tick {
    private val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, l.pxf(14f)) } } }
    private val app = TextView(ctx).apply { style(Txt.CAPTION, l.u, Colors.TEXT_DIM); maxLines = 1 }
    private val title = TextView(ctx).apply { style(Txt.ROW, l.u); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val text = TextView(ctx).apply { style(Txt.META, l.u, 0xD9FFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val p = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val row: LinearLayout
    var note: NotificationHub.Note? = null; private set
    val showing get() = note != null

    init {
        visibility = INVISIBLE; alpha = 0f
        addView(TabGlass(ctx, l, backdrop, l.pxf(30f)), LayoutParams(MATCH_PARENT, MATCH_PARENT))
        row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; val pd = l.px(22); setPadding(pd, pd, pd, pd) }
        row.addView(icon, LinearLayout.LayoutParams(l.px(72), l.px(72)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(app); col.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(2) })
        col.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(4) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = l.px(20) })
        addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** As tall as its text: the glass behind follows the content's height, never the screen's. */
    override fun onMeasure(w: Int, h: Int) {
        row.measure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(row.measuredHeight, MeasureSpec.EXACTLY))
    }

    private val autoHide = Runnable { hide() }

    fun show(n: NotificationHub.Note) {
        note = n
        app.text = n.app.uppercase(); title.text = n.title; text.text = n.text; text.visibility = if (n.text.isEmpty()) GONE else VISIBLE
        icon.setImageBitmap(n.icon); icon.visibility = if (n.icon == null) GONE else VISIBLE
        visibility = VISIBLE; p.target = 1f; clock.add(this)
        removeCallbacks(autoHide); postDelayed(autoHide, 6000)
    }

    /** Called once the banner is dismissed (by itself or the remote). */
    var onHide: (() -> Unit)? = null
    fun hide() { if (note == null) return; note = null; removeCallbacks(autoHide); p.target = 0f; clock.add(this); onHide?.invoke() }

    override fun tick(dt: Float): Boolean {
        p.step(dt); val v = p.value.coerceIn(0f, 1f)
        alpha = v; translationX = (1f - v) * l.pxf(60f)
        if (p.atRest() && note == null) visibility = INVISIBLE
        return !p.atRest()
    }
}
