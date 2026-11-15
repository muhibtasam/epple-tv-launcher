package com.eppletv.launcher.content

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * What the user watched last, as seen from Epple TV (kept on this TV only, so it survives restarts).
 *
 * A title is known by its app and its name ([key]), not by an id: the same show has a different id in Watch Next, in
 * the app's own rows and in Epple's lists, and only the name and app match across them.
 *
 * Opening a title only marks it as pending. It is stamped (and so becomes eligible for Continue Watching) once its app
 * has reported at least 30 seconds of playing ([Playback]); coming back to Epple TV afterwards moves the stamp to the
 * time watching ended. Opening an app, or returning before 30 seconds of playback, stamps nothing.
 * Stamps are kept under "w:" (titles) and "v:" (apps); the older "t:" / "a:" stamps, written on every open, are ignored.
 */
object Recency {
    private const val TAG = "EppleContinue"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("recency", Context.MODE_PRIVATE)

    fun norm(t: String) = t.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun key(pkg: String?, title: String) = (pkg ?: "") + "|" + norm(title)

    /** Epple TV opened [pkg], for the title [title] when there is one. Nothing is stamped yet. */
    fun opened(c: Context, pkg: String?, title: String?) {
        if (pkg.isNullOrEmpty() || pkg == c.packageName) return
        val p = prefs(c)
        val e = p.edit().putString("pending", pkg).putBoolean("pendingOk", false)
        // Opening the app itself right after one of its titles (the title had no link of its own) keeps that title.
        if (title.isNullOrBlank() && p.getString("pending", null) != pkg) e.remove("pendingKey")
        if (!title.isNullOrBlank()) e.putString("pendingKey", key(pkg, title))
        e.apply()
        Playback.mark(pkg)
        Log.i(TAG, "opened ${title ?: "(app)"} in $pkg: counting playback")
    }

    /** [pkg] has played at least 30 seconds: the title opened in it (if it is the pending one) becomes eligible now. */
    fun verified(c: Context, pkg: String, ms: Long) {
        val p = prefs(c); val now = System.currentTimeMillis()
        val mine = p.getString("pending", null) == pkg
        val k = if (mine) p.getString("pendingKey", null) else null
        if (mine && p.getBoolean("pendingOk", false)) return               // already stamped for this open
        val e = p.edit().putLong("v:$pkg", now).putLong("vs:$pkg", now - ms)
        if (mine) e.putBoolean("pendingOk", true)
        // Watching a removed title again is choosing it: it comes back.
        if (k != null) e.putLong("w:$k", now).remove("x:$k")
        e.apply()
        Log.i(TAG, "verified ${ms / 1000} s playing in $pkg${if (k != null) " for $k" else ""}: eligible at $now")
    }

    /** Back in Epple TV after an open. Returns the key (or app) whose stamp changed, or null when nothing became eligible. */
    fun returned(c: Context): String? {
        val p = prefs(c); val pkg = p.getString("pending", null) ?: return null
        val k = p.getString("pendingKey", null); val now = System.currentTimeMillis()
        val ms = Playback.played(pkg)
        val ok = p.getBoolean("pendingOk", false) || ms >= Playback.ELIGIBLE_MS
        val e = p.edit().remove("pending").remove("pendingKey").remove("pendingOk")
        if (ok) { e.putLong("v:$pkg", now); if (!p.contains("vs:$pkg")) e.putLong("vs:$pkg", now - ms); if (k != null) e.putLong("w:$k", now).remove("x:$k") }
        e.apply()
        val what = k ?: pkg
        if (ok) Log.i(TAG, "returned from $what after ${ms / 1000} s verified playback: stamped $now")
        else Log.i(TAG, "returned from $what after ${ms / 1000} s verified playback${if (Playback.active) "" else " (media sessions not readable)"}: under 30 s, not added")
        return if (ok) what else null
    }

    /**
     * An app's own Continue Watching row (no times, newest first, e.g. Stremio) has a new title at its front: the app
     * itself recorded watching it (provider-reported, not timed by Epple TV). The first time a row is seen nothing is claimed.
     */
    fun front(c: Context, pkg: String, title: String) {
        val p = prefs(c); val was = p.getString("front:$pkg", null); val now = norm(title)
        if (was == now) return
        val e = p.edit().putString("front:$pkg", now)
        if (was != null) { val t = System.currentTimeMillis(); e.putLong("w:" + key(pkg, title), t); Log.i(TAG, "new at the front of $pkg (app-reported): $title at $t") }
        e.apply()
    }

    /**
     * Removed from Continue Watching by the user: hidden whatever reports it again (Watch Next, the app's own row, an
     * old list), until the user watches that title again for 30 seconds from Epple TV ([verified] clears it).
     */
    fun remove(c: Context, pkg: String?, title: String) {
        prefs(c).edit().putLong("x:" + key(pkg, title), System.currentTimeMillis()).apply()
        Log.i(TAG, "removed ${key(pkg, title)}")
    }
    fun removed(c: Context, pkg: String?, title: String) = prefs(c).contains("x:" + key(pkg, title))

    private val sessions = HashSet<String>()
    /** [pkg] has reported PLAYING through a media session at least once: its viewing can be verified. */
    fun sessionSeen(c: Context, pkg: String) {
        if (!sessions.add(pkg)) return
        val p = prefs(c)
        if (!p.getBoolean("s:$pkg", false)) p.edit().putBoolean("s:$pkg", true).apply()
        if (!p.contains("trackSince")) p.edit().putLong("trackSince", System.currentTimeMillis()).apply()
    }

    /**
     * The time to order a Watch Next title by. Apps rewrite these times themselves, sometimes just for being opened
     * (Crunchyroll stamps its other titles when one starts). For an app whose playback Epple TV can verify, a newer time
     * counts only if it falls inside a stretch of at least 30 seconds of verified playing in that app; otherwise the
     * title keeps the last time that counted. A title that first appears that way, never verified, is left out (-1).
     * Apps without a media session can't be checked: their own times are used as they are (provider-reported).
     */
    fun acceptedTime(c: Context, pkg: String?, title: String, engaged: Long): Long {
        if (pkg == null || engaged <= 0) return engaged
        val p = prefs(c); val k = "e:" + key(pkg, title)
        val prev = p.getLong(k, 0)
        if (engaged <= prev) return prev
        val since = p.getLong("trackSince", 0)
        val verifiable = p.getBoolean("s:$pkg", false) && since > 0
        val end = p.getLong("v:$pkg", 0); val start = p.getLong("vs:$pkg", Long.MAX_VALUE)
        val ok = !verifiable || engaged < since || (end > 0 && engaged >= start - SLACK && engaged <= end + SLACK)
        if (ok) { p.edit().putLong(k, engaged).apply(); return engaged }
        if (ignored.add("$k@$engaged")) Log.i(TAG, "ignored Watch Next time for ${key(pkg, title)} at $engaged: no verified playback then (kept ${if (prev > 0) prev else "none"})")
        return if (prev > 0) prev else -1L
    }
    private val ignored = HashSet<String>()
    private const val SLACK = 120_000L

    fun appTime(c: Context, pkg: String?): Long = if (pkg == null) 0 else prefs(c).getLong("v:$pkg", 0)
    fun titleTime(c: Context, pkg: String?, title: String): Long = prefs(c).getLong("w:" + key(pkg, title), 0)
}
