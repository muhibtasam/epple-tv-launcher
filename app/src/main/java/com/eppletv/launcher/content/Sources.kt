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
    val DEFAULT_PINS = listOf(NETFLIX, PRIME, CRUNCHYROLL, YOUTUBE, STREMIO, TAPMAD)
}

object Sources {
    /** Content region: local.properties content_region, else the device's country. */
    /** Settings > Content region; empty = automatic. Set before the sources are built. */
    var override = ""
    val region: String get() = override.ifEmpty { BuildConfig.CONTENT_REGION }.ifEmpty { Locale.getDefault().country }.ifEmpty { "US" }.uppercase(Locale.US)

    /** [debugCinemetaFor]: debug builds only, lets an emulator without Stremio exercise the content pipeline. */
    fun all(debugCinemetaFor: String? = null): List<ContentSource> = listOf(
        Cinemeta(setOfNotNull(Apps.STREMIO, debugCinemetaFor)),
        Showcase(
            mapOf(
                Apps.NETFLIX to Service("Netflix", watchmode = "203", tmdb = "8"),
                Apps.PRIME to Service("Prime Video", watchmode = "26", tmdb = "119|9"),
                Apps.CRUNCHYROLL to Service("Crunchyroll", watchmode = "80,79", tmdb = "283", indian = false),
            ),
            Keys(BuildConfig.TMDB_KEY, BuildConfig.WATCHMODE_KEY, BuildConfig.TRAKT_ID), region,
        ),
        YouTube(BuildConfig.YOUTUBE_KEY, region),
        YouTubeChannel(Apps.TAPMAD, "tapmad", TAPMAD_UPLOADS, BuildConfig.YOUTUBE_KEY),
    )
    val usesTmdb get() = BuildConfig.TMDB_KEY.isNotEmpty()

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
                    logoUrl = m.optString("logo").ifEmpty { "https://images.metahub.space/logo/medium/$id/img" }))
            }
        }
        val movies = out.filter { it.linkUri!!.contains("/movie/") }; val series = out - movies.toSet()
        return (0 until maxOf(movies.size, series.size)).flatMap { listOfNotNull(movies.getOrNull(it), series.getOrNull(it)) }
    }
}

class Keys(val tmdb: String, val watchmode: String, val trakt: String)
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
class Showcase(private val services: Map<String, Service>, private val keys: Keys, private val region: String) : ContentSource {
    override val key = "showcase2"
    override val ready = keys.tmdb.isNotEmpty()
    override fun handles(pkg: String) = pkg in services

    private val v4 = keys.tmdb.length > 40
    private val tmdbHeaders = if (v4) mapOf("Authorization" to "Bearer ${keys.tmdb}") else emptyMap()
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
        val langs = if (svc.indian) listOf("en", INDIAN.joinToString("|")) else listOf("")
        for (r in listOf(region, if (region in SOUTH_ASIA) "IN" else "US", "US").distinct()) {
            var found = 0
            for (lang in langs) for (type in arrayOf("movie", "tv")) try {
                val lq = if (lang.isEmpty()) "" else "&with_original_language=$lang"
                val lk = if (lang.isEmpty()) "all" else if (lang == "en") "en" else "in"
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
            val backdrop = o.optString("backdrop_path"); if (backdrop.isEmpty() || backdrop == "null") continue
            val movie = p.type == "movie"
            val g = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }
                ?: o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { genres[a.getInt(it)] } } ?: emptyList()
            val vote = o.optDouble("vote_average", 0.0)
            val date = o.optString(if (movie) "release_date" else "first_air_date")
            val lang = o.optString("original_language")
            val trend = trending[k]
            val kind = when { p.isNew -> "New on ${svc.name}"; trend != null -> "Trending on ${svc.name}"; else -> "Popular on ${svc.name}" }
            out.add(ContentItem(k, o.optString(if (movie) "title" else "name"),
                join(year(date), if (movie) "Movie" else "Series", if (lang != "en") languageName(lang) else "",
                    g.take(2).joinToString(", "), if (vote > 0) "★ %.1f".format(Locale.US, vote) else ""),
                "https://image.tmdb.org/t/p/original$backdrop", null, null, kind, o.optString("overview"),
                weight = weight(o.optDouble("popularity", 0.0), date, trend), origin = if (lang in INDIAN) "in" else "intl"))
        }
        return out
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
        val m = Regex("^(movie|tv):(\\d+)$").find(item.id) ?: return null
        val (type, id) = m.destructured
        val logos = tmdb(net, "logos-$type-$id", 30 * 24 * HOUR, "$type/$id/images?include_image_language=en,${INDIAN.joinToString(",")},null")
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
class YouTube(private val apiKey: String, private val region: String) : ContentSource {
    override val key = "youtube2"
    override val ready = apiKey.isNotEmpty()
    override fun handles(pkg: String) = pkg == Apps.YOUTUBE
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val items = JSONObject(net.cached("yt-popular-$region", 3 * HOUR,
            "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&maxResults=25&regionCode=$region&key=$apiKey")).getJSONArray("items")
        return youtubeItems(items, "Trending on YouTube") { it.getString("id") }
    }
}

/** Latest uploads of an app's official YouTube channel (legitimate public source for apps with no catalogue API). */
class YouTubeChannel(private val pkg: String, private val name: String, private val uploads: String, private val apiKey: String) : ContentSource {
    override val key = "ytchannel2"
    override val ready = apiKey.isNotEmpty()
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
