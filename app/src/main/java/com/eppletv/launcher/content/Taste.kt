package com.eppletv.launcher.content

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The viewer's taste for recommendations, from reliable signals only (kept on this TV, in the "taste" preferences).
 *
 * A watch counts only for a title Epple TV itself opened ([opened]) whose app then reported 30 seconds of playing
 * ([played], from [Playback]); a title started inside an app is never credited. The app's media session, when it says
 * what is playing, rejects trailers and previews (under 5 minutes) and other titles. Favourites and the Watchlist are
 * read from [UserLibrary] at refresh time; the TV's Continue Watching is matched by the recommender. Every weight fades
 * with a 60-day half-life (a details visit within 14 days). Which apps get watched is kept apart from titles.
 */
object Taste {
    const val STRONG = 1.0; const val MEDIUM = 0.6; const val CONTINUE = 0.9; const val FAVOURITE = 1.0
    const val WATCHLIST = 0.5; const val DETAILS = 0.1; const val MORE_LIKE = 1.0
    const val DAY = 24 * 3600_000L
    const val HALF_LIFE = 60 * DAY
    const val DETAILS_LIFE = 14 * DAY
    const val DEDUP = 6 * 3600_000L
    const val DAMP = 30 * DAY
    private const val TRAILER_MS = 5 * 60_000L
    private const val LONG_MS = 15 * 60_000L
    private const val MAX_EVENTS = 400
    private const val TAG = "EppleTaste"

    /** One signal: [id] "movie:123" / "tv:123", [w] its base weight, [at] when, [kind] watch / details / more. */
    class Event(val id: String, val title: String, val w: Double, val at: Long, val kind: String)

    // ---- rules (pure, unit tested) ----

    fun norm(t: String) = t.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun same(a: String, b: String) = a == b || (b.length >= 4 && a.contains(b)) || (a.length >= 4 && b.contains(a))

    /**
     * How much a verified 30-second play counts. [pendingPkg] / [pendingTitle] / [pendingId]: what Epple TV opened last;
     * [sessionTitles]: what the app's session says is playing (title, subtitle, album, artist; may be empty);
     * [durationMs]: the session's length (0 = not reported). 0.0 = not credited.
     */
    fun confidence(pendingPkg: String?, pkg: String, pendingId: String?, pendingTitle: String?, sessionTitles: List<String>, durationMs: Long): Double {
        if (pendingPkg != pkg || pendingTitle.isNullOrBlank() || pendingId.isNullOrBlank()) return 0.0   // started inside the app
        if (durationMs in 1 until TRAILER_MS) return 0.0                                            // trailer, preview, autoplay
        val want = norm(pendingTitle)
        val names = sessionTitles.map(::norm).filter { it.isNotEmpty() }
        val matched = names.any { same(want, it) }
        if (names.isNotEmpty() && !matched) {
            // A series' session often names the episode, not the show: a long session of a series still counts, a little.
            return if (pendingId.startsWith("tv:") && durationMs >= LONG_MS) MEDIUM else 0.0
        }
        return if (matched || durationMs >= LONG_MS) STRONG else MEDIUM
    }

    /** Weight of [e] at [now]: details fade to nothing over 14 days, everything else halves every 60 days. */
    fun weight(e: Event, now: Long): Double {
        val age = (now - e.at).coerceAtLeast(0L)
        return if (e.kind == "details") e.w * (1.0 - age.toDouble() / DETAILS_LIFE).coerceAtLeast(0.0)
        else e.w * Math.pow(0.5, age.toDouble() / HALF_LIFE)
    }

    /** [events] with [e] added: the same title within 6 hours counts once (the stronger of the two). Newest first. */
    fun add(events: List<Event>, e: Event): List<Event> {
        val dup = events.firstOrNull { it.id == e.id && Math.abs(it.at - e.at) < DEDUP }
        val kept = if (dup == null) events else if (dup.w >= e.w) return events else events.filter { it !== dup }
        return (listOf(e) + kept).sortedByDescending { it.at }.take(MAX_EVENTS)
    }

    // ---- what is stored ----

    /** Everything the recommender reads, as one consistent copy. */
    class Data(
        val events: List<Event> = emptyList(),
        val dismissed: Map<String, Long> = emptyMap(),                 // "Not for me": id -> when
        val shown: Map<String, List<Long>> = emptyMap(),               // Top Picks showings without an open, last 30 days
        val opened: Map<String, Long> = emptyMap(),                    // last open of a title from Epple TV
        val services: Map<String, Int> = emptyMap(),                   // app -> verified plays (any title)
    )

    private var data: Data? = null
    private var pending: Array<String?> = arrayOfNulls(3)              // id, title, app of the last open
    private var credited = false
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("taste", Context.MODE_PRIVATE)

    @Synchronized fun snapshot(c: Context): Data = load(c)

    @Synchronized private fun load(c: Context): Data = data ?: parse(prefs(c).getString("data", null)).also {
        data = it
        val p = prefs(c); pending = arrayOf(p.getString("pid", null), p.getString("ptitle", null), p.getString("ppkg", null)); credited = p.getBoolean("pdone", false)
    }

