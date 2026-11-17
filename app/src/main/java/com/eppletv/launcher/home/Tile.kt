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
    // Dock magnification: extra scale as a neighbour of the focused tile, and a sideways shift. Set by the dock.
    private val near = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val shift = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS, Motion.REST_PX)
    /** Dock tiles: told when this tile gains or loses focus, so the row can magnify around it. */
    var onDockFocus: ((Tile, Boolean) -> Unit)? = null
    /** Dock magnification targets: [grow] on top of the normal size, [dx] sideways (px). Springs carry it there. */
    fun magnify(grow: Float, dx: Float) {
        if (near.target == grow && shift.target == dx) return
        near.target = grow; shift.target = dx; clock.add(this)
    }
    private var longPressed = false

    private val shadow = ImageView(ctx).apply { setImageBitmap(shadowBmp); alpha = 0f; scaleType = ImageView.ScaleType.FIT_XY }

    // Layered artwork: banner (parallax layer, drawn with its own rounded corners; no view clipping, which is costly on
    // this GPU), static gloss, moving specular light, light rim.
    private val banner = BannerView(ctx, entry.banner, l.corner)
    private val highlight = View(ctx).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = l.pxf(300f)
            colors = intArrayOf(0x66FFFFFF, 0x14FFFFFF, 0x00FFFFFF); setGradientCenter(0.25f, -0.1f); cornerRadius = l.corner
        }
        alpha = 0f
    }
    // Gloss and rim in one pre-rendered bitmap shared by every tile: one cheap layer instead of a gradient pass plus a bitmap.
    // The resting surface (gloss + rim at 45%) is baked into each tile's art; focus adds the rest of the rim live.
    private val rim = ImageView(ctx).apply { setImageBitmap(surfaceFor(l)); scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f; visibility = View.INVISIBLE }
    // Focus ring around the dark tile (pre-rendered, shared): the one light edge in the row, readable from the sofa.
    private val ring = ImageView(ctx).apply { setImageBitmap(focusRing(l, l.tileW, l.tileH)); scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f; visibility = View.INVISIBLE }

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
        addView(highlight, LayoutParams(l.tileW, l.tileH))
        addView(rim, LayoutParams(l.tileW, l.tileH))
        val rp = ringPad(l)
        addView(ring, LayoutParams(l.tileW + 2 * rp, l.tileH + 2 * rp).apply { leftMargin = -rp; topMargin = -rp })
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
        onDockFocus?.invoke(this, gainFocus)
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
        focus.step(dt); press.step(dt); tiltX.step(dt); tiltY.step(dt); near.step(dt); shift.step(dt)
        val v = focus.value
        val s = (1f + (if (inDock) Motion.DOCK_SCALE else Motion.FOCUS_SCALE) * v + near.value) * (1f - Motion.PRESS_SCALE * press.value)
        scaleX = s; scaleY = s
        translationX = shift.value
        translationZ = v * l.pxf(10f)
        rotationX = tiltX.value; rotationY = tiltY.value
        // Depth: the shadow grows, softens and drops as the tile lifts.
        shadow.alpha = v
        shadow.visibility = if (v > 0.002f) View.VISIBLE else View.INVISIBLE   // unfocused tiles draw no shadow
        val ss = 1f + 0.06f * v; shadow.scaleX = ss; shadow.scaleY = ss; shadow.translationY = v * l.pxf(14f)
        // Parallax: the artwork layer sits slightly behind the glass and shifts against the tilt.
        banner.parallax(1f + 0.04f * v, tiltY.value * l.pxf(2.2f), -tiltX.value * l.pxf(2.2f))
        highlight.alpha = 0.75f * v
        rim.alpha = 0.55f * v
        rim.visibility = if (v > 0.002f) View.VISIBLE else View.INVISIBLE   // unfocused tiles: baked surface only
        ring.alpha = v * v; ring.visibility = rim.visibility                // fades in with focus, a little behind the lift
        label?.alpha = v
        label?.translationY = l.tileH * (s - 1f) / 2f - (1f - v) * l.pxf(10f)
        return !(focus.atRest() && press.atRest() && tiltX.atRest() && tiltY.atRest() && near.atRest() && shift.atRest())
    }

    /** The app's art was prepared after this tile was built: show it. */
    fun updateArt() = banner.set(entry.banner)

    private fun isSelect(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
}

/**
 * Tile surface shared by every tile, pre-rendered once: top light and a faint darker base (the tile reads as a lit,
 * slightly convex surface) plus the light rim. Live gradients and stroked rounded rects are expensive on this GPU.
 */
private var rimCache: Pair<Int, Bitmap>? = null
private fun surfaceFor(l: Layout): Bitmap {
    rimCache?.let { if (it.first == l.tileW) return it.second }
    val b = Bitmap.createBitmap(l.tileW, l.tileH, Bitmap.Config.ARGB_8888)
    paintRim(Canvas(b), l.tileW, l.tileH, l.corner, l.pxf(2f).coerceAtLeast(1f), 255)
    b.prepareToDraw()
    rimCache = l.tileW to b
    return b
}

/** Space between a tile or app card and its focus ring, plus the ring's stroke. */
internal fun ringPad(l: Layout) = l.px(4) + l.px(3)

private val ringCache = HashMap<Long, Bitmap>()
/**
 * The focus ring around a dark app tile or app card of [w] x [h]: pre-rendered once per size and shared, drawn only
 * on the focused item and faded with its focus spring (no live stroke, no extra frames).
 */
internal fun focusRing(l: Layout, w: Int, h: Int): Bitmap = ringCache.getOrPut(w.toLong() shl 32 or h.toLong()) {
    val pad = ringPad(l); val sw = l.pxf(3f).coerceAtLeast(2f)
    val b = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = 0xD9E9E9EC.toInt() }
    val r = l.corner + pad - sw / 2
    Canvas(b).drawRoundRect(android.graphics.RectF(sw / 2, sw / 2, b.width - sw / 2, b.height - sw / 2), r, r, p)
    b.prepareToDraw()
    b
}

/** Light rim: brighter along the top edge, where the light falls. */
internal fun paintRim(c: Canvas, w: Int, h: Int, corner: Float, sw: Float, alpha: Int) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = sw; this.alpha = alpha
        shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x8CFFFFFF.toInt(), 0x26FFFFFF, 0x33FFFFFF),
            null, android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawRoundRect(android.graphics.RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2), corner, corner, p)
}

/**
 * The resting tile surface, baked into each app's art once: top light and a faint darker base (a lit, slightly convex
 * surface) plus the rim at 45%. As live layers these cost ~4.5 ms a frame across a full grid on this GPU.
 */
fun paintTileSurface(c: Canvas, w: Int, h: Int, corner: Float, stroke: Float) {
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x05FFFFFF, 0x00000000, 0x14000000))
        .apply { cornerRadius = corner; setBounds(0, 0, w, h); draw(c) }
    paintRim(c, w, h, corner, stroke, 115)
}

/** Banner with rounded corners, drawn through a bitmap shader. Parallax moves the artwork inside its rounded frame. */
@SuppressLint("ViewConstructor")
private class BannerView(ctx: Context, private var bmp: Bitmap, private val corner: Float) : View(ctx) {
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
    /** New artwork (the app's prepared art replacing its placeholder). */
    fun set(b: Bitmap) {
        if (b === bmp) return
        bmp = b; paint.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        update(); invalidate()
    }
}
