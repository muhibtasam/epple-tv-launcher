package com.eppletv.launcher.content

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln

/** A row of a browse screen: its title and the TMDB path that fills it. */
class RowSpec(val title: String, val path: String, val ttlH: Int = 12)

/** One provider a title streams on in the user's region; [pkg] is the app that plays it, when known. */
class Provider(val id: Int, val name: String, val pkg: String?)

class Season(val number: Int, val name: String, val episodes: Int, val year: String)

/** Everything a details page shows. [item] is the canonical ContentItem ("movie:123" / "tv:123"). */
class Details(
    val item: ContentItem, val type: String, val tmdbId: Int, val tagline: String, val meta: String,
    val providers: List<Provider>, val imdb: String, val seasons: List<Season>, val more: List<ContentItem>,
)

/**
 * The TMDB catalogue behind Movies, TV Shows, Search and the details pages. Worker thread only.
 * Every request goes through [Net.cached] (disk cache with TTL, stale copy on failure, per-key de-duplication),
 * using the same cache keys as the home showcase where the requests are the same, so nothing is fetched twice.
 */
/** [key_] is read on every request, so a TMDB key changed in Settings applies at once. */
class Catalog(private val net: Net, private val key_: () -> String, private val region: () -> String) {
    private val key get() = key_()
    val ready get() = key.isNotEmpty()
    private val v4 get() = key.length > 40
    private val headers get() = if (v4) mapOf("Authorization" to "Bearer $key") else emptyMap()
    private fun url(path: String) = "https://api.themoviedb.org/3/$path${if ('?' in path) "&" else "?"}language=en-US" + if (v4) "" else "&api_key=$key"
    private fun get(cacheKey: String, ttlH: Int, path: String) = JSONObject(net.cached("tmdb-$cacheKey", ttlH * HOUR, url(path), headers))

    fun movieRows(): List<RowSpec> = listOf(
        RowSpec("Trending Movies", "trending/movie/week", 6),
        RowSpec("Latest in Cinemas", "movie/now_playing?region=IN"),
        RowSpec("Popular in India", "discover/movie?with_original_language=${indianQ()}&sort_by=popularity.desc&vote_count.gte=20"),
        RowSpec("Popular Worldwide", "movie/popular"),
        RowSpec("Top Rated", "movie/top_rated", 48),
    ).filter { !it.path.contains("with_original_language=&") } + listOf(28 to "Action", 35 to "Comedy", 53 to "Thriller", 18 to "Drama", 16 to "Animation", 10749 to "Romance", 27 to "Horror", 878 to "Science Fiction")
        .map { (g, n) -> RowSpec(n, "discover/movie?with_genres=$g&sort_by=popularity.desc&vote_count.gte=100", 24) }

    fun tvRows(): List<RowSpec> = listOf(
        RowSpec("Trending Series", "trending/tv/week", 6),
        RowSpec("New Episodes", "tv/on_the_air"),
        RowSpec("Indian Series", "discover/tv?with_original_language=${indianQ()}&sort_by=popularity.desc&vote_count.gte=5"),
        RowSpec("Popular Worldwide", "tv/popular"),
        RowSpec("Top Rated", "tv/top_rated", 48),
    ).filter { !it.path.contains("with_original_language=&") } + listOf(18 to "Drama", 80 to "Crime", 35 to "Comedy", 10765 to "Sci-Fi & Fantasy", 16 to "Animation", 9648 to "Mystery", 10759 to "Action & Adventure", 99 to "Documentary")
        .map { (g, n) -> RowSpec(n, "discover/tv?with_genres=$g&sort_by=popularity.desc&vote_count.gte=50", 24) }

    val trendingAll = RowSpec("Trending Now", "trending/all/week", 6)

    /** Items of one row (backdrop required: every card is landscape artwork). */
    fun row(spec: RowSpec): List<ContentItem> {
        val res = get("row-" + spec.path.replace(Regex("[^A-Za-z0-9]+"), "_"), spec.ttlH, spec.path).optJSONArray("results") ?: JSONArray()
        val type = when { spec.path.contains("/movie") || spec.path.startsWith("movie") -> "movie"; spec.path.contains("/tv") || spec.path.startsWith("tv") -> "tv"; else -> "" }
        return items(res, type)
    }

    fun recommendations(type: String, id: Int): List<ContentItem> =
        items(get("recs-$type-$id", 72, "$type/$id/recommendations").optJSONArray("results") ?: JSONArray(), type)

    /** Movies and series matching [q] (one request; people are skipped). */
    fun search(q: String): Pair<List<ContentItem>, List<ContentItem>> {
        val norm = q.trim().lowercase(Locale.US)
        val res = get("search-" + norm.replace(Regex("[^a-z0-9]+"), "_"), 24, "search/multi?include_adult=false&query=" + java.net.URLEncoder.encode(norm, "UTF-8"))
            .optJSONArray("results") ?: JSONArray()
        val all = items(res, "")
        return all.filter { it.id.startsWith("movie:") } to all.filter { it.id.startsWith("tv:") }
    }

