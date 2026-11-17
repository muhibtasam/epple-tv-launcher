package com.eppletv.launcher.motion

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Motion tokens. Spring values are placeholders, to be tuned by eye on the TV. */
object Motion {
    const val FOCUS_DAMPING = 0.80f; const val FOCUS_STIFFNESS = 380f
    const val SCROLL_DAMPING = 1.0f; const val SCROLL_STIFFNESS = 260f
    const val FADE_DAMPING = 1.0f; const val FADE_STIFFNESS = 120f
    const val PRESS_DAMPING = 1.0f; const val PRESS_STIFFNESS = 900f

    const val FOCUS_SCALE = 0.16f    // focused tile grows by 16 %
    const val PRESS_SCALE = 0.06f    // pressed tile shrinks by 6 %
    // Dock magnification (Home's top row), after the macOS Dock: a virtual pointer follows focus and every tile's size
    // and position come from its distance to that pointer (cosine falloff), so the bulge travels across the row.
    const val DOCK_PEAK = 0.24f      // tile under the pointer: 1.24x
    const val DOCK_RADIUS = 2.8f     // falloff reaches zero this many tiles from the pointer
    const val DOCK_SPREAD = 0.6f     // how much of the growth pushes neighbours aside (the rest closes the gaps)
    const val DOCK_SQUEEZE = 0.07f   // tiles beyond the lens draw in slightly, so both ends stay on the glass
    const val DOCK_DAMPING = 0.92f; const val DOCK_STIFFNESS = 240f   // the pointer gliding to the next tile
    const val TILT_DEG = 5f          // tilt kick in the direction of travel, springs back to 0

    // Rest thresholds: a spring stops (and stops requesting frames) below these.
    const val REST_PROGRESS = 0.002f
    const val REST_PX = 0.5f
    const val REST_DEG = 0.05f
}

/**
 * Damped spring, mass 1. Time-based (same motion at any frame rate) and retargetable: changing [target]
 * mid-flight keeps position and velocity, so interrupted motion never jumps or stalls. No allocation.
 */
class Spring(
    var value: Float,
    private val damping: Float,
    private val stiffness: Float,
    private val rest: Float = Motion.REST_PROGRESS,
) {
    var velocity = 0f
    var target = value

    fun atRest(): Boolean = abs(value - target) < rest && abs(velocity) < rest * 10f

    fun snapTo(v: Float) { value = v; target = v; velocity = 0f }

    fun step(dt: Float) {
        val c = 2f * damping * sqrt(stiffness)
        var t = dt
        while (t > 0f) {                       // fixed 4 ms sub-steps keep the integration stable
            val h = min(t, 0.004f)
            velocity += (-stiffness * (value - target) - c * velocity) * h
            value += velocity * h
            t -= h
        }
        if (atRest()) { value = target; velocity = 0f }
    }
}

/**
 * One Choreographer callback drives every active animation. It unregisters itself as soon as nothing
 * moves, so an idle home screen requests no frames at all.
 */
class FrameClock : Choreographer.FrameCallback {
    interface Tick { fun tick(dt: Float): Boolean }   // return true while still animating

    private val items = ArrayList<Tick>(64)
    private var last = 0L
    private var posted = false

    fun add(t: Tick) {
        if (!items.contains(t)) items.add(t)
        if (!posted) { posted = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }

    fun remove(t: Tick) { items.remove(t) }

    override fun doFrame(now: Long) {
        val dt = if (last == 0L) 1f / 60f else min((now - last) / 1e9f, 0.05f)
        last = now
        var i = items.size - 1
        while (i >= 0) {
            if (i < items.size && !items[i].tick(dt)) items.removeAt(i)
            i--
        }
        if (items.isEmpty()) posted = false else Choreographer.getInstance().postFrameCallback(this)
    }
}
