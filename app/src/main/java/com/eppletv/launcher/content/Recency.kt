package com.eppletv.launcher.content

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * What the user watched last, as seen from Epple TV (kept on this TV only, so it survives restarts).
 *
 * A title is known by its app and its name ([key]), not by an id: the same show has a different id in Watch Next, in
 * the app's own rows and in Epple's lists, and only the name and app match across them. Opening a title records it
 * as pending; coming back to Epple TV afterwards stamps that title (and its app) with the time watching ended. Being
 * resumed for any other reason (Settings, a dialog, the screensaver) changes nothing.
 */
object Recency {
    private const val TAG = "EppleContinue"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("recency", Context.MODE_PRIVATE)

    fun norm(t: String) = t.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun key(pkg: String?, title: String) = (pkg ?: "") + "|" + norm(title)

    /** Epple TV opened [pkg], for the title [title] when there is one. */
    fun opened(c: Context, pkg: String?, title: String?) {
        if (pkg.isNullOrEmpty() || pkg == c.packageName) return
        val now = System.currentTimeMillis()
        val p = prefs(c)
        val e = p.edit().putLong("a:$pkg", now).putString("pending", pkg)
        // Opening the app itself right after one of its titles (the title had no link of its own) keeps that title.
        if (title.isNullOrBlank() && p.getString("pending", null) != pkg) e.remove("pendingKey")
        // Opening a title again from Epple TV is choosing it: a title removed from Continue Watching comes back.
        if (!title.isNullOrBlank()) e.putLong("t:" + key(pkg, title), now).putString("pendingKey", key(pkg, title)).remove("x:" + key(pkg, title))
        e.apply()
        Log.i(TAG, "opened ${title ?: "(app)"} in $pkg at $now")
    }

    /** Back in Epple TV after an open: stamps what was watched with now. Returns its key (or the app), or null. */
    fun returned(c: Context): String? {
        val p = prefs(c); val pkg = p.getString("pending", null) ?: return null
        val k = p.getString("pendingKey", null); val now = System.currentTimeMillis()
        val e = p.edit().putLong("a:$pkg", now).remove("pending").remove("pendingKey")
        if (k != null) e.putLong("t:$k", now)
        e.apply()
        Log.i(TAG, "returned from ${k ?: pkg} at $now")
        return k ?: pkg
    }

    /**
     * An app's own Continue Watching row (no times, newest first, e.g. Stremio) has a new title at its front: the user
     * just watched it, wherever they started it. The first time an app's row is seen nothing is claimed.
     */
    fun front(c: Context, pkg: String, title: String) {
        val p = prefs(c); val was = p.getString("front:$pkg", null); val now = norm(title)
        if (was == now) return
        val e = p.edit().putString("front:$pkg", now)
        if (was != null) { val t = System.currentTimeMillis(); e.putLong("t:" + key(pkg, title), t); Log.i(TAG, "new at the front of $pkg: $title at $t") }
        e.apply()
    }

    /**
     * Removed from Continue Watching by the user: hidden whatever reports it again (Watch Next, the app's own row, an
     * old list), until the user opens that title from Epple TV again ([opened] clears it).
     */
    fun remove(c: Context, pkg: String?, title: String) {
        prefs(c).edit().putLong("x:" + key(pkg, title), System.currentTimeMillis()).apply()
        Log.i(TAG, "removed ${key(pkg, title)}")
    }
    fun removed(c: Context, pkg: String?, title: String) = prefs(c).contains("x:" + key(pkg, title))

    fun appTime(c: Context, pkg: String?): Long = if (pkg == null) 0 else prefs(c).getLong("a:$pkg", 0)
    fun titleTime(c: Context, pkg: String?, title: String): Long = prefs(c).getLong("t:" + key(pkg, title), 0)
}
