package com.eppletv.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring

/** Typography: Inter (SIL OFL, bundled) at exact weights; falls back to the system sans if the asset is missing. */
object Type {
    private var assets: AssetManager? = null
    private val cache = HashMap<Int, Typeface>()
    fun init(ctx: Context) { assets = ctx.applicationContext.assets }
    fun of(weight: Int): Typeface = cache.getOrPut(weight) {
        try {
            Typeface.Builder(assets!!, "fonts/Inter.ttf").setFontVariationSettings("'wght' $weight, 'opsz' 28").build()
        } catch (e: RuntimeException) {
            Typeface.create(if (weight >= 500) "sans-serif-medium" else if (weight <= 300) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
        }
    }
}

object Colors {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF111114.toInt()            // text on a white focused surface
    const val TEXT_DIM = 0xB8FFFFFF.toInt()       // 72 % white
    const val GLASS = 0x8C1E1E22.toInt()          // translucent dark bar / pill
    const val GLASS_SOLID = 0xFA1C1C20.toInt()    // panels
    const val FILL = 0x1FFFFFFF                   // resting row on a panel
    const val FILL_SELECTED = 0x2EFFFFFF
    const val HAIRLINE = 0x24FFFFFF

    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = if (t < 0f) 0f else if (t > 1f) 1f else t
        val aa = a ushr 24; val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val ba = b ushr 24; val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        return ((aa + (ba - aa) * u).toInt() shl 24) or ((ar + (br - ar) * u).toInt() shl 16) or
            ((ag + (bg - ag) * u).toInt() shl 8) or (ab + (bb - ab) * u).toInt()
    }
}

/** Type scale for a 10-foot UI, in px on the 1080p canvas. Every text in the app uses one of these. */
enum class Txt(val px: Float, val weight: Int, val tracking: Float, val lines: Int = 1) {
    HERO(72f, 700, -0.022f, 2),     // content title over artwork
    CLOCK(72f, 300, -0.02f),        // Control Center time
    TITLE(36f, 600, -0.012f),       // panel titles
    ROW(30f, 500, -0.005f),         // menu rows
    META(27f, 500, 0f),             // metadata line, dates
    LABEL(26f, 500, 0f),            // app name under a focused tile
    CAPTION(22f, 700, 0.10f),       // upper-case eyebrow
    BODY(24f, 400, 0f, 2),          // short description under the hero title
    FOOT(19f, 400, 0f, 3),          // attribution
}

fun TextView.style(t: Txt, u: Float, color: Int = Colors.WHITE) {
    textStyle(t.px * u, t.weight, color); letterSpacing = t.tracking
    if (t.lines > 1) { isSingleLine = false; maxLines = t.lines; setLineSpacing(0f, 1.06f) }
    if (t == Txt.CAPTION) isAllCaps = true
}

fun TextView.textStyle(px: Float, weight: Int, color: Int = Colors.WHITE) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, px); typeface = Type.of(weight); setTextColor(color)
    includeFontPadding = false; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
}

/**
 * The one focusable surface used by tabs, the status pill and menu rows:
 * rests translucent, turns solid white with dark text when focused (spring-driven), optional "selected" state.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
class Pill(
    ctx: Context, label: String, textPx: Float, radius: Float, padH: Int,
    private val clock: FrameClock, private val restBg: Int, private val restText: Int,
    private val focusScale: Float, startAligned: Boolean = false,
) : TextView(ctx), FrameClock.Tick {
    private val f = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS)
    private val s = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 3f)
    private val bg = GradientDrawable().apply { cornerRadius = radius; setColor(restBg) }

    var selectedState = false
        set(v) { if (field != v) { field = v; s.target = if (v) 1f else 0f; clock.add(this) } }

    init {
        text = label; textStyle(textPx, 500, restText)
        gravity = if (startAligned) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
        setPadding(padH, 0, padH, 0); background = bg
        isFocusable = true; isClickable = true
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        f.target = if (gain) 1f else 0f; clock.add(this)
    }

    override fun tick(dt: Float): Boolean {
        f.step(dt); s.step(dt)
        val sc = 1f + focusScale * f.value
        scaleX = sc; scaleY = sc
        bg.setColor(Colors.lerp(Colors.lerp(restBg, Colors.FILL_SELECTED, s.value), Colors.WHITE, f.value))
        setTextColor(Colors.lerp(Colors.lerp(restText, Colors.WHITE, s.value), Colors.INK, f.value))
        return !(f.atRest() && s.atRest())
    }
}
