package com.eppletv.launcher.content

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recommendations without AI (Release 1): real TMDB titles found from what you watch and keep, scored with fixed
 * weights, picked one at a time for variety, each with a one-line reason. The result is a deck kept on the TV
 * (recs/deck.json, replaced atomically and only by a valid new deck), refreshed at most daily in the background.
 */
object Recommender {
    private const val TAG = "EppleRecs"
    const val DAY = 86_400_000L
    private const val HOUR = 3_600_000L
    private const val LEASE = 30 * 60_000L
    const val MIN_DECK = 8
    /** Deck format: 2 adds verified availability ("avail", "fresh"). */
    private const val DECK_V = 2
    /** A title with no verified availability counts as "unknown" on your services ([Availability]). */
    const val SERVICE_UNKNOWN = Availability.SCORE_UNKNOWN
    /** How much a pick's closeness to titles already picked lowers it. */
    const val NEAR = 0.15

    // ---- ranking (pure, unit tested) ----

    class Cand(
        val id: String, val title: String, val genres: List<Int>, val lang: String, val year: Int, val ageDays: Int,
        val rating: Double, val votes: Int, val franchise: Int = 0, val people: Set<Int> = emptySet(),
        val seeds: Set<String> = emptySet(), val trending: Boolean = false,
        /** The service term: [Availability.score] of the title's verified state (unknown when not looked up). */
        val service: Double = SERVICE_UNKNOWN,
    ) { val type get() = id.substringBefore(':') }

    /** A title the viewer showed taste for: summed weight, and the kind of its strongest signal. */
    class Seed(val id: String, val title: String, val w: Double, val kind: String)

    class Profile(
        val genres: Map<Int, Double> = emptyMap(), val people: Map<Int, Double> = emptyMap(),
        val franchises: Map<Int, Double> = emptyMap(), val langs: Map<String, Double> = emptyMap(),
        val seeds: Map<String, Seed> = emptyMap(),
        /** Genres and seeds behind a "Not for me" in the last 30 days: they weigh 30% less. */
        val dampedGenres: Set<Int> = emptySet(), val dampedSeeds: Set<String> = emptySet(),
        val genreNames: Map<Int, String> = emptyMap(), val peopleNames: Map<Int, String> = emptyMap(), val franchiseNames: Map<Int, String> = emptyMap(),
    )

    /** What is left out or held back: [exclude] (kept, watched, dismissed), Top Picks [shown] times, Indian titles setting. */
    class Feedback(val exclude: Set<String> = emptySet(), val shown: Map<String, List<Long>> = emptyMap(), val indianMode: Int = 0, val indianLangs: Set<String> = emptySet())

    class Pick(val c: Cand, val score: Double, val reason: String, val seed: String?)

    fun base(c: Cand, p: Profile): Double =
        0.30 * seedMatch(c, p) + 0.20 * genreFit(c, p) + 0.20 * peopleOrFranchise(c, p) + 0.15 * c.service + 0.10 * quality(c) + 0.05 * fresh(c)

    fun seedMatch(c: Cand, p: Profile): Double {
        if (p.seeds.isEmpty() || c.seeds.isEmpty()) return 0.0
        val top = p.seeds.values.map { it.w }.sortedDescending().take(2).sum().coerceAtLeast(1.0)
        return (c.seeds.sumOf { s -> p.seeds[s]?.let { if (s in p.dampedSeeds) it.w * 0.7 else it.w } ?: 0.0 } / top).coerceIn(0.0, 1.0)
    }

    fun genreFit(c: Cand, p: Profile): Double {
        val max = p.genres.values.maxOrNull() ?: return 0.0
        if (max <= 0.0 || c.genres.isEmpty()) return 0.0
        val g = c.genres.take(3)
        return g.sumOf { (p.genres[it] ?: 0.0) / max * (if (it in p.dampedGenres) 0.7 else 1.0) } / g.size
    }