    /** A stored profile that can't be read starts empty (never fatal). */
    fun parse(s: String?): Data {
        if (s.isNullOrEmpty()) return Data()
        return try {
            val o = JSONObject(s)
            val ev = o.optJSONArray("events") ?: JSONArray()
            Data(
                (0 until ev.length()).map { ev.getJSONObject(it) }.map { Event(it.getString("id"), it.optString("t"), it.getDouble("w"), it.getLong("at"), it.optString("k")) },
                longs(o.optJSONObject("dismissed")),
                o.optJSONObject("shown")?.let { m -> m.keys().asSequence().associateWith { k -> m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getLong(it) } } } } ?: emptyMap(),
                longs(o.optJSONObject("opened")),
                o.optJSONObject("services")?.let { m -> m.keys().asSequence().associateWith { m.getInt(it) } } ?: emptyMap(),
            )
        } catch (e: Exception) { Data() }
    }
    private fun longs(m: JSONObject?): Map<String, Long> = m?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } ?: emptyMap()

    @Synchronized private fun save(c: Context, d: Data) {
        data = d
        val ev = JSONArray(); d.events.forEach { ev.put(JSONObject().put("id", it.id).put("t", it.title).put("w", it.w).put("at", it.at).put("k", it.kind)) }
        val o = JSONObject().put("v", 1).put("events", ev).put("dismissed", JSONObject(d.dismissed)).put("opened", JSONObject(d.opened))
            .put("shown", JSONObject().also { m -> d.shown.forEach { (k, v) -> m.put(k, JSONArray(v)) } })
            .put("services", JSONObject(d.services))
        prefs(c).edit().putString("data", o.toString()).apply()          // written in the background by SharedPreferences
    }

    private fun tmdb(id: String) = Regex("^(movie|tv):\\d+$").matches(id)

    // ---- signals ----

    /** Epple TV opened [item] in [pkg] (Play): it may become a watch; it was opened, so it is no longer a skipped pick. */
    @Synchronized fun opened(c: Context, item: ContentItem, pkg: String) {
        val d = load(c)
        val p = prefs(c).edit()
        if (tmdb(item.id)) { pending = arrayOf(item.id, item.title, pkg); p.putString("pid", item.id).putString("ptitle", item.title) }
        else { pending = arrayOf(null, null, pkg); p.remove("pid").remove("ptitle") }
        credited = false; p.putString("ppkg", pkg).putBoolean("pdone", false).apply()
        if (tmdb(item.id)) save(c, open(d, item.id))
    }

    /** An app opened by itself (not a title): what plays next in it was chosen there, so no title is credited. */
    @Synchronized fun appOpened(c: Context, pkg: String?) {
        if (pkg.isNullOrEmpty()) return
        load(c); pending = arrayOf(null, null, pkg); credited = false
        prefs(c).edit().remove("pid").remove("ptitle").putString("ppkg", pkg).putBoolean("pdone", false).apply()
    }

    private fun open(d: Data, id: String) = Data(d.events, d.dismissed, d.shown - id, d.opened + (id to System.currentTimeMillis()), d.services)

    /** [pkg] played 30 seconds (Playback). [sessionTitles] / [durationMs]: what its media session reports, if anything. */
    @Synchronized fun played(c: Context, pkg: String, sessionTitles: List<String>, durationMs: Long) {
        var d = load(c)
        d = Data(d.events, d.dismissed, d.shown, d.opened, d.services + (pkg to (d.services[pkg] ?: 0) + 1))
        val (id, title, ppkg) = pending
        if (!credited) {
            val w = confidence(ppkg, pkg, id, title, sessionTitles, durationMs)
            Log.i(TAG, "play in $pkg: ${if (w > 0) "credited $w to $id" else "not credited"}")
            if (w > 0 && id != null) {
                credited = true; prefs(c).edit().putBoolean("pdone", true).apply()
                d = Data(add(d.events, Event(id, title ?: "", w, System.currentTimeMillis(), "watch")), d.dismissed, d.shown, d.opened, d.services)
            }
        }
        save(c, d)
    }

    /** A details page opened: a weak, short-lived signal, and the title was opened. */
    @Synchronized fun details(c: Context, item: ContentItem) {
        if (!tmdb(item.id)) return
        val d = open(load(c), item.id)
        save(c, Data(add(d.events, Event(item.id, item.title, DETAILS, System.currentTimeMillis(), "details")), d.dismissed, d.shown, d.opened, d.services))
    }

    /** "More like this": a strong seed for the next refresh. */
    @Synchronized fun moreLike(c: Context, item: ContentItem) {
        if (!tmdb(item.id)) return
        val d = load(c)
        save(c, Data(add(d.events, Event(item.id, item.title, MORE_LIKE, System.currentTimeMillis(), "more")), d.dismissed, d.shown, d.opened, d.services))
    }

    /** "Not for me": hidden for good; what led to it weighs less for 30 days. */
    @Synchronized fun dismiss(c: Context, id: String) {
        val d = load(c)
        save(c, Data(d.events, d.dismissed + (id to System.currentTimeMillis()), d.shown - id, d.opened, d.services))
    }

    /** [ids] were dealt into Top Picks now. Showings older than 30 days are dropped. */
    @Synchronized fun shown(c: Context, ids: List<String>) {
        val d = load(c); val now = System.currentTimeMillis()
        val m = HashMap(d.shown.mapValues { (_, v) -> v.filter { now - it < DAMP } }.filterValues { it.isNotEmpty() })
        ids.forEach { m[it] = (m[it] ?: emptyList()) + now }
        save(c, Data(d.events, d.dismissed, m, d.opened, d.services))
    }

    /** Reset recommendations: taste, feedback and dismissals go; Favourites, Watchlist and Continue Watching stay. */
    @Synchronized fun reset(c: Context) {
        data = Data(); pending = arrayOfNulls(3); credited = false
        prefs(c).edit().clear().apply()
    }
}
