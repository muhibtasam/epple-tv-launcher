package com.eppletv.launcher.content

import android.util.Log
import com.eppletv.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.ln

object Apps {
    const val NETFLIX = "com.netflix.ninja"
    const val PRIME = "com.amazon.amazonvideo.livingroom"
    const val CRUNCHYROLL = "com.crunchyroll.crunchyroid"
    const val YOUTUBE = "com.google.android.youtube.tv"
    const val STREMIO = "com.stremio.one"
    const val TAPMAD = "com.pitelevision.tapmadtv"
    const val APPLE_TV = "com.apple.atve.androidtv.appletv"
    val DEFAULT_PINS = listOf(NETFLIX, PRIME, CRUNCHYROLL, YOUTUBE, STREMIO, TAPMAD)
}

object Sources {
    /** Content region: local.properties content_region, else the device's country. */
    /** Settings > Content region; empty = automatic. Set before the sources are built. */
    var override = ""
    /** Settings > Languages: Indian languages included in discovery (empty = Hollywood only). */
    var indianLangs: List<String> = Catalog.INDIAN
    val region: String get() = override.ifEmpty { BuildConfig.CONTENT_REGION }.ifEmpty { Locale.getDefault().country }.ifEmpty { "US" }.uppercase(Locale.US)

    /** [debugCinemetaFor]: debug builds only, lets an emulator without Stremio exercise the content pipeline. */
    private val showcase = Showcase(
        mapOf(
            Apps.NETFLIX to Service("Netflix", watchmode = "203", tmdb = "8"),
            Apps.PRIME to Service("Prime Video", watchmode = "26", tmdb = "119|9"),
            Apps.CRUNCHYROLL to Service("Crunchyroll", watchmode = "80,79", tmdb = "283", indian = false),
            Apps.APPLE_TV to Service("Apple TV", watchmode = "371", tmdb = "350", indian = false),
        ),
        Keys(), { region },
    )

    fun all(ctx: android.content.Context, debugCinemetaFor: String? = null): List<ContentSource> = listOf(
        Cinemeta(setOfNotNull(Apps.STREMIO, debugCinemetaFor)),
        NetflixTop10(showcase, Keys(), { region }),
        YouTube({ Credentials.get(Credentials.Key.YOUTUBE) }, { region }),
        YouTubeChannel(Apps.TAPMAD, "tapmad", TAPMAD_UPLOADS) { Credentials.get(Credentials.Key.YOUTUBE) },
        channels(ctx),                                               // any app's own rows, once TV listings may be read
        showcase,
    )

    private var channels: AppChannels? = null
    /** The one [AppChannels]: Home asks it which apps have rows every time it comes to the front. */
    fun channels(ctx: android.content.Context) = channels ?: AppChannels(ctx.applicationContext) { net, title, movie -> matchFor(net, title, movie) }.also { channels = it }