    /** A person you watch counts half; a franchise you watched counts in full (double a person). */
    fun peopleOrFranchise(c: Cand, p: Profile): Double {
        val pm = p.people.values.maxOrNull() ?: 0.0
        val person = if (pm > 0) (c.people.maxOfOrNull { p.people[it] ?: 0.0 } ?: 0.0) / pm else 0.0
        val fm = p.franchises.values.maxOrNull() ?: 0.0
        val fr = if (fm > 0 && c.franchise != 0) (p.franchises[c.franchise] ?: 0.0) / fm else 0.0
        return (0.5 * person + fr).coerceAtMost(1.0)
    }

    fun quality(c: Cand) = (c.rating / 10.0).coerceIn(0.0, 1.0) * (c.votes / 500.0).coerceIn(0.0, 1.0)
    fun fresh(c: Cand) = if (c.ageDays in 0..365) 1.0 else 0.0

    /**
     * Top Picks showings without an open: each in the last 14 days costs 0.05, one in the last 3 days 0.05 more;
     * 3 within 14 days rest the title for 30 days from the third. Returns (penalty, resting).
     */
    fun exposure(shown: List<Long>, now: Long): Pair<Double, Boolean> {
        val s = shown.sorted()
        val rested = s.windowed(3).any { w -> w[2] - w[0] < 14 * DAY && now - w[2] < 30 * DAY }
        val pen = 0.05 * s.count { now - it < 14 * DAY } + if (s.any { now - it < 3 * DAY }) 0.05 else 0.0
        return pen to rested
    }

    /** 0 = unrelated .. 1 = near-duplicate: genres, language, decade, franchise and the seed that found them. */
    fun similarity(a: Cand, b: Cand): Double {
        val ga = a.genres.toSet(); val gb = b.genres.toSet()
        val j = if (ga.isEmpty() || gb.isEmpty()) 0.0 else ga.intersect(gb).size.toDouble() / ga.union(gb).size
        return 0.4 * j + (if (a.lang == b.lang) 0.15 else 0.0) + (if (a.year > 0 && a.year / 10 == b.year / 10) 0.1 else 0.0) +
            (if (a.franchise != 0 && a.franchise == b.franchise) 0.2 else 0.0) + (if (a.seeds.any { it in b.seeds }) 0.15 else 0.0)
    }

    /** The seed that found [c] best (strongest, then by id), or null. */
    fun primary(c: Cand, p: Profile): String? = c.seeds.filter { it in p.seeds }.sortedWith(compareByDescending<String> { p.seeds.getValue(it).w }.thenBy { it }).firstOrNull()

    private fun indian(c: Cand, fb: Feedback) = when {
        c.lang !in fb.indianLangs -> 0.0
        fb.indianMode == 1 -> 0.05
        fb.indianMode == 2 -> -0.05
        else -> 0.0
    }

    /**
     * Every eligible candidate, best first. Picked one at a time: score minus closeness to what is already picked.
     * In the first 12: at most 3 per main genre, 2 per seed, 2 per franchise, and one title outside the main
     * language when the history has a second one. Deterministic: ties go to the higher base score, then the id.
     */
    fun order(cands: List<Cand>, p: Profile, fb: Feedback, now: Long): List<Pick> {
        val left = cands.asSequence()
            .filter { it.id !in fb.exclude && it.ageDays >= 0 && !(fb.indianMode == 3 && it.lang in fb.indianLangs) }
            .mapNotNull { c -> val (pen, rest) = exposure(fb.shown[c.id].orEmpty(), now); if (rest) null else c to (base(c, p) - pen + indian(c, fb)) }
            .sortedWith(compareByDescending<Pair<Cand, Double>> { it.second }.thenBy { it.first.id }).toMutableList()
        val out = ArrayList<Pick>()
        val genreN = HashMap<Int, Int>(); val seedN = HashMap<String, Int>(); val frN = HashMap<Int, Int>()
        val topLang = p.langs.maxByOrNull { it.value }?.key
        val secondLang = topLang != null && p.langs.any { it.key != topLang && it.value >= 0.2 * p.langs.getValue(topLang) }
        fun capsOk(c: Cand) = (c.genres.firstOrNull()?.let { (genreN[it] ?: 0) < 3 } ?: true) &&
            (primary(c, p)?.let { (seedN[it] ?: 0) < 2 } ?: true) && (c.franchise == 0 || (frN[c.franchise] ?: 0) < 2)
        fun best(ok: (Cand) -> Boolean): Int? {
            var bi = -1; var bv = Double.NEGATIVE_INFINITY
            for ((i, e) in left.withIndex()) {
                if (!ok(e.first)) continue
                val v = e.second - NEAR * (out.maxOfOrNull { similarity(it.c, e.first) } ?: 0.0)
                if (v > bv + 1e-12) { bv = v; bi = i }
            }
            return bi.takeIf { it >= 0 }
        }
        while (left.isNotEmpty()) {
            val capped = out.size < 12
            val needOther = capped && out.size == 11 && secondLang && out.all { it.c.lang == topLang }
            val i = (if (needOther) best { capsOk(it) && it.lang != topLang } else null)
                ?: (if (capped) best { capsOk(it) } else null) ?: best { true }!!
            val (c, s) = left.removeAt(i)
            val (why, seed) = reason(c, p)
            out.add(Pick(c, s, why, seed))
            if (capped) {
                c.genres.firstOrNull()?.let { genreN[it] = (genreN[it] ?: 0) + 1 }
                primary(c, p)?.let { seedN[it] = (seedN[it] ?: 0) + 1 }
                if (c.franchise != 0) frN[c.franchise] = (frN[c.franchise] ?: 0) + 1
            }
        }
        return out
    }

