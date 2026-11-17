package com.eppletv.launcher.home

/** One-time loading marks in logcat (tag EppleStart), in ms since the process started: for on-device measurements. */
object Marks {
    private val done = HashSet<String>()
    /** Main thread. */
    fun once(what: String) {
        if (done.add(what)) android.util.Log.i("EppleStart", "$what +${android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()} ms")
    }
}