    fun details(type: String, id: Int): Details {
        val o = get("details-$type-$id", 72, "$type/$id?append_to_response=watch/providers,external_ids,recommendations")
        val item = toItem(o, type) ?: ContentItem("$type:$id", o.optString(if (type == "movie") "title" else "name"), "", "", null, null)
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList()
        val runtime = if (type == "movie") o.optInt("runtime").let { if (it > 0) "${it / 60}h ${it % 60}m" else "" }
                      else o.optInt("number_of_seasons").let { if (it > 0) "$it Season${if (it > 1) "s" else ""}" else "" }
        val lang = o.optString("original_language")
        val vote = o.optDouble("vote_average", 0.0)
        val meta = join(year(o.optString(if (type == "movie") "release_date" else "first_air_date")), if (type == "movie") "Movie" else "Series",
            runtime, if (lang != "en") languageName(lang) else "", genres.take(3).joinToString(", "), if (vote > 0) "★ %.1f".format(Locale.US, vote) else "")
        val providers = providers(o.optJSONObject("watch/providers")?.optJSONObject("results"))
        val imdb = o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") } ?: o.optString("imdb_id").takeIf { it.startsWith("tt") } ?: ""
        val seasons = o.optJSONArray("seasons")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optInt("season_number") > 0 && it.optInt("episode_count") > 0 }
            .map { Season(it.optInt("season_number"), it.optString("name"), it.optInt("episode_count"), year(it.optString("air_date"))) } } ?: emptyList()
        val more = items(o.optJSONObject("recommendations")?.optJSONArray("results") ?: JSONArray(), type)
        return Details(item, type, id, o.optString("tagline"), meta, providers, imdb, seasons, more)
    }

    /** Episodes of one season as items "ep:<tv>:<s>:<e>" with their still frames. */
    fun season(tvId: Int, n: Int): List<ContentItem> {
        val eps = get("season-$tvId-$n", 24, "tv/$tvId/season/$n").optJSONArray("episodes") ?: JSONArray()
        return (0 until eps.length()).map { eps.getJSONObject(it) }.map { e ->
            val num = e.optInt("episode_number"); val still = e.optString("still_path")
            ContentItem("ep:$tvId:$n:$num", "$num. ${e.optString("name")}",
                join("S$n E$num", e.optString("air_date"), e.optInt("runtime").let { if (it > 0) "${it}m" else "" }),
                if (still.isEmpty() || still == "null") "" else "https://image.tmdb.org/t/p/original$still", null, null, "Season $n", e.optString("overview"))
        }
    }

    private fun providers(results: JSONObject?): List<Provider> {
        if (results == null) return emptyList()
        val r = listOf(region(), "IN", "US").firstOrNull { results.has(it) } ?: return emptyList()
        val o = results.getJSONObject(r)
        val out = LinkedHashMap<Int, Provider>()
        for (kind in arrayOf("flatrate", "free", "ads")) {
            val a = o.optJSONArray(kind) ?: continue
            for (i in 0 until a.length()) { val p = a.getJSONObject(i); val id = p.optInt("provider_id")
                out.putIfAbsent(id, Provider(id, p.optString("provider_name"), PROVIDER_APPS[id])) }
        }
        return out.values.toList()
    }

    private fun items(a: JSONArray, type: String): List<ContentItem> {
        val out = ArrayList<ContentItem>()
        for (i in 0 until a.length()) toItem(a.getJSONObject(i), type)?.let { out.add(it) }
        return out
    }

    private fun toItem(o: JSONObject, typeHint: String): ContentItem? {
        val type = o.optString("media_type").ifEmpty { typeHint }
        if (type != "movie" && type != "tv") return null
        val backdrop = o.optString("backdrop_path"); if (backdrop.isEmpty() || backdrop == "null") return null
        val movie = type == "movie"
        val genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { genreNames()[a.getInt(it)] } }
            ?: o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList()
        val lang = o.optString("original_language"); val vote = o.optDouble("vote_average", 0.0)
        val date = o.optString(if (movie) "release_date" else "first_air_date")
        return ContentItem("$type:${o.optInt("id")}", o.optString(if (movie) "title" else "name"),
            join(year(date), if (movie) "Movie" else "Series", if (lang != "en" && lang.isNotEmpty()) languageName(lang) else "",
                genres.take(2).joinToString(", "), if (vote > 0) "★ %.1f".format(Locale.US, vote) else ""),
            "https://image.tmdb.org/t/p/original$backdrop", null, null, "", o.optString("overview"),
            weight = 0.15 + 0.85 * (ln(1 + o.optDouble("popularity", 0.0)) / ln(401.0)).coerceIn(0.0, 1.0),
            origin = if (lang in INDIAN) "in" else "intl")
    }

    @Volatile private var genres: Map<Int, String>? = null
    private fun genreNames(): Map<Int, String> = genres ?: HashMap<Int, String>().also { m ->
        for (t in arrayOf("movie", "tv")) try {      // same cache keys as the showcase: fetched once for both
            val a = get("genres-$t", 30 * 24, "genre/$t/list").getJSONArray("genres")
            for (i in 0 until a.length()) a.getJSONObject(i).let { m[it.getInt("id")] = it.getString("name") }
        } catch (_: Exception) {}
        if (m.isNotEmpty()) genres = m
    }

    companion object {
        private const val HOUR = 60 * 60 * 1000L
        val INDIAN = listOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa")
        /** The Indian languages chosen in Settings (empty: Indian rows are left out). */
        private fun indianQ() = Sources.indianLangs.joinToString("|")
        /** TMDB provider id -> the app on this TV that plays it. */
        val PROVIDER_APPS = mapOf(8 to Apps.NETFLIX, 1796 to Apps.NETFLIX, 119 to Apps.PRIME, 9 to Apps.PRIME, 10 to Apps.PRIME,
            283 to Apps.CRUNCHYROLL, 192 to Apps.YOUTUBE)
        private fun year(d: String) = d.take(4)
        private fun join(vararg p: String) = p.filter { it.isNotBlank() }.joinToString("  ·  ")
        fun languageName(code: String) = if (code.isEmpty()) "" else Locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it != code } ?: ""
        /** Card-sized artwork for an item image (TMDB w780 instead of the original). */
        fun cardUrl(url: String) = url.replace("/t/p/original/", "/t/p/w780/")
    }
}
