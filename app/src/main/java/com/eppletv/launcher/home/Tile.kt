package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
    private val highlight = View(ctx).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = l.pxf(320f)
            colors = intArrayOf(0x55FFFFFF, 0x00FFFFFF); setGradientCenter(0.3f, 0f); cornerRadius = l.corner
        }
        alpha = 0f
    }

    init {
        isFocusable = true
        clipChildren = false
        outlineProvider = null          // Z only orders drawing; the soft shadow is our own bitmap
        cameraDistance = 12000f * l.u
        contentDescription = entry.label
        val p = l.shadowPad
        addView(shadow, LayoutParams(l.tileW + 2 * p, l.tileH + 2 * p).apply { leftMargin = -p; topMargin = -p + l.px(18) })
        addView(BannerView(ctx, entry.banner, l.corner), LayoutParams(l.tileW, l.tileH))
        addView(highlight, LayoutParams(l.tileW, l.tileH))
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
        shadow.scaleX = 1f + 0.04f * v; shadow.scaleY = 1f + 0.04f * v
        rotationX = tiltX.value; rotationY = tiltY.value
        shadow.alpha = v
        highlight.alpha = 0.6f * v
        highlight.translationX = -tiltY.value * l.pxf(6f)
        highlight.translationY = tiltX.value * l.pxf(6f)
        label?.alpha = v
        label?.translationY = l.tileH * (s - 1f) / 2f - (1f - v) * l.pxf(10f)
        return !(focus.atRest() && press.atRest() && tiltX.atRest() && tiltY.atRest())
    }

    private fun isSelect(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
}

/** Banner drawn once with a bitmap shader and rounded corners; never redrawn during motion. */
@SuppressLint("ViewConstructor")
private class BannerView(ctx: Context, private val bmp: Bitmap, private val corner: Float) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val rect = RectF()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        // The artwork is rendered larger than the tile so it stays sharp when the focused tile scales up.
        paint.shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(w.toFloat() / bmp.width, h.toFloat() / bmp.height) })
    }
    override fun onDraw(canvas: Canvas) { canvas.drawRoundRect(rect, corner, corner, paint) }
}