    /** TMDB's entry for an app's title, by exact name (movie or show; null = try both): its poster, details and genres. */
    fun matchFor(net: Net, title: String, movie: Boolean?): ContentItem? {
        if (!usesTmdb) return null
        val name = title.replace(Regex("\\s*[-:–]\\s*(Season|Series)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()
        val kinds = when (movie) { true -> listOf("movie", "tv"); false -> listOf("tv", "movie"); null -> listOf("movie", "tv") }
        for (k in kinds) showcase.find(net, k, name, "", 0.0, exact = true)?.let { return it }
        return null
    }

    /** A portrait poster from TMDB for a title known only by name (an app's row, Watch Next). Null without TMDB. */
    fun posterFor(net: Net, title: String, movie: Boolean): String? {
        if (!usesTmdb) return null
        val name = title.replace(Regex("\\s*[-:–]\\s*(Season|Series)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()
        return (showcase.find(net, if (movie) "movie" else "tv", name, "", 0.0, exact = true) ?: if (movie) null else showcase.find(net, "movie", name, "", 0.0, exact = true))
            ?.posterUrl?.ifEmpty { null }
    }

    val usesTmdb get() = Credentials.hasAny(Credentials.Key.TMDB)

    /** Uploads playlist of tapmad's official YouTube channel (@tapmadofficial, UCPHOf3lVS8bUSwR1h55EO6g). */
    private const val TAPMAD_UPLOADS = "UUPHOf3lVS8bUSwR1h55EO6g"
}

private fun year(date: String) = date.take(4)
private fun join(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString("  ·  ")
private const val HOUR = 60 * 60 * 1000L

/** Stremio's official catalogue add-on (public add-on protocol, no key). Titles open inside Stremio via its documented deep links. */
class Cinemeta(private val pkgs: Set<String>) : ContentSource {
    override val key = "cinemeta3"
    override val ready = true
    override fun handles(pkg: String) = pkg in pkgs
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val out = ArrayList<ContentItem>()
        for (type in arrayOf("movie", "series")) {
            val metas = JSONObject(net.cached("cinemeta-$type", 6 * HOUR, "https://v3-cinemeta.strem.io/catalog/$type/top.json")).getJSONArray("metas")
            for (i in 0 until minOf(12, metas.length())) {
                val m = metas.getJSONObject(i)
                val id = m.optString("id").ifEmpty { m.optString("imdb_id") }
                if (!id.startsWith("tt")) continue
                val genres = m.optJSONArray("genre")?.let { g -> (0 until minOf(2, g.length())).joinToString(", ") { g.getString(it) } } ?: ""
                val rating = m.optString("imdbRating").let { if (it.isEmpty() || it == "null") "" else "IMDb $it" }
                val link = if (type == "movie") "stremio:///detail/movie/$id/$id" else "stremio:///detail/series/$id"
                out.add(ContentItem(id, m.optString("name"), join(m.optString("releaseInfo").ifEmpty { year(m.optString("released")) },
                    if (type == "movie") "Movie" else "Series", genres, rating),
                    "https://images.metahub.space/background/large/$id/img", link, Apps.STREMIO,
                    "Popular on Stremio", m.optString("description"), weight = 1.0 - i * 0.06, origin = "intl",
                    posterUrl = m.optString("poster").let { if (it.startsWith("https://")) it else "" },
                    logoUrl = m.optString("logo").ifEmpty { "https://images.metahub.space/logo/medium/$id/img" }))
            }
        }
        val movies = out.filter { it.linkUri!!.contains("/movie/") }; val series = out - movies.toSet()
        return (0 until maxOf(movies.size, series.size)).flatMap { listOfNotNull(movies.getOrNull(it), series.getOrNull(it)) }
    }
}

/** The provider keys, read when used: a key changed in Settings applies to the next request, without a restart. */
class Keys {
    val tmdb get() = Credentials.get(Credentials.Key.TMDB)
    val watchmode get() = Credentials.get(Credentials.Key.WATCHMODE)
    val trakt get() = Credentials.get(Credentials.Key.TRAKT)
}
class Service(val name: String, val watchmode: String, val tmdb: String, val indian: Boolean = true)

/**
 * Showcase for one streaming service, built only from titles that are on that service. It returns a scored pool
 * (about 40 titles); [ContentRepository] picks a balanced, varied selection from it on each launch.
 *  1. New on the service: Watchmode's catalogue for the service, newest first (service-specific, region-aware).
 *  2. Popular on the service: TMDB's watch-provider catalogue, queried twice, once for English-language titles
 *     (Hollywood) and once for Indian languages (Bollywood and regional cinema), most popular first.
 *  Trakt trending (worldwide) only boosts titles already in 1 or 2. TMDB supplies metadata and backdrops.
 * Each title gets a weight from popularity, release recency and trending, and an origin ("in" or "intl").
 */
class Showcase(private val services: Map<String, Service>, private val keys: Keys, private val region_: () -> String) : ContentSource {
    private val region get() = region_()
    override val key = "showcase2"
    override val uses = setOf(Credentials.Key.TMDB, Credentials.Key.WATCHMODE, Credentials.Key.TRAKT)
    override val ready get() = keys.tmdb.isNotEmpty()
    override fun handles(pkg: String) = pkg in services

    private val v4 get() = keys.tmdb.length > 40
    private val tmdbHeaders get() = if (v4) mapOf("Authorization" to "Bearer ${keys.tmdb}") else emptyMap()
    private fun tmdbUrl(path: String) = "https://api.themoviedb.org/3/$path${if ('?' in path) "&" else "?"}language=en-US" +
        if (v4) "" else "&api_key=${keys.tmdb}"
    private fun tmdb(net: Net, cacheKey: String, ttl: Long, path: String) = JSONObject(net.cached("tmdb-$cacheKey", ttl, tmdbUrl(path), tmdbHeaders))

    private class Pick(val type: String, val id: Int, val isNew: Boolean)

    override fun load(net: Net, pkg: String): List<ContentItem> {
        val svc = services.getValue(pkg)
        val tag = pkg.substringAfterLast('.')
        val trending = trending(net)                                   // "movie:123" -> rank (0 = most trending)
        val picks = LinkedHashMap<String, Pick>()
        val known = HashMap<String, JSONObject>()
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)

        // 1. New on the service (Watchmode): up to 8 recent titles.
        if (keys.watchmode.isNotEmpty()) try {
            val wmRegion = if (region in WATCHMODE_REGIONS) region else if (region in SOUTH_ASIA) "IN" else "US"
            val body = net.cached("wm-$tag-$wmRegion", 12 * HOUR, "https://api.watchmode.com/v1/list-titles/?source_ids=${svc.watchmode}" +
                "&regions=$wmRegion&types=movie,tv_series,tv_miniseries&sort_by=release_date_desc&limit=30&apiKey=${keys.watchmode}")
            val titles = JSONObject(body).optJSONArray("titles") ?: JSONArray()
            var n = 0
            for (i in 0 until titles.length()) {
                val t = titles.getJSONObject(i)
                val id = t.optInt("tmdb_id"); val type = t.optString("tmdb_type")
                if (id <= 0 || type !in setOf("movie", "tv") || t.optInt("year") < thisYear - 1) continue
                if (picks.putIfAbsent("$type:$id", Pick(type, id, true)) == null && ++n == 8) break
            }
        } catch (e: Exception) { Log.w(TAG, "watchmode $tag: ${e.javaClass.simpleName} ${e.message}") }

        // 2. Popular on the service (TMDB watch providers): English-language and Indian-language pools, 12 per type each.
        //    First region that has the service wins.
        val chosen = Sources.indianLangs
        val langs = if (!svc.indian) listOf("") else if (chosen.isEmpty()) listOf("en") else listOf("en", chosen.joinToString("|"))
        for (r in listOf(region, if (region in SOUTH_ASIA) "IN" else "US", "US").distinct()) {
            var found = 0
            for (lang in langs) for (type in arrayOf("movie", "tv")) try {
                val lq = if (lang.isEmpty()) "" else "&with_original_language=$lang"
                val lk = if (lang.isEmpty()) "all" else if (lang == "en") "en" else "in" + chosen.joinToString("")   // the language set is part of the cache key
                val res = tmdb(net, "disc-$tag-$type-$r-$lk", 12 * HOUR, "discover/$type?watch_region=$r&with_watch_providers=${svc.tmdb}" +
                    "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&vote_count.gte=10$lq").optJSONArray("results") ?: JSONArray()
                for (i in 0 until minOf(12, res.length())) {
                    val o = res.getJSONObject(i); val k = "$type:${o.optInt("id")}"
                    known.putIfAbsent(k, o); picks.putIfAbsent(k, Pick(type, o.optInt("id"), false)); found++
                }
            } catch (e: Exception) { Log.w(TAG, "tmdb discover $tag $r: ${e.javaClass.simpleName} ${e.message}") }
            if (found > 0) break
        }

        // 3. Metadata and artwork from TMDB (details only for titles discover didn't already describe).
        val genres = genreNames(net)
        val out = ArrayList<ContentItem>()
        for ((k, p) in picks) {
            if (out.size == POOL) break
            val o = known[k] ?: try { tmdb(net, "${p.type}-${p.id}", 7 * 24 * HOUR, "${p.type}/${p.id}") } catch (e: Exception) { continue }
            val date = o.optString(if (p.type == "movie") "release_date" else "first_air_date")
            val trend = trending[k]
            val kind = when { p.isNew -> "New on ${svc.name}"; trend != null -> "Trending on ${svc.name}"; else -> "Popular on ${svc.name}" }
            build(o, p.type, kind, weight(o.optDouble("popularity", 0.0), date, trend), genres)?.let { out.add(it) }
        }
        return out
    }

    /** One TMDB title as a showcase item (null without a backdrop). */
    private fun build(o: JSONObject, type: String, kind: String, weight: Double, genres: Map<Int, String>): ContentItem? {
        val backdrop = o.optString("backdrop_path"); if (backdrop.isEmpty() || backdrop == "null") return null
        val movie = type == "movie"
        val g = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }
            ?: o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { genres[a.getInt(it)] } } ?: emptyList()
        val vote = o.optDouble("vote_average", 0.0)
        val date = o.optString(if (movie) "release_date" else "first_air_date")
        val lang = o.optString("original_language")
        return ContentItem("$type:${o.optInt("id")}", o.optString(if (movie) "title" else "name"),
            join(year(date), if (movie) "Movie" else "Series", if (lang != "en") languageName(lang) else "",
                g.take(2).joinToString(", "), if (vote > 0) "★ %.1f".format(Locale.US, vote) else ""),
            "https://image.tmdb.org/t/p/original$backdrop", null, null, kind, o.optString("overview"),
            weight = weight, origin = if (lang in INDIAN) "in" else "intl",
            posterUrl = o.optString("poster_path").let { if (it.isEmpty() || it == "null") "" else "https://image.tmdb.org/t/p/w342$it" })
    }

    /**
     * A title found on TMDB by its exact name (Netflix's Top 10 gives names only): the most popular result whose title
     * matches, else the first result. Cached for a month: the same week's names are looked up once.
     */
    fun find(net: Net, type: String, name: String, kind: String, weight: Double, exact: Boolean = false): ContentItem? {
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val res = tmdb(net, "find-$type-${name.lowercase(Locale.ROOT).hashCode()}", 30 * 24 * HOUR, "search/$type?query=$q&include_adult=false")
            .optJSONArray("results") ?: return null
        val all = (0 until res.length()).map { res.getJSONObject(it) }
        val norm = { t: String -> t.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") }
        val pick = all.filter { norm(it.optString(if (type == "movie") "title" else "name")) == norm(name) }
            .maxByOrNull { it.optDouble("popularity", 0.0) } ?: (if (exact) null else all.firstOrNull()) ?: return null
        return build(pick, type, kind, weight, genreNames(net))
    }

    /** 0.15..1: popularity (log scale) 45%, release recency (two years) 25%, trending 15%, plus a floor so nothing is excluded. */
    private fun weight(popularity: Double, date: String, trendRank: Int?): Double {
        val pop = (ln(1 + popularity) / ln(1 + 400.0)).coerceIn(0.0, 1.0)
        val age = try { (System.currentTimeMillis() - java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!.time) / 86_400_000.0 } catch (_: Exception) { 3650.0 }
        val recent = (1 - age / 730).coerceIn(0.0, 1.0)
        val trend = if (trendRank == null) 0.0 else 1 - trendRank / 60.0
        return 0.15 + 0.45 * pop + 0.25 * recent + 0.15 * trend
    }

    /** Transparent title logo from TMDB (PNG only), English first, then the title's own language, then language-neutral. */
    override fun logo(net: Net, item: ContentItem): String? {
        // Titles from an app's own rows or Watch Next carry no TMDB id: found by name (series first), so every hero
        // shows the title's logo the same way.
        val (type, id) = Regex("^(movie|tv):(\\d+)$").find(item.id)?.destructured?.let { it.component1() to it.component2() } ?: run {
            if (keys.tmdb.isEmpty() || !(item.id.startsWith("wn:") || item.id.startsWith("app:"))) return null
            val name = item.title.replace(Regex("\\s*[-:–]\\s*(Season|Series)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()
            val movie = "Movie" in item.meta
            // Exact names only: a near match would put another title's logo on this one.
            val f = (if (movie) null else find(net, "tv", name, "", 0.0, exact = true)) ?: find(net, "movie", name, "", 0.0, exact = true)
                ?: if (movie) find(net, "tv", name, "", 0.0, exact = true) else null
            f?.id?.split(':')?.let { it[0] to it[1] } ?: return null
        }
        val logos = tmdb(net, "logos-$type-$id", 30 * 24 * HOUR, "$type/$id/images?include_image_language=en,${INDIAN.joinToString(",")},ja,ko,zh,null")
            .optJSONArray("logos") ?: return null
        var best: JSONObject? = null; var bestScore = -1.0
        for (i in 0 until logos.length()) {
            val l = logos.getJSONObject(i)
            val path = l.optString("file_path"); if (!path.endsWith(".png")) continue
            val lang = l.optString("iso_639_1")
            val pref = when (lang) { "en" -> 3.0; "null", "" -> 1.0; else -> 2.0 }
            val s = pref * 10 + l.optDouble("vote_average", 0.0)
            if (s > bestScore) { bestScore = s; best = l }
        }
        return best?.let { "https://image.tmdb.org/t/p/w500${it.optString("file_path")}" }
    }

    /** Trakt trending movies and shows (shared by every service; cached 6 h). Empty when Trakt is unavailable. */
    private fun trending(net: Net): Map<String, Int> {
        if (keys.trakt.isEmpty()) return emptyMap()
        val h = mapOf("trakt-api-version" to "2", "trakt-api-key" to keys.trakt, "Content-Type" to "application/json")
        val out = HashMap<String, Int>()
        for ((path, field, type) in listOf(Triple("movies", "movie", "movie"), Triple("shows", "show", "tv"))) try {
            val a = JSONArray(net.cached("trakt-$path", 6 * HOUR, "https://api.trakt.tv/$path/trending?limit=60", h))
            for (i in 0 until a.length()) {
                val id = a.getJSONObject(i).optJSONObject(field)?.optJSONObject("ids")?.optInt("tmdb") ?: 0
                if (id > 0) out.putIfAbsent("$type:$id", i)
            }
        } catch (e: Exception) { Log.w(TAG, "trakt $path: ${e.javaClass.simpleName} ${e.message}") }
        return out
    }

    private fun genreNames(net: Net): Map<Int, String> {
        val m = HashMap<Int, String>()
        for (t in arrayOf("movie", "tv")) try {
            val a = tmdb(net, "genres-$t", 30 * 24 * HOUR, "genre/$t/list").getJSONArray("genres")
            for (i in 0 until a.length()) a.getJSONObject(i).let { m[it.getInt("id")] = it.getString("name") }
        } catch (_: Exception) {}
        return m
    }

    private fun languageName(code: String) = if (code.isEmpty()) "" else Locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it != code } ?: ""

    private companion object {
        const val TAG = "EppleShowcase"
        const val POOL = 40
        val INDIAN = listOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa")
        val SOUTH_ASIA = setOf("PK", "IN", "BD", "LK", "NP")
        val WATCHMODE_REGIONS = setOf("US", "CA", "GB", "AU", "BR", "ES", "IN", "AE", "AT", "IT", "AR", "BE", "BG", "CL", "CO", "HR", "CZ",
            "DK", "EC", "EE", "FI", "FR", "DE", "GR", "HK", "HU", "IS", "ID", "IE", "IL", "JP", "LT", "MY", "MX", "NL", "NZ", "NO", "PA",
            "PE", "PH", "PL", "PT", "RO", "RU", "RS", "SG", "ZA", "KR", "SE", "CH", "TH", "TR", "UA", "VN")
    }
}

/** YouTube Data API v3, most popular videos in the region. Opens each video in the YouTube app. */
class YouTube(private val key_: () -> String, private val region_: () -> String) : ContentSource {
    private val region get() = region_()
    private val apiKey get() = key_()
    override val key = "youtube2"
    override val uses = setOf(Credentials.Key.YOUTUBE)
    override val ready get() = apiKey.isNotEmpty()
    override fun handles(pkg: String) = pkg == Apps.YOUTUBE
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val items = JSONObject(net.cached("yt-popular-$region", 3 * HOUR,
            "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&maxResults=25&regionCode=$region&key=$apiKey")).getJSONArray("items")
        return youtubeItems(items, "Trending on YouTube") { it.getString("id") }
    }
}

/** Latest uploads of an app's official YouTube channel (legitimate public source for apps with no catalogue API). */
class YouTubeChannel(private val pkg: String, private val name: String, private val uploads: String, private val key_: () -> String) : ContentSource {
    private val apiKey get() = key_()
    override val key = "ytchannel2"
    override val uses = setOf(Credentials.Key.YOUTUBE)
    override val ready get() = apiKey.isNotEmpty()
    override fun handles(pkg: String) = pkg == this.pkg
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val items = JSONObject(net.cached("yt-$uploads", 3 * HOUR,
            "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=25&playlistId=$uploads&key=$apiKey")).getJSONArray("items")
        return youtubeItems(items, "Latest from $name") { it.getJSONObject("snippet").getJSONObject("resourceId").getString("videoId") }
    }
}

/** "KING: Tu Mil Jaaye | Shah Rukh Khan | ..." -> "KING: Tu Mil Jaaye"; emoji removed. Keeps the full title if the first part is too short. */
private fun cleanTitle(t: String): String {
    val noEmoji = t.codePoints().toArray().filter { Character.getType(it) != Character.OTHER_SYMBOL.toInt() && it !in 0xFE00..0xFE0F && it != 0x200D }
        .let { String(it.toIntArray(), 0, it.size) }.replace(Regex("\\s+"), " ").trim()
    val first = noEmoji.split(" | ", " || ", " l ").first().trim().trimEnd('|', '-', ' ')
    return if (first.length >= 12) first else noEmoji
}

/** Only videos with true 16:9 HD artwork (maxres thumbnails); at most 10. */
private fun youtubeItems(items: JSONArray, kind: String, idOf: (JSONObject) -> String): List<ContentItem> {
    val out = ArrayList<ContentItem>()
    for (i in 0 until items.length()) {
        val v = items.getJSONObject(i); val s = v.getJSONObject("snippet")
        val img = s.optJSONObject("thumbnails")?.optJSONObject("maxres")?.optString("url") ?: continue
        val id = idOf(v)
        val date = s.optString("publishedAt").take(10)
        out.add(ContentItem(id, cleanTitle(s.optString("title")), join(s.optString("channelTitle"), date), img,
            "https://www.youtube.com/watch?v=$id", Apps.YOUTUBE, kind, ""))
        if (out.size == 10) break
    }
    return out
}

/**
 * Netflix on Home. Netflix publishes nothing to other launchers, so its titles are Netflix's own official weekly Top 10
 * for the country (Netflix's public all-weeks-countries data, trimmed to the latest week by the VYRO project's weekly job
 * and served from its site), matched to TMDB by name for artwork and details, then the [Showcase]'s new and popular
 * titles on Netflix. Each opens at the title inside Netflix through [TitleLauncher] (Watchmode's Netflix id).
 * A country Netflix doesn't rank falls back to the global (ZZ) list when present, else to the showcase alone.
 */
class NetflixTop10(private val showcase: Showcase, private val keys: Keys, private val region_: () -> String) : ContentSource {
    override val key = "netflix-top10-1"
    override val ordered = true                                   // Top 10 by rank, then new on Netflix
    override val uses = setOf(Credentials.Key.TMDB, Credentials.Key.WATCHMODE, Credentials.Key.TRAKT)
    override val ready get() = keys.tmdb.isNotEmpty()
    override fun handles(pkg: String) = pkg == Apps.NETFLIX

