package com.eppletv.launcher.content

import com.eppletv.launcher.BuildConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Apps {
    const val NETFLIX = "com.netflix.ninja"
    const val PRIME = "com.amazon.amazonvideo.livingroom"
    const val CRUNCHYROLL = "com.crunchyroll.crunchyroid"
    const val YOUTUBE = "com.google.android.youtube.tv"
    const val STREMIO = "com.stremio.one"
    val DEFAULT_PINS = listOf(NETFLIX, PRIME, CRUNCHYROLL, YOUTUBE, STREMIO)
}

object Sources {
    /** [debugCinemetaFor]: debug builds only, lets an emulator without Stremio exercise the content pipeline. */
    fun all(debugCinemetaFor: String? = null): List<ContentSource> = listOf(
        Cinemeta(setOfNotNull(Apps.STREMIO, debugCinemetaFor)),
        Tmdb(mapOf(Apps.NETFLIX to 8, Apps.PRIME to 9, Apps.CRUNCHYROLL to 283), BuildConfig.TMDB_KEY),
        YouTube(BuildConfig.YOUTUBE_KEY),
    )
    val usesTmdb get() = BuildConfig.TMDB_KEY.isNotEmpty()
}

private fun year(date: String) = date.take(4)
private fun join(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString("  ·  ")

/** Stremio's official catalogue add-on (public add-on protocol, no key). Titles open inside Stremio via its documented deep links. */
class Cinemeta(private val pkgs: Set<String>) : ContentSource {
    override val key = "cinemeta"
    override val ready = true
    override fun handles(pkg: String) = pkg in pkgs
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val out = ArrayList<ContentItem>()
        for (type in arrayOf("movie", "series")) {
            val metas = net.json("https://v3-cinemeta.strem.io/catalog/$type/top.json").getJSONArray("metas")
            for (i in 0 until minOf(6, metas.length())) {
                val m = metas.getJSONObject(i)
                val id = m.optString("id").ifEmpty { m.optString("imdb_id") }
                if (!id.startsWith("tt")) continue
                val genres = m.optJSONArray("genre")?.let { g -> (0 until minOf(2, g.length())).joinToString(", ") { g.getString(it) } } ?: ""
                val rating = m.optString("imdbRating").let { if (it.isEmpty() || it == "null") "" else "IMDb $it" }
                val link = if (type == "movie") "stremio:///detail/movie/$id/$id" else "stremio:///detail/series/$id"
                out.add(ContentItem(id, m.optString("name"), join(m.optString("releaseInfo").ifEmpty { year(m.optString("released")) },
                    if (type == "movie") "Movie" else "Series", genres, rating),
                    "https://images.metahub.space/background/large/$id/img", link, Apps.STREMIO))
            }
        }
        // Interleave movies and series so the first few items show both.
        val movies = out.filter { it.linkUri!!.contains("/movie/") }; val series = out - movies.toSet()
        return (0 until maxOf(movies.size, series.size)).flatMap { listOfNotNull(movies.getOrNull(it), series.getOrNull(it)) }
    }
}

/**
 * TMDB (themoviedb.org) "discover" filtered by streaming provider and region: what recently arrived or aired on that service.
 * Needs a free TMDB API key (tmdb.key in local.properties). TMDB has no title links into the apps, so OK opens the app itself.
 */
class Tmdb(private val providers: Map<String, Int>, private val apiKey: String) : ContentSource {
    override val key = "tmdb"
    override val ready = apiKey.isNotEmpty()
    override fun handles(pkg: String) = pkg in providers
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val region = Locale.getDefault().country.ifEmpty { "US" }
        return fetch(net, pkg, region).ifEmpty { if (region != "US") fetch(net, pkg, "US") else emptyList() }
    }

    private fun fetch(net: Net, pkg: String, region: String): List<ContentItem> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val to = fmt.format(Calendar.getInstance().time)
        val from = fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -120) }.time)
        val v4 = apiKey.length > 40   // a v4 read token goes in a header, a v3 key in the query
        val common = "watch_region=$region&with_watch_providers=${providers[pkg]}&with_watch_monetization_types=flatrate" +
            "&sort_by=popularity.desc&include_adult=false&language=en-US" + if (v4) "" else "&api_key=$apiKey"
        val out = ArrayList<Pair<Double, ContentItem>>()
        for ((type, window) in arrayOf("movie" to "primary_release_date.gte=$from&primary_release_date.lte=$to",
            "tv" to "air_date.gte=$from&air_date.lte=$to")) {
            val r = net.json("https://api.themoviedb.org/3/discover/$type?$common&$window", if (v4) apiKey else null).getJSONArray("results")
            for (i in 0 until minOf(8, r.length())) {
                val o: JSONObject = r.getJSONObject(i)
                val img = o.optString("backdrop_path"); if (img.isEmpty() || img == "null") continue
                val title = o.optString(if (type == "movie") "title" else "name")
                val date = o.optString(if (type == "movie") "release_date" else "first_air_date")
                val vote = o.optDouble("vote_average", 0.0)
                out.add(o.optDouble("popularity", 0.0) to ContentItem("$type${o.optInt("id")}", title,
                    join(year(date), if (type == "movie") "Movie" else "Series", if (vote > 0) "★ %.1f".format(Locale.US, vote) else ""),
                    "https://image.tmdb.org/t/p/original$img", null, null))
            }
        }
        return out.sortedByDescending { it.first }.take(10).map { it.second }
    }
}

/** YouTube Data API v3, most popular videos in the region. Needs an API key (youtube.key in local.properties). */
class YouTube(private val apiKey: String) : ContentSource {
    override val key = "youtube"
    override val ready = apiKey.isNotEmpty()
    override fun handles(pkg: String) = pkg == Apps.YOUTUBE
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val region = Locale.getDefault().country.ifEmpty { "US" }
        val items = net.json("https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&maxResults=20&regionCode=$region&key=$apiKey").getJSONArray("items")
        val out = ArrayList<ContentItem>()
        for (i in 0 until items.length()) {
            val v = items.getJSONObject(i); val s = v.getJSONObject("snippet")
            val img = s.getJSONObject("thumbnails").optJSONObject("maxres")?.optString("url") ?: continue   // only true 16:9 HD artwork
            out.add(ContentItem(v.getString("id"), s.optString("title"), join(s.optString("channelTitle"), year(s.optString("publishedAt"))),
                img, "https://www.youtube.com/watch?v=${v.getString("id")}", Apps.YOUTUBE))
            if (out.size == 10) break
        }
        return out
    }
}
