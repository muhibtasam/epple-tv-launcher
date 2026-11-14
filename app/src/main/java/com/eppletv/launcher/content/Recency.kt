package com.eppletv.launcher.content

import android.content.Context

/**
 * When the user last used each app, and when each title was last opened, as seen from Epple TV (kept on this TV only).
 * Most apps publish Continue Watching rows without a time, so this is what puts the title you just watched first.
 */
object Recency {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("recency", Context.MODE_PRIVATE)

    /** An app was opened from Epple TV (or the user just came back from it). */
    fun app(c: Context, pkg: String?) {
        if (pkg.isNullOrEmpty() || pkg == c.packageName) return
        prefs(c).edit().putLong("a:$pkg", System.currentTimeMillis()).putString("last", pkg).apply()
    }

    /** A title was opened. */
    fun item(c: Context, id: String) { prefs(c).edit().putLong("i:$id", System.currentTimeMillis()).apply() }

    fun appTime(c: Context, pkg: String?): Long = if (pkg == null) 0 else prefs(c).getLong("a:$pkg", 0)
    fun itemTime(c: Context, id: String): Long = prefs(c).getLong("i:$id", 0)

    /** Back on Home: the app the user was in is the one they used last. */
    fun returned(c: Context) { prefs(c).getString("last", null)?.let { app(c, it) } }
}
