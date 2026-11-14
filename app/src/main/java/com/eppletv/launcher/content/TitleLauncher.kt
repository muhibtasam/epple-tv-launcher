package com.eppletv.launcher.content

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Every rule for opening a title inside a streaming app lives here (the UI only calls [open]).
 *
 * Link forms, each tested on the TCL C6K against the app's own declared intent filters
 * (link rules for Netflix and Prime Video carried over from the earlier VYRO launcher, where they were proven on this TV):
 *  - Netflix      https://www.netflix.com/title/<id>, extra source=30, fresh task (a running Netflix otherwise stays on its home)
 *  - Prime Video  https://app.primevideo.com/detail?gti=<gti>, fresh task (Watchmode's amazon.com/watch.amazon.* links are rewritten)
 *  - Crunchyroll  crunchyroll://episode/<id> or crunchyroll://series/<id> (the TV app declares only its own scheme)
 *  - YouTube      https://www.youtube.com/watch?v=<id>
 *  - Stremio      stremio:///detail/<type>/<id>[/<id>]
 *  - Tapmad       declares no links: its cards are its official YouTube videos and open in YouTube; the app itself otherwise.
 * Netflix, Prime Video and Crunchyroll title ids come from Watchmode (subscription/free sources only, never rent or buy),
 * looked up when OK is pressed (2 calls, cached for good; titles with no link are asked again after 12 h).
 *
 * Validated, not assumed: an intent is used only if it is restricted to the intended package AND that installed package
 * declares an activity for it. Otherwise the app itself opens (never a browser or store).
 */
class TitleLauncher(ctx: Context, private val net: Net, private val watchmodeKey_: () -> String, private val region: () -> String) {
    private val watchmodeKey get() = watchmodeKey_()
    private val app = ctx.applicationContext
    private val pm = app.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("title-links", Context.MODE_PRIVATE)
    private var ioUsed = false
    private val io by lazy { ioUsed = true; Executors.newSingleThreadExecutor { r -> Thread(r, "epple-links").apply { priority = Thread.NORM_PRIORITY - 1 } } }
    private val regions get() = listOf(region(), "IN", "US")          // Watchmode has no PK; India's links use the same title ids
    private var busy = false
    @Volatile private var pauseUntil = 0L

    private class Provider(
        val pkg: String,
        val watchmodeName: Regex?,                   // Watchmode source names that belong to this app
        val toAppLink: (String) -> String?,          // Watchmode web link -> link the TV app opens (null = unusable)
        val freshTask: Boolean = false,
        val extras: Map<String, String> = emptyMap(),
    )

    private val providers = listOf(
        Provider(Apps.NETFLIX, Regex("^netflix$", RegexOption.IGNORE_CASE), { u ->
            Regex("netflix\\.com/(?:[a-z-]+/)?title/(\\d+)").find(u)?.let { "https://www.netflix.com/title/${it.groupValues[1]}" }
        }, freshTask = true, extras = mapOf("source" to "30")),
        Provider(Apps.PRIME, Regex("^(prime video|amazon prime video)$", RegexOption.IGNORE_CASE), { u ->
            Regex("(amzn1\\.dv\\.gti\\.[0-9a-fA-F-]+)").find(u)?.let { "https://app.primevideo.com/detail?gti=${it.groupValues[1]}" }
        }, freshTask = true),
        Provider(Apps.CRUNCHYROLL, Regex("^crunchyroll( premium)?$", RegexOption.IGNORE_CASE), { u ->
            Regex("crunchyroll\\.com/(?:[a-z-]+/)?(watch|series)/([A-Z0-9]+)").find(u)?.let {
                "crunchyroll://${if (it.groupValues[1] == "watch") "episode" else "series"}/${it.groupValues[2]}"
            }
        }),
        Provider(Apps.APPLE_TV, Regex("^apple tv\\+?$", RegexOption.IGNORE_CASE), { u ->
            Regex("https://tv\\.apple\\.com/[^?#]+").find(u)?.value                     // the app declares tv.apple.com links
        }, freshTask = true),
        Provider(Apps.YOUTUBE, null, { it }),
        Provider(Apps.STREMIO, null, { it }),
        Provider(Apps.TAPMAD, null, { null }),
    ).associateBy { it.pkg }

