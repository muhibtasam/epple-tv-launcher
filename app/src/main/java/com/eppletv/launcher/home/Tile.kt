package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring

interface TileHost {
    val dirX: Int
    val dirY: Int
    fun onTileFocused(tile: Tile)
    fun onTileClicked(tile: Tile)
    fun onTileLongPressed(tile: Tile)
}

/**
 * One app tile: shadow, banner and specular highlight inside a group that is scaled and tilted as a whole.
 * Only layer properties (scale, rotation, Z, alpha, translation) change during motion, so nothing is redrawn.
 */
@SuppressLint("ViewConstructor")
class Tile(
    ctx: Context,
    val entry: AppEntry,
    shadowBmp: Bitmap,
    private val label: TextView?,
    private val l: Layout,
    private val clock: FrameClock,
    private val host: TileHost,
) : FrameLayout(ctx), FrameClock.Tick {

    private val focus = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val press = Spring(0f, Motion.PRESS_DAMPING, Motion.PRESS_STIFFNESS)
    private val tiltX = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS, Motion.REST_DEG)
    private val tiltY = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS, Motion.REST_DEG)
    private var pressed = false
    var inDock = false
    private var longPressed = false

    private val shadow = ImageView(ctx).apply { setImageBitmap(shadowBmp); alpha = 0f; scaleType = ImageView.ScaleType.FIT_XY }

    // Layered artwork: banner (parallax layer, drawn with its own rounded corners; no view clipping, which is costly on
    // this GPU), static gloss, moving specular light, light rim.
    private val banner = BannerView(ctx, entry.banner, l.corner)
    private val gloss = View(ctx).apply {
        // Top light and a faint darker base: the tile reads as a lit, slightly convex surface rather than a flat image.
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x05FFFFFF, 0x00000000, 0x14000000))
            .apply { cornerRadius = l.corner }
    }
    private val highlight = View(ctx).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = l.pxf(300f)
            colors = intArrayOf(0x66FFFFFF, 0x14FFFFFF, 0x00FFFFFF); setGradientCenter(0.25f, -0.1f); cornerRadius = l.corner
        }
        alpha = 0f
    }
    private val rim = ImageView(ctx).apply { setImageBitmap(rimFor(l)); scaleType = ImageView.ScaleType.FIT_XY; alpha = 0.45f }

    init {
        isFocusable = true
        clipChildren = false
        outlineProvider = null          // Z only orders drawing; the soft shadow is our own bitmap
        cameraDistance = 12000f * l.u
        contentDescription = entry.label
        val p = l.shadowPad
        shadow.visibility = View.INVISIBLE
        addView(shadow, LayoutParams(l.tileW + 2 * p, l.tileH + 2 * p).apply { leftMargin = -p; topMargin = -p + l.px(14) })
        addView(banner, LayoutParams(l.tileW, l.tileH))
        addView(gloss, LayoutParams(l.tileW, l.tileH))
        addView(highlight, LayoutParams(l.tileW, l.tileH))
        addView(rim, LayoutParams(l.tileW, l.tileH))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focus.target = if (gainFocus) 1f else 0f
        if (gainFocus) {
            // Kick the tile in the direction of travel; it springs back flat.
            tiltX.value = -host.dirY * Motion.TILT_DEG
            tiltY.value = host.dirX * Motion.TILT_DEG
            host.onTileFocused(this)
        } else {
            pressed = false; press.target = 0f
        }
        clock.add(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isSelect(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) { pressed = true; longPressed = false; press.target = 1f; clock.add(this); event.startTracking() }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isSelect(keyCode) || !pressed) return super.onKeyLongPress(keyCode, event)
        pressed = false; longPressed = true; press.target = 0f; clock.add(this)
        host.onTileLongPressed(this)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isSelect(keyCode)) return super.onKeyUp(keyCode, event)
        if (longPressed) { longPressed = false; return true }
        if (pressed && !event.isCanceled) {
            pressed = false; press.target = 0f; clock.add(this)
            host.onTileClicked(this)
        }
        return true
    }

    override fun tick(dt: Float): Boolean {
        focus.step(dt); press.step(dt); tiltX.step(dt); tiltY.step(dt)
        val v = focus.value
        val s = (1f + Motion.FOCUS_SCALE * v) * (1f - Motion.PRESS_SCALE * press.value)
        scaleX = s; scaleY = s
        translationZ = v * l.pxf(10f)
        rotationX = tiltX.value; rotationY = tiltY.value
        // Depth: the shadow grows, softens and drops as the tile lifts.
        shadow.alpha = v
        shadow.visibility = if (v > 0.002f) View.VISIBLE else View.INVISIBLE   // unfocused tiles draw no shadow
        val ss = 1f + 0.06f * v; shadow.scaleX = ss; shadow.scaleY = ss; shadow.translationY = v * l.pxf(14f)
        // Parallax: the artwork layer sits slightly behind the glass and shifts against the tilt.
        banner.parallax(1f + 0.04f * v, tiltY.value * l.pxf(2.2f), -tiltX.value * l.pxf(2.2f))
        highlight.alpha = 0.75f * v
        rim.alpha = 0.45f + 0.55f * v
        label?.alpha = v
        label?.translationY = l.tileH * (s - 1f) / 2f - (1f - v) * l.pxf(10f)
        return !(focus.atRest() && press.atRest() && tiltX.atRest() && tiltY.atRest())
    }

    private fun isSelect(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
}

/** Light rim shared by every tile, pre-rendered once: live stroked rounded rects are expensive on this GPU. */
private var rimCache: Pair<Int, Bitmap>? = null
private fun rimFor(l: Layout): Bitmap {
    rimCache?.let { if (it.first == l.tileW) return it.second }
    val b = Bitmap.createBitmap(l.tileW, l.tileH, Bitmap.Config.ARGB_8888)
    val sw = l.pxf(2f).coerceAtLeast(1f)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = sw
        shader = android.graphics.LinearGradient(0f, 0f, 0f, l.tileH.toFloat(), intArrayOf(0x8CFFFFFF.toInt(), 0x26FFFFFF, 0x33FFFFFF),
            null, android.graphics.Shader.TileMode.CLAMP)
    }
    Canvas(b).drawRoundRect(android.graphics.RectF(sw / 2, sw / 2, l.tileW - sw / 2, l.tileH - sw / 2), l.corner, l.corner, p)
    b.prepareToDraw()
    rimCache = l.tileW to b
    return b
}

/** Banner with rounded corners, drawn through a bitmap shader. Parallax moves the artwork inside its rounded frame. */
@SuppressLint("ViewConstructor")
private class BannerView(ctx: Context, private val bmp: Bitmap, private val corner: Float) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    }
    private val rect = android.graphics.RectF()
    private val m = android.graphics.Matrix()
    private var scale = 1f; private var dx = 0f; private var dy = 0f
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rect.set(0f, 0f, w.toFloat(), h.toFloat()); update() }
    fun parallax(s: Float, x: Float, y: Float) {
        if (s == scale && x == dx && y == dy) return
        scale = s; dx = x; dy = y; update(); invalidate()
    }
    private fun update() {
        if (width == 0) return
        val sx = width.toFloat() / bmp.width * scale; val sy = height.toFloat() / bmp.height * scale
        m.setScale(sx, sy); m.postTranslate((width - bmp.width * sx) / 2f + dx, (height - bmp.height * sy) / 2f + dy)
        paint.shader.setLocalMatrix(m)
    }
    override fun onDraw(canvas: Canvas) { canvas.drawRoundRect(rect, corner, corner, paint) }
}
