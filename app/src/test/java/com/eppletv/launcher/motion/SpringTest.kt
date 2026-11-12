package com.eppletv.launcher.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** JVM tests for the custom spring (motion candidate D). Run: ./gradlew testBaseDebugUnitTest */
class SpringTest {
    private fun run(hz: Float, seconds: Float, s: Spring = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS).apply { target = 1f }): Spring {
        val dt = 1f / hz; var t = 0f
        while (t < seconds) { s.step(dt); t += dt }
        return s
    }
    private fun settleTime(hz: Float): Float {
        val s = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS).apply { target = 1f }
        val dt = 1f / hz; var t = 0f
        while (!s.atRest() && t < 5f) { s.step(dt); t += dt }
        return t
    }

    @Test fun sameStateAt30_60_120Hz() {
        val a = run(30f, 0.1f).value; val b = run(60f, 0.1f).value; val c = run(120f, 0.1f).value
        assertEquals(b, a, 0.01f); assertEquals(b, c, 0.01f)
    }

    @Test fun settleTimeIndependentOfFrameRate() {
        val t60 = settleTime(60f); val t120 = settleTime(120f)
        assertTrue("60Hz $t60 vs 120Hz $t120", abs(t60 - t120) / t60 < 0.05f)
        assertTrue("settles under 1 s at 60 Hz, was $t60", t60 < 1f)
    }

    @Test fun overshootMatchesDampingRatio() {
        val s = Spring(0f, Motion.FOCUS_DAMPING, Motion.FOCUS_STIFFNESS).apply { target = 1f }
        var peak = 0f
        repeat(120) { s.step(1f / 60f); peak = maxOf(peak, s.value) }
        // Damping ratio 0.8 gives about 1.5 % overshoot analytically.
        assertTrue("peak $peak", peak in 1.005f..1.03f)
    }

    @Test fun retargetKeepsPositionAndVelocity() {
        val s = run(60f, 0.08f)
        val v0 = s.value; val vel0 = s.velocity
        assertTrue("moving before retarget", vel0 > 0.5f)
        s.target = 0f                       // reverse mid-motion
        assertEquals(v0, s.value, 0f); assertEquals(vel0, s.velocity, 0f)
        s.step(1f / 60f)
        assertTrue("keeps moving forward on the first frame after reversal", s.value > v0)
    }

    @Test fun restsExactlyAtTarget() {
        val s = run(60f, 3f)
        assertEquals(1f, s.value, 0f); assertEquals(0f, s.velocity, 0f)
    }
}