    class Title(val name: String, val movie: Boolean, val rank: Int)

    override fun load(net: Net, pkg: String): List<ContentItem> {
        val top = try {
            val tsv = net.cached("netflix-top10", 12 * HOUR, URL)
            top10(tsv, region_()).ifEmpty { top10(tsv, "ZZ") }
        } catch (e: Exception) { Log.w(TAG, "top 10: ${e.javaClass.simpleName} ${e.message}"); emptyList() }
        val out = ArrayList<ContentItem>()
        val country = Locale("", region_()).getDisplayCountry(Locale.ENGLISH).ifEmpty { region_() }
        for (t in top) try {
            showcase.find(net, if (t.movie) "movie" else "tv", t.name, "#${t.rank} in $country on Netflix", 1.0 - (t.rank - 1) * 0.04)?.let { out.add(it) }
        } catch (e: Exception) { Log.w(TAG, "find ${t.name}: ${e.javaClass.simpleName}") }
        val seen = out.map { it.id }.toHashSet()
        val rest = try { showcase.load(net, pkg) } catch (e: Exception) { if (out.isEmpty()) throw e else emptyList() }
        // After the Top 10: new on Netflix first, then the popular ones, weighted below every Top 10 title.
        rest.filter { seen.add(it.id) }.sortedByDescending { it.kind.startsWith("New on") }
            .forEachIndexed { i, it -> out.add(it.reweighted(0.55 - i * 0.01)) }
        return out
    }

