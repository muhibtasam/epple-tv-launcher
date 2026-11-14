package com.eppletv.launcher.content

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import java.net.URLDecoder
import java.util.Locale

/**
 * What the streaming apps publish to Android TV themselves (TvProvider, READ_TV_LISTINGS): their home-screen channels
 * (Prime Video's own rows, the ones Google TV shows) and the system Watch Next list (what you left half-way, in any app).
 * The same reading the earlier VYRO launcher proved on this TV. Worker threads only: these are content provider queries.
 */
object TvListings {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    class Program(
        val pkg: String, val channel: String?, val title: String, val art: String?, val poster: String?, val link: String?,
        val type: Int, val watchNext: Boolean, val episode: String?, val description: String?, val year: String?,
        val position: Long = 0, val duration: Long = 0, val engaged: Long = 0,
    ) {
        /** 0..1, or -1 when there is no position worth showing. */
        val progress: Float get() = if (duration <= 0 || position < 1000 || position >= duration) -1f else position.toFloat() / duration
        val isMovie get() = type == TvContract.PreviewPrograms.TYPE_MOVIE
    }

    fun allowed(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private val CONTINUE_NAMES = setOf("continue watching", "seguir viendo", "continuar viendo", "continuar assistindo", "continue assistindo")
    fun isContinueChannel(name: String?) = name != null && name.trim().lowercase(Locale.ROOT) in CONTINUE_NAMES

    private val PROGRAM = arrayOf("package_name", "channel_id", "title", "type", "poster_art_uri", "thumbnail_uri", "intent_uri",
        "short_description", "release_date", "episode_display_number", "browsable")
    private val WATCH_NEXT = arrayOf("package_name", "title", "type", "poster_art_uri", "thumbnail_uri", "intent_uri", "short_description",
        "release_date", "episode_display_number", "last_playback_position_millis", "duration_millis", "last_engagement_time_utc_millis", "browsable")

    /** Everything readable. Throws when the provider can't be read (callers keep what they had). */
    fun read(c: Context, watchNext: Boolean = true, previews: Boolean = true): List<Program> {
        if (!allowed(c)) throw IllegalStateException("TV listings not allowed")
        val cr = c.contentResolver
        val channels = HashMap<Long, Pair<String, String>>()
        cr.query(TvContract.Channels.CONTENT_URI, arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE), null, null, null)?.use { cur ->
            while (cur.moveToNext()) if (cur.getString(3) == TvContract.Channels.TYPE_PREVIEW)
                channels[cur.getLong(0)] = (cur.getString(1) ?: "") to (cur.getString(2) ?: "")
        } ?: throw IllegalStateException("no channels")
        val out = ArrayList<Program>()
        if (previews) rows(c, TvContract.PreviewPrograms.CONTENT_URI, PROGRAM) { r ->
            if (r["browsable"] == "0") return@rows
            val ch = r["channel_id"]?.toLongOrNull()?.let { channels[it] }
            val pkg = r["package_name"] ?: ch?.first ?: return@rows
            out += Program(pkg, ch?.second, r["title"] ?: return@rows, r["thumbnail_uri"] ?: r["poster_art_uri"], r["poster_art_uri"],
                r["intent_uri"], r["type"]?.toIntOrNull() ?: -1, false, r["episode_display_number"], r["short_description"], year(r["release_date"]))
        }
        if (watchNext) rows(c, TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT) { r ->
            if (r["browsable"] == "0") return@rows
            out += Program(r["package_name"] ?: return@rows, "Watch Next", r["title"] ?: return@rows, r["thumbnail_uri"] ?: r["poster_art_uri"],
                r["poster_art_uri"], r["intent_uri"], r["type"]?.toIntOrNull() ?: -1, true, r["episode_display_number"], r["short_description"],
                year(r["release_date"]), num(r["last_playback_position_millis"]), num(r["duration_millis"]), num(r["last_engagement_time_utc_millis"]))
        }
        Log.i(TAG, "read ${out.size} programs from ${out.map { it.pkg }.distinct().size} apps")
        return out
    }