    fun seedLine(s: Seed) = when (s.kind) {
        "favourite" -> "Because you love ${s.title}"
        "watchlist" -> "Because ${s.title} is on your Watchlist"
        "more" -> "More like ${s.title}"
        "details" -> "Because you looked at ${s.title}"
        else -> "Because you watched ${s.title}"
    }

    /**
     * The line shown above a pick: from its strongest term, a specific one (a seed title, a franchise or person)
     * counting double against a broad genre match. Returns (reason, the seed it names).
     */
    fun reason(c: Cand, p: Profile): Pair<String, String?> {
        val seed = 0.60 * seedMatch(c, p); val genre = 0.20 * genreFit(c, p); val pf = 0.40 * peopleOrFranchise(c, p)
        val top = maxOf(seed, genre, pf)
        if (top <= 0.0) return (if (c.trending) "Trending this week" else "Highly rated") to null
        return when (top) {
            seed -> primary(c, p)?.let { s -> seedLine(p.seeds.getValue(s)) to s } ?: ("Picked for you" to null)
            pf -> if (c.franchise != 0 && c.franchise in p.franchises) "More from ${p.franchiseNames[c.franchise] ?: "the series"}" to null
                  else c.people.filter { it in p.people }.maxByOrNull { p.people.getValue(it) }?.let { "With ${p.peopleNames[it] ?: "a favourite"}" to null } ?: ("Picked for you" to null)
            else -> if (c.trending) "Trending this week" to null
                    else c.genres.firstOrNull { (p.genres[it] ?: 0.0) > 0 }?.let { g -> p.genreNames[g]?.takeIf { it.isNotEmpty() }?.let { "Because you like $it" } }?.let { it to null } ?: ("Picked for you" to null)
        }
    }

    // ---- the deck ----

    /** The saved recommendations. [rows]: titled personal rows ("Because you watched …", "More from …", "With …"). */
    class Deck(val at: Long, val items: Map<String, ContentItem>, val top: List<String>, val movie: List<String>, val tv: List<String>,
               val rows: List<Pair<String, List<String>>>, val trending: List<String>,
               /** Titles confirmed on a pinned app (TMDB, region, subscription): id -> that app's name. Missing = unknown. */
               val avail: Map<String, String> = emptyMap(),
               /** New on your apps: recent releases confirmed on a pinned app, at least [Availability.MIN_NEW]. */
               val fresh: List<String> = emptyList()) {
        /** Trending titles confirmed on your apps (the row uses them only when there are enough). */
        val trendingApps get() = trending.filter { it in avail }
    }

    /** Main thread: the service the focused title is confirmed on, from the deck in memory; null = no claim. */
    fun badge(id: String): String? = deck?.avail?.get(id)

    @Volatile private var deck: Deck? = null
    @Volatile private var loaded = false
    /** Changes whenever a new deck is in memory; screens compare it to know when to show the new one. */
    @Volatile var version = 0; private set
    private val running = AtomicBoolean(false)
    private val runId = AtomicInteger(0)
    private fun dir(c: Context) = File(c.filesDir, "recs")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("recs", Context.MODE_PRIVATE)