    /**
     * Opens [item] (shown for [appPkg]) at the title when possible, else the app ([openApp]). Main thread.
     * Returns immediately; a Watchmode lookup, when needed, finishes in the background.
     */
    fun open(item: ContentItem?, appPkg: String, openApp: () -> Unit) {
        if (busy) return
        if (item == null) return openApp()
        Recency.opened(app, appPkg, item.title)                // what was opened, for Continue Watching's order
        // 1. The item carries its own link (YouTube, Tapmad's YouTube videos, Stremio).
        item.linkUri?.let { uri -> return if (start(item.linkPkg ?: appPkg, uri)) Unit else openApp() }
        // 2. A TMDB title on a service Watchmode knows: look its link up (cached).
        val p = providers[appPkg]
        val m = Regex("^(movie|tv):(\\d+)$").find(item.id)
        if (p?.watchmodeName == null || m == null || watchmodeKey.isEmpty()) return openApp()
        val key = "${m.value}|$appPkg"
        cached(key)?.let { url -> return if (url.isNotEmpty() && start(appPkg, url)) Unit else openApp() }
        if (System.currentTimeMillis() < pauseUntil) return openApp()
        busy = true
        io.execute {
            val url = try { lookUp(m.groupValues[1], m.groupValues[2], p) } catch (e: Exception) {
                Log.w(TAG, "lookup failed: ${e.javaClass.simpleName} ${e.message}"); pauseUntil = System.currentTimeMillis() + 10 * 60_000L; null
            }
            if (url != null) prefs.edit().putString(key, JSONObject().put("url", url).put("at", System.currentTimeMillis()).toString()).apply()
            main.post { busy = false; if (url.isNullOrEmpty() || !start(appPkg, url)) openApp() }
        }
    }

    private fun cached(key: String): String? = prefs.getString(key, null)?.let {
        try {
            val o = JSONObject(it); val url = o.getString("url")
            if (url.isEmpty() && System.currentTimeMillis() - o.getLong("at") > 12 * 3600_000L) null else url
        } catch (_: Exception) { null }
    }

    /** Watchmode: TMDB id -> Watchmode id -> sources. The first usable link for this app, our regions first. "" = none. */
    private fun lookUp(kind: String, tmdbId: String, p: Provider): String {
        val found = JSONObject(net.text("https://api.watchmode.com/v1/search/?search_field=tmdb_${kind}_id&search_value=$tmdbId&apiKey=$watchmodeKey"))
            .optJSONArray("title_results")
        if (found == null || found.length() == 0) return ""
        val sources = JSONArray(net.text("https://api.watchmode.com/v1/title/${found.getJSONObject(0).getInt("id")}/sources/?apiKey=$watchmodeKey"))
        val usable = (0 until sources.length()).map { sources.getJSONObject(it) }
            .filter { it.optString("type") in setOf("sub", "free") && p.watchmodeName!!.matches(it.optString("name").trim()) }
            .mapNotNull { s -> p.toAppLink(s.optString("web_url"))?.let { s.optString("region") to it } }
        return usable.sortedBy { (r, _) -> regions.indexOf(r).let { i -> if (i < 0) regions.size else i } }.firstOrNull()?.second ?: ""
    }

    /** Starts [uri] in [pkg] only if that installed package declares an activity for it. */
    private fun start(pkg: String, uri: String): Boolean {
        // An app's own link from its Android TV row (Prime Video's intent: links): kept inside that app (TvListings.intent).
        if (uri.startsWith("intent:") || "#Intent;" in uri) {
            val i = TvListings.intent(app, uri, pkg) ?: run { Log.i(TAG, "$pkg row link refused"); return false }
            return try { app.startActivity(i); Log.i(TAG, "opened row title in $pkg"); true } catch (e: Exception) { false }
        }
        val p = providers[pkg]
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (p?.freshTask == true) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        p?.extras?.forEach { (k, v) -> intent.putExtra(k, v) }
        val ok = try { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() } catch (_: RuntimeException) { false }
        if (!ok) { Log.i(TAG, "$pkg does not open ${uri.substringBefore('?')}"); return false }
        return try { app.startActivity(intent); Log.i(TAG, "opened title in $pkg"); true } catch (e: Exception) { false }
    }

    fun stop() { if (ioUsed) io.shutdown() }

    private companion object { const val TAG = "EppleLaunch" }
}
