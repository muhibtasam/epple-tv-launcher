package com.eppletv.launcher.content

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Where each Continue Watching title really stands. Apps' own rows can be stale (Stremio keeps an old episode at 0 ms
 * long after newer viewing), so the last state the app's media session reported (position, duration, episode name;
 * saved by [Playback] when playback stops) wins over the row, unless the row has moved on to a later episode.
 * TMDB (by the IMDb id the app publishes) only fills what is missing and tells which episodes have aired; a failed
 * lookup never removes a title or marks it watched.
 *
 * The rules are pure functions so they are unit-tested; [save]/[saved] persist one small record per app and title.
 */
object Resume {
    /** Watched this far, a title counts as finished (end credits are rarely played out). */
    const val DONE = 0.92
    const val DAYS = 14
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val MAX_SAVED = 120

    class Saved(val position: Long, val duration: Long, val name: String, val at: Long)
    /** What TMDB says about a series: overview, the last aired episode (0, 0 when none) and each season's episode count. */
    class Series(val overview: String, val lastS: Int, val lastE: Int, val counts: Map<Int, Int>)
    class Shown(val s: Int?, val e: Int?, val name: String, val position: Long, val duration: Long)

    fun finished(position: Long, duration: Long) = duration > 0 && position >= duration * DONE
    /** 0..1, or -1 when there is nothing true to show (no duration, under a second, past the end). */
    fun progress(position: Long, duration: Long): Float =
        if (duration <= 0 || position < 1000 || position >= duration) -1f else position.toFloat() / duration
    private fun cmp(s1: Int, e1: Int, s2: Int, e2: Int) = if (s1 != s2) s1.compareTo(s2) else e1.compareTo(e2)

    /** The episode after S[s]E[e] if it has aired; null when nothing later is out yet. */
    fun next(s: Int, e: Int, series: Series): Pair<Int, Int>? {
        val count = series.counts[s]
        val n = if (count != null && e < count) s to e + 1 else s + 1 to 1
        return n.takeIf { cmp(it.first, it.second, series.lastS, series.lastE) <= 0 }
    }

    /**
     * What the row shows for one title, or null to leave it out (finished with nothing unwatched out yet, or listed
     * ahead of its air date). [savedEp] is the saved episode name resolved to a number (null when unknown).
     */
    fun decide(movie: Boolean, rowS: Int?, rowE: Int?, rowPos: Long, rowDur: Long, saved: Saved?, savedEp: Pair<Int, Int>?, series: Series?): Shown? {
        var s = rowS; var e = rowE; var name = ""; var pos = rowPos; var dur = rowDur
        val rowNewer = saved != null && savedEp != null && rowS != null && rowE != null && cmp(rowS, rowE, savedEp.first, savedEp.second) > 0
        if (saved != null && !rowNewer && (movie || savedEp != null || saved.name.isNotEmpty())) {
            val same = movie || (savedEp != null && savedEp.first == rowS && savedEp.second == rowE)
            if (!movie) { s = savedEp?.first; e = savedEp?.second; name = saved.name }
            pos = saved.position; dur = if (saved.duration > 0) saved.duration else if (same) rowDur else 0
        }
        if (finished(pos, dur)) {
            if (movie) return null
            val ss = s; val ee = e
            if (series == null || ss == null || ee == null) return Shown(s, e, name, 0, 0)   // can't tell what's next: kept, no bar
            val n = next(ss, ee, series) ?: return null
            return Shown(n.first, n.second, "", 0, 0)
        }
        // An app may list the next episode before it is out: hide it until it airs (only when TMDB answered).
        if (!movie && series != null && s != null && e != null && pos < 60_000 && cmp(s, e, series.lastS, series.lastE) > 0) return null
        return Shown(s, e, name, pos, dur)
    }

    /** "S1 E9"; empty for movies or when the numbers are unknown. */
    fun label(s: Int?, e: Int?) = if (e == null) "" else listOfNotNull(s?.let { "S$it" }, "E$e").joinToString(" ")

    /** The first non-blank value: the app's own first, then the fallbacks in order. */
    fun fill(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

    /** Start of the oldest day shown: today and the 13 days before it, in [tz]. */
    fun cutoff(now: Long, tz: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(tz).run {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -(DAYS - 1)); timeInMillis
    }
    fun inWindow(t: Long, now: Long, tz: TimeZone = TimeZone.getDefault()) = t >= cutoff(now, tz) && t <= now + DAY

    // Persistence: "position;duration;at;name" under "pkg|title".
    fun encode(v: Saved) = "${v.position};${v.duration};${v.at};${v.name}"
    fun decode(s: String?): Saved? = s?.split(';', limit = 4)?.takeIf { it.size == 4 }?.let { p ->
        val pos = p[0].toLongOrNull() ?: return null; val dur = p[1].toLongOrNull() ?: return null; val at = p[2].toLongOrNull() ?: return null
        Saved(pos, dur, p[3], at)
    }
    fun key(pkg: String, title: String) = pkg + "|" + title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun prefs(c: Context) = c.getSharedPreferences("epple_resume", Context.MODE_PRIVATE)
    fun saved(c: Context, pkg: String?, title: String): Saved? = if (pkg == null) null else decode(prefs(c).getString(key(pkg, title), null))
    /** Written asynchronously (apply); keeps the newest [MAX_SAVED]. */
    fun save(c: Context, pkg: String, title: String, v: Saved) {
        val p = prefs(c); val ed = p.edit().putString(key(pkg, title), encode(v))
        val all = p.all
        if (all.size > MAX_SAVED) all.entries.sortedBy { decode(it.value as? String)?.at ?: 0L }.take(all.size - MAX_SAVED).forEach { ed.remove(it.key) }
        ed.apply()
    }
}