    /** Worker thread: the saved deck (read from disk once). Null when there is none, or it can't be read. */
    fun deck(c: Context): Deck? {
        if (loaded) return deck
        synchronized(this) {
            if (!loaded) { deck = try { parseDeck(File(dir(c), "deck.json").readText()) } catch (_: Exception) { null }; loaded = true }
            return deck
        }
    }

    /** A deck that can't be read, or has fewer than [MIN_DECK] top picks, counts as no deck (never fatal). */
    fun parseDeck(s: String?): Deck? = try {
        val o = JSONObject(s ?: "")
        val items = o.getJSONObject("items").let { m -> m.keys().asSequence().associateWith { ContentItem.of(m.getJSONObject(it)) } }
        fun ids(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }.filter { it in items }
        val rows = o.optJSONArray("rows")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.getString("t") to ids(it.optJSONArray("ids")) } } ?: emptyList()
        // Release 1 decks have no "avail" / "fresh": every title is unknown and the row stays hidden.
        val avail = o.optJSONObject("avail")?.let { m -> m.keys().asSequence().filter { it in items }.associateWith { m.optString(it) }.filterValues { it.isNotEmpty() } } ?: emptyMap()
        Deck(o.getLong("at"), items, ids(o.optJSONArray("top")), ids(o.optJSONArray("movie")), ids(o.optJSONArray("tv")), rows, ids(o.optJSONArray("trending")),
            avail, ids(o.optJSONArray("fresh")).filter { it in avail })
            .takeIf { it.top.size >= MIN_DECK }
    } catch (_: Exception) { null }

    private fun write(c: Context, d: Deck) {
        val o = JSONObject().put("v", DECK_V).put("at", d.at).put("items", JSONObject().also { m -> d.items.forEach { (k, v) -> m.put(k, v.json()) } })
            .put("top", JSONArray(d.top)).put("movie", JSONArray(d.movie)).put("tv", JSONArray(d.tv)).put("trending", JSONArray(d.trending))
            .put("rows", JSONArray(d.rows.map { (t, ids) -> JSONObject().put("t", t).put("ids", JSONArray(ids)) }))
            .put("avail", JSONObject(d.avail)).put("fresh", JSONArray(d.fresh))
        val dir = dir(c).apply { mkdirs() }
        val tmp = File(dir, "deck.json.tmp"); tmp.writeText(o.toString())
        java.nio.file.Files.move(tmp.toPath(), File(dir, "deck.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun lastRefresh(c: Context): Long = prefs(c).getLong("done", 0)

    /**
     * Main thread. Refreshes the deck in the background when it is due: no deck yet, a day old, or marked dirty
     * ("More like this"); a failed refresh waits an hour. One at a time across restarts (persisted 30-minute lease),
     * and a run made obsolete by a reset saves nothing. [watchNext]: titles in the TV's Continue Watching (worker).
     */
    fun refresh(c: Context, content: ContentRepository, catalog: Catalog, user: UserLibrary, indianMode: Int,
                /** Pinned apps, package to label, in order: what availability is checked against. */
                pinned: List<Pair<String, String>>,
                watchNext: () -> List<String>, force: Boolean = false, done: (Boolean) -> Unit = {}) {
        if (!catalog.ready) { Log.i(TAG, "refresh skipped: no catalogue key"); return }
        val p = prefs(c); val now = System.currentTimeMillis()
        // A deck saved by an older release (no availability) is refreshed once.
        val due = force || p.getBoolean("dirty", false) || now - p.getLong("done", 0) >= DAY || p.getInt("deckV", 1) < DECK_V
        // A failed refresh waits an hour; one cut short by the process dying (no outcome recorded) does not.
        if (!due || (!force && now - p.getLong("try", 0) < HOUR)) { Log.i(TAG, "refresh skipped: ${if (due) "failed recently" else "not due"}"); return }
        // The lease belongs to this process: one left by a process that was killed mid-refresh is stale.
        val pid = android.os.Process.myPid()
        if (p.getInt("leasePid", 0) == pid && now - p.getLong("lease", 0) in 0 until LEASE) return
        if (!running.compareAndSet(false, true)) return
        Log.i(TAG, "refresh started")
        p.edit().putLong("lease", now).putInt("leasePid", pid).apply()
        val run = runId.incrementAndGet()
        val keep = user.items(UserLibrary.Kind.FAVOURITES) to user.items(UserLibrary.Kind.WATCHLIST)
        content.run({
            val d = build(c, catalog, keep.first, keep.second, indianMode, pinned, watchNext, now) ?: return@run false
            synchronized(this) { if (run != runId.get()) return@run false; write(c, d); deck = d; loaded = true }
            true
        }) { ok ->
            running.set(false)
            val e = p.edit().putLong("lease", 0)
            if (ok == true && run == runId.get()) { e.putLong("done", System.currentTimeMillis()).putBoolean("dirty", false).putInt("deckV", DECK_V); version++ }
            else if (ok != true) e.putLong("try", System.currentTimeMillis())
            e.apply()
            Log.i(TAG, "refresh ${if (ok == true) "saved a new deck" else "kept the last deck"}")
            done(ok == true)
        }
    }

    /** The next refresh runs as soon as it may ("More like this"). */
    fun markDirty(c: Context) = prefs(c).edit().putBoolean("dirty", true).apply()

    /** Worker thread. "Not for me": the title leaves the saved deck at once. */
    fun drop(c: Context, id: String) {
        synchronized(this) {
            val d = deck(c) ?: return
            val n = Deck(d.at, d.items - id, d.top - id, d.movie - id, d.tv - id, d.rows.map { (t, ids) -> t to (ids - id) }, d.trending - id, d.avail - id, d.fresh - id)
            deck = n; write(c, n)
        }
        version++
    }

    /** Worker thread. Reset recommendations: deck, taste, feedback and dismissals; nothing else. */
    fun reset(c: Context) {
        synchronized(this) {
            runId.incrementAndGet(); deck = null; loaded = true
            File(dir(c), "deck.json").delete()
            prefs(c).edit().clear().apply()
        }
        Taste.reset(c); version++
    }

    /** Main thread, deck in hand: 16 Top Picks, those not shown in the last 3 days first; recorded as shown. */
    fun deal(c: Context, d: Deck, n: Int = 16): List<ContentItem> {
        val t = Taste.snapshot(c); val now = System.currentTimeMillis()
        val ok = d.top.filter { it !in t.dismissed }
        val fresh = ok.filter { id -> t.shown[id]?.any { now - it < 3 * DAY } != true }
        val ids = (fresh + (ok - fresh.toSet())).take(n)
        Taste.shown(c, ids)
        return ids.mapNotNull { d.items[it] }
    }

    /** Main thread: [ids] as items, minus anything dismissed since the deck was made. */
    fun items(c: Context, d: Deck, ids: List<String>): List<ContentItem> {
        val gone = Taste.snapshot(c).dismissed
        return ids.filter { it !in gone }.mapNotNull { d.items[it] }
    }

    // ---- building a deck (worker thread) ----

    private class Builder(val id: String, val title: String, val genres: List<Int>, val lang: String, val year: Int, val ageDays: Int, val rating: Double, val votes: Int) {
        var franchise = 0; val people = HashSet<Int>(); val seeds = HashSet<String>(); var trending = false; var service = SERVICE_UNKNOWN
        fun cand() = Cand(id, title, genres, lang, year, ageDays, rating, votes, franchise, people, seeds, trending, service)
    }

    private fun tmdbId(id: String) = Regex("^(movie|tv):(\\d+)$").find(id)?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    private fun build(c: Context, catalog: Catalog, fav: List<ContentItem>, list: List<ContentItem>, indianMode: Int, pinned: List<Pair<String, String>>,
                      watchNext: () -> List<String>, now: Long): Deck? {
        val t = Taste.snapshot(c)
        // Seeds: weighted signals per title, each with the kind of its strongest signal.
        val w = HashMap<String, Double>(); val kind = HashMap<String, Pair<String, Double>>(); val names = HashMap<String, String>()
        fun seed(id: String, title: String, v: Double, k: String) {
            if (tmdbId(id) == null || v <= 0.01) return
            w[id] = (w[id] ?: 0.0) + v; names.putIfAbsent(id, title)
            if ((kind[id]?.second ?: 0.0) < v) kind[id] = k to v
        }
        t.events.forEach { e -> seed(e.id, e.title, Taste.weight(e, now), e.kind) }
        fav.forEach { seed(it.id, it.title, Taste.FAVOURITE, "favourite") }
        list.forEach { seed(it.id, it.title, Taste.WATCHLIST, "watchlist") }
        // The TV's Continue Watching, matched to TMDB only by an exact title (anything else is left out).
        try {
            watchNext().take(10).forEach { title ->
                val (m, s) = catalog.search(title)
                (s + m).firstOrNull { Taste.norm(it.title) == Taste.norm(title) }?.let { seed(it.id, it.title, Taste.CONTINUE, "watch") }
            }
        } catch (e: Exception) { Log.i(TAG, "Continue Watching not matched: ${e.javaClass.simpleName}") }
        val seeds = w.map { (id, v) -> Seed(id, names[id] ?: "", v, kind[id]?.first ?: "watch") }.sortedWith(compareByDescending<Seed> { it.w }.thenBy { it.id })

        // Profile and candidates from the seeds' TMDB records (cached with the details pages).
        val genres = HashMap<Int, Double>(); val langs = HashMap<String, Double>(); val people = HashMap<Int, Double>(); val franchises = HashMap<Int, Double>()
        val peopleNames = HashMap<Int, String>(); val franchiseNames = HashMap<Int, String>()
        val pool = LinkedHashMap<String, Builder>(); val items = HashMap<String, ContentItem>()
        fun add(o: JSONObject, typeHint: String): Builder? {
            val type = o.optString("media_type").ifEmpty { typeHint }
            if (type != "movie" && type != "tv") return null
            val id = "$type:${o.optInt("id")}"
            pool[id]?.let { return it }
            val item = catalog.item(o, type) ?: return null
            val date = o.optString(if (type == "movie") "release_date" else "first_air_date")
            val age = ageDays(date, now)
            val g = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
                ?: o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optInt("id") } } ?: emptyList()
            items[id] = item
            return Builder(id, item.title, g, o.optString("original_language"), date.take(4).toIntOrNull() ?: 0, age, o.optDouble("vote_average", 0.0), o.optInt("vote_count")).also { pool[id] = it }
        }
        for ((i, s) in seeds.take(10).withIndex()) {
            val (type, num) = tmdbId(s.id) ?: continue
            val rec = try { catalog.record(type, num) } catch (_: Exception) { continue }
            rec.optJSONArray("genres")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).optInt("id").let { g -> genres[g] = (genres[g] ?: 0.0) + s.w } }
            rec.optString("original_language").takeIf { it.isNotEmpty() }?.let { langs[it] = (langs[it] ?: 0.0) + s.w }
            if (s.kind != "watchlist" && s.kind != "details") rec.optJSONObject("belongs_to_collection")?.let { col ->
                val f = col.optInt("id"); if (f != 0) { franchises[f] = (franchises[f] ?: 0.0) + s.w; franchiseNames[f] = col.optString("name").removeSuffix(" Collection") }
            }
            rec.optJSONObject("recommendations")?.optJSONArray("results")?.let { a -> for (k in 0 until a.length()) add(a.getJSONObject(k), type)?.seeds?.add(s.id) }
            if (i < 5 && s.kind != "details") try {
                val cr = catalog.credits(type, num)
                val cast = cr.optJSONArray("cast"); val crew = cr.optJSONArray("crew")
                if (cast != null) for (k in 0 until minOf(3, cast.length())) cast.getJSONObject(k).let { pp -> people[pp.optInt("id")] = (people[pp.optInt("id")] ?: 0.0) + 0.6 * s.w; peopleNames[pp.optInt("id")] = pp.optString("name") }
                if (crew != null) for (k in 0 until crew.length()) crew.getJSONObject(k).takeIf { it.optString("job") == "Director" }?.let { pp -> people[pp.optInt("id")] = (people[pp.optInt("id")] ?: 0.0) + 0.8 * s.w; peopleNames[pp.optInt("id")] = pp.optString("name") }
            } catch (_: Exception) {}
        }
        // Franchises and people you keep coming back to.
        franchises.entries.sortedByDescending { it.value }.take(2).forEach { (f, _) ->
            try { catalog.collection(f).optJSONArray("parts")?.let { a -> for (k in 0 until a.length()) add(a.getJSONObject(k), "movie")?.franchise = f } } catch (_: Exception) {}
        }
        people.entries.sortedByDescending { it.value }.take(2).forEach { (pid, _) ->
            try {
                val pc = catalog.personCredits(pid)
                val all = listOfNotNull(pc.optJSONArray("cast"), pc.optJSONArray("crew")).flatMap { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                    .filter { it.has("character") || it.optString("job") == "Director" }
                    .sortedByDescending { it.optDouble("popularity", 0.0) }.take(25)
                all.forEach { o -> add(o, "")?.people?.add(pid) }
            } catch (_: Exception) {}
        }
        // Discovery that needs no history: trending, top rated, the favourite (or varied) genres, Indian titles per the setting.
        try { val a = catalog.rawRow(catalog.trendingAll); for (k in 0 until a.length()) add(a.getJSONObject(k), "")?.trending = true } catch (_: Exception) {}
        val specs = catalog.movieRows() + catalog.tvRows()
        val favGenres = genres.entries.sortedByDescending { it.value }.map { it.key }.take(2).ifEmpty { listOf(28, 35, 18) }
        val wanted = specs.filter { s -> s.title == "Top Rated" || favGenres.any { g -> s.path.contains("with_genres=$g&") } } +
            (if (Sources.indianLangs.isNotEmpty() && indianMode != 3) specs.filter { it.path.contains("with_original_language=") } else emptyList())
        for (s in wanted) try { val a = catalog.rawRow(s); val type = if (s.path.contains("movie")) "movie" else "tv"; for (k in 0 until a.length()) add(a.getJSONObject(k), type) } catch (_: Exception) {}
        // Availability (Release 2): the region's TMDB providers mapped to pinned apps by name, at runtime. Any failure
        // leaves the map empty: every title stays unknown (never "unavailable") and the deck is built as before.
        val pinnedPkgs = pinned.map { it.first }; val usage = t.services
        val pmap = try { Availability.map(catalog.providerList("movie") + catalog.providerList("tv"), pinned) } catch (_: Exception) { emptyMap() }
        val onApps = LinkedHashMap<String, MutableSet<Int>>()          // recent releases TMDB confirms on a pinned app
        for ((_, ids) in pmap.entries.groupBy({ it.value }, { it.key })) for (type in listOf("movie", "tv")) try {
            val a = catalog.newOn(type, ids)
            for (k in 0 until minOf(20, a.length())) add(a.getJSONObject(k), type)?.let { b -> onApps.getOrPut(b.id) { HashSet() }.addAll(ids) }
        } catch (_: Exception) {}
        Log.i(TAG, "availability ${catalog.availRegion()}: ${pmap.values.toSet()} mapped from ${pmap.size} providers, ${onApps.size} recent on them")
        if (pool.size < MIN_DECK) return null                    // offline or TMDB failing: keep the last deck

        val dismissedAt = t.dismissed
        val recentDismissals = dismissedAt.filterValues { now - it < Taste.DAMP }.keys
        val profile = Profile(genres, people, franchises, langs, seeds.take(10).associateBy { it.id },
            dampedGenres = recentDismissals.flatMap { pool[it]?.genres.orEmpty() }.toSet(),
            dampedSeeds = recentDismissals.flatMap { pool[it]?.seeds.orEmpty() }.toSet(),
            genreNames = (genres.keys + pool.values.flatMap { it.genres }).associateWith { catalog.genreName(it) },
            peopleNames = peopleNames, franchiseNames = franchiseNames)
        val fb = Feedback(exclude = seeds.map { it.id }.toSet() + dismissedAt.keys, shown = t.shown, indianMode = indianMode, indianLangs = Sources.indianLangs.toSet())
        var picks = order(pool.values.map { it.cand() }, profile, fb, now)
        if (picks.size < MIN_DECK) return null

        // Each likely-shown title's own region entry (the details page's cached record) decides its service term; the
        // rest stay unknown. Then the same ranking runs again with those terms. Nothing is ever dropped for availability.
        val avail = HashMap<String, String>()
        if (pmap.isNotEmpty()) {
            val region = catalog.availRegion()
            onApps.forEach { (id, ids) -> Availability.classify(true, ids.toList(), pmap, pinnedPkgs, usage).app?.let { avail[id] = it; pool[id]?.service = Availability.SCORE_CONFIRMED } }
            val look = (picks.take(60) + picks.filter { it.c.trending }.take(20)).map { it.c.id }.distinct().filter { it !in avail }
            var elsewhere = 0
            for (id in look) {
                val (type, num) = tmdbId(id) ?: continue
                val r = try { catalog.record(type, num).optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(region) } catch (_: Exception) { continue }
                val flat = r?.optJSONArray("flatrate")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optInt("provider_id") } }
                val res = Availability.classify(r != null, flat, pmap, pinnedPkgs, usage)
                pool[id]?.service = Availability.score(res.state)
                res.app?.let { avail[id] = it }
                if (res.state == Availability.State.ELSEWHERE) elsewhere++
            }
            picks = order(pool.values.map { it.cand() }, profile, fb, now)
            Log.i(TAG, "availability: ${avail.size} confirmed, $elsewhere elsewhere, of ${look.size} looked up and ${onApps.size} recent")
        }

        // The deck: Top Picks, For You per type, personal rows, trending; each item carries its reason.
        val withWhy = picks.associate { pk -> pk.c.id to items.getValue(pk.c.id).let { i -> ContentItem(i.id, i.title, i.meta, i.imageUrl, i.linkUri, i.linkPkg, pk.reason, i.summary, i.weight, i.origin, i.logoUrl, i.posterUrl) } }
        val ids = picks.map { it.c.id }
        val rows = ArrayList<Pair<String, List<String>>>()
        seeds.filter { it.w >= 0.9 && it.kind in setOf("watch", "favourite", "more") }.take(2).forEach { s ->
            val r = picks.filter { s.id in it.c.seeds }.map { it.c.id }
            if (r.size >= 8) rows.add(seedLine(s) to r.take(20))
        }
        franchises.maxByOrNull { it.value }?.key?.let { f -> picks.filter { it.c.franchise == f }.map { it.c.id }.takeIf { it.size >= 6 }?.let { rows.add("More from ${franchiseNames[f]}" to it.take(20)) } }
        people.maxByOrNull { it.value }?.key?.let { pid -> picks.filter { pid in it.c.people }.map { it.c.id }.takeIf { it.size >= 6 }?.let { rows.add("With ${peopleNames[pid]}" to it.take(20)) } }
        // New on your apps: recent releases confirmed on a pinned app, in recommendation order (dismissed and kept titles
        // are already out), shown only with enough of them.
        val fresh = Availability.newRow(ids.filter { it in onApps && it in avail }.take(20))
        val used = (ids.take(40) + picks.filter { it.c.type == "movie" }.take(20).map { it.c.id } + picks.filter { it.c.type == "tv" }.take(20).map { it.c.id } +
            rows.flatMap { it.second } + picks.filter { it.c.trending }.take(20).map { it.c.id } + fresh).toSet()
        val labels = pinned.toMap()
        Log.i(TAG, "deck: ${seeds.size} seeds, ${pool.size} candidates, ${picks.size} ranked, ${rows.size} personal rows, ${fresh.size} new on your apps")
        return Deck(now, withWhy.filterKeys { it in used }, ids.take(40), picks.filter { it.c.type == "movie" }.take(20).map { it.c.id },
            picks.filter { it.c.type == "tv" }.take(20).map { it.c.id }, rows, picks.filter { it.c.trending }.take(20).map { it.c.id },
            avail.filterKeys { it in used }.mapValues { labels[it.value].orEmpty() }.filterValues { it.isNotEmpty() }, fresh)
    }

    /** Days since a TMDB date (yyyy-MM-dd); negative = not released yet; unknown dates count as old. */
    fun ageDays(date: String, now: Long): Int = try {
        val d = java.time.LocalDate.parse(date.take(10))
        ((now / DAY) - d.toEpochDay()).toInt()
    } catch (_: Exception) { 100_000 }
}