    private fun rows(c: Context, uri: Uri, cols: Array<String>, each: (Map<String, String?>) -> Unit) {
        c.contentResolver.query(uri, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) each(cols.indices.associate { i -> cols[i] to (if (cur.isNull(i)) null else cur.getString(i)?.trim()?.ifEmpty { null }) })
        } ?: throw IllegalStateException("no cursor for $uri")
    }
    private fun num(s: String?) = s?.toLongOrNull() ?: 0L
    private fun year(s: String?) = s?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

    // Prime Video draws a badge into its artwork through the image address: "._PI…badge…_._ZA<words>,…_".
    private val BADGE = Regex("\\._PI[^_/]*badge[^_/]*_\\._ZA([^,_/]*)[^_/]*_")

    /** Prime Video's art without its drawn-in badge ("NEW MOVIE", "TOP 10"), and the badge's words ("" when none). */
    fun cleanArt(art: String): Pair<String, String> {
        val m = BADGE.find(art) ?: return art to ""
        var words = m.groupValues[1]
        try { repeat(3) { if ('%' in words) words = URLDecoder.decode(words, "UTF-8") } } catch (_: Exception) { words = "" }
        words = words.replace('+', ' ').trim().uppercase(Locale.ROOT)
        if (!words.matches(Regex("[A-Z0-9 ]{2,20}"))) words = ""
        return art.substring(0, m.range.first) + "._" + art.substring(m.range.last + 1) to words
    }

    /**
     * May [link] be opened for [pkg]? Only links that stay inside the app that published them: an intent: link naming
     * another package or component, or carrying a selector, and file/content/javascript/data links are refused.
     */
    fun linkAllowed(link: String?, pkg: String): Boolean {
        val l = link?.trim() ?: return false
        if (l.isEmpty()) return false
        val lower = l.lowercase(Locale.ROOT)
        if (listOf("file:", "content:", "javascript:", "data:").any { lower.startsWith(it) }) return false
        val hash = l.indexOf("#Intent;")
        if (lower.startsWith("intent:") || hash >= 0) {
            if (hash < 0) return false
            val body = l.substring(hash + "#Intent;".length)
            if ("SEL;" in body || (!body.endsWith("end") && ";end" !in body)) return false
            for (part in body.split(';')) {
                if (part.startsWith("package=") && part.removePrefix("package=") != pkg) return false
                if (part.startsWith("component=") && part.removePrefix("component=").substringBefore('/') != pkg) return false
            }
            return true
        }
        return l.indexOf(':') > 0
    }

    /** The intent for an app's own link, kept inside that app, or null when refused or nothing opens it. */
    fun intent(c: Context, link: String, pkg: String): Intent? {
        if (!linkAllowed(link, pkg)) return null
        val i = try {
            if (link.startsWith("intent:") || "#Intent;" in link) Intent.parseUri(link, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(link))
        } catch (_: Exception) { return null }
        i.selector = null
        if (i.component != null && i.component!!.packageName != pkg) return null
        if (i.`package` != null && i.`package` != pkg) return null
        if (i.component == null) i.setPackage(pkg)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK                      // the app's own flags but never permission grants
        return try { if (c.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) null else i } catch (_: RuntimeException) { null }
    }

    private const val TAG = "EppleListings"
}

/**
 * An app's own home-screen rows (Prime Video's, as Google TV shows them): titles, artwork without the drawn-in badge,
 * the badge as the eyebrow ("New movie on Prime Video") and the app's own link to the title. Ready only once the
 * TV allows Epple TV to read TV listings; until then the [Showcase] serves the app.
 */
class AppChannels(private val ctx: Context, private val names: Map<String, String>) : ContentSource {
    override val key = "channels1"
    override val ordered = true                                   // the app's own order
    override val ready get() = TvListings.allowed(ctx)
    override fun handles(pkg: String) = pkg in names
    override fun load(net: Net, pkg: String): List<ContentItem> {
        val name = names.getValue(pkg)
        val seen = HashSet<String>()
        val rows = TvListings.read(ctx, watchNext = false).filter { p ->
            p.pkg == pkg && !TvListings.isContinueChannel(p.channel) && p.art?.startsWith("https://") == true &&
                TvListings.linkAllowed(p.link, pkg) && seen.add(p.title.lowercase(Locale.ROOT).replace(Regex("\\s+"), " "))
        }
        return rows.mapIndexed { i, p ->
            val (art, badge) = TvListings.cleanArt(p.art!!)
            val poster = p.poster?.takeIf { it.startsWith("https://") && it != p.art }?.let { TvListings.cleanArt(it).first } ?: ""
            val kind = when {
                badge.isEmpty() -> "On $name"
                badge.startsWith("TOP 10") -> "Top 10 on $name"
                else -> badge.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } + " on $name"
            }
            val type = if (p.isMovie) "Movie" else if (p.type < 0) "" else "Series"
            ContentItem("app:$pkg:${p.title}", p.title, listOfNotNull(p.year, type.ifEmpty { null }).joinToString("  ·  "), art,
                p.link, pkg, kind, p.description ?: "", weight = 1.0 - i * 0.02, origin = "app", posterUrl = poster)
        }
    }
}