    companion object {
        private const val TAG = "EppleNetflix"
        const val URL = "https://muhibtasam.github.io/vyro/netflix-top10.tsv"

        /** The country's latest week (country_name, country_iso2, week, category, weekly_rank, show_title): movies and shows by rank, interleaved. */
        fun top10(tsv: String, iso2: String): List<Title> {
            val rows = tsv.lineSequence().map { it.split('\t') }
                .filter { it.size >= 6 && it[1].trim().equals(iso2, true) && Regex("\\d{4}-\\d{2}-\\d{2}").matches(it[2].trim()) }.toList()
            val latest = rows.maxOfOrNull { it[2].trim() } ?: return emptyList()
            val titles = rows.filter { it[2].trim() == latest }.mapNotNull { f ->
                val rank = f[4].trim().toIntOrNull() ?: return@mapNotNull null
                val cat = f[3].trim().lowercase(Locale.ROOT)
                val movie = when { cat.startsWith("film") -> true; cat.startsWith("tv") -> false; else -> return@mapNotNull null }
                f[5].trim().takeIf { it.isNotEmpty() }?.let { Title(it, movie, rank) }
            }
            val movies = titles.filter { it.movie }.sortedBy { it.rank }; val shows = titles.filter { !it.movie }.sortedBy { it.rank }
            return (0 until maxOf(movies.size, shows.size)).flatMap { listOfNotNull(movies.getOrNull(it), shows.getOrNull(it)) }
        }
    }
}
