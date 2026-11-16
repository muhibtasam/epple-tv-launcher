package com.eppletv.launcher.home

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.eppletv.launcher.content.Catalog
import com.eppletv.launcher.content.ContentRepository
import com.eppletv.launcher.content.UserLibrary
import com.eppletv.launcher.system.NotificationHub
import com.eppletv.launcher.system.NotificationHub.Note
import org.json.JSONObject

/**
 * Epple TV's own notifications: Wi-Fi going away and coming back, a new Epple TV release, and new seasons or episodes
 * of followed shows. No polling: Wi-Fi is a system callback, the other two are checked at most once a day when Home
 * comes to the front (and TMDB answers are cached).
 */
object Watchers {
    private const val TAG = "EppleNotes"
    /** Where Epple TV's releases are published (GitHub Releases, read without a token: the repository must be public). */
    const val REPO = "muhibtasam/epple-tv-launcher"
    private const val DAY = 24 * 3600_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private const val HOUR = 3600_000L
    /**
     * A daily check is due when the last one that succeeded is a day old. A start is remembered too: a check is never
     * run twice at once, and one that failed (offline, TMDB or GitHub down) is tried again an hour later, not on every
     * return to Home.
     */
    private fun due(c: Context, k: String): Boolean {
        val p = prefs(c); val now = System.currentTimeMillis()
        if (now - p.getLong(k, 0) < DAY || now - p.getLong("$k:try", 0) < HOUR) return false
        p.edit().putLong("$k:try", now).apply(); return true
    }
    private fun done(c: Context, k: String) = prefs(c).edit().putLong(k, System.currentTimeMillis()).apply()

    // ---- Wi-Fi ----
    private var wifiOn = false
    private var downAt = 0L
    private var told = false

    /** Once per process. A drop shorter than 20 seconds (an unstable network) says nothing; one note replaces the last. */
    fun startWifi(c: Context, prefs: Prefs) {
        if (wifiOn) return
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        wifiOn = true
        val report = Runnable {
            if (downAt == 0L || prefs.get(Prefs.NOTIFY_WIFI) != 0) return@Runnable
            told = true
            NotificationHub.post(Note("wifi", "", "Wi-Fi", "Wi-Fi disconnected", "The TV is offline. Online rows come back when it reconnects.",
                System.currentTimeMillis(), null, null, NotificationHub.SYSTEM, action = "wifi", own = true))
        }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(n: Network) { main.post { if (downAt == 0L) { downAt = System.currentTimeMillis(); main.postDelayed(report, 20_000) } } }
            override fun onAvailable(n: Network) {
                main.post {
                    main.removeCallbacks(report)
                    if (downAt > 0 && told && prefs.get(Prefs.NOTIFY_WIFI) == 0) {
                        val s = (System.currentTimeMillis() - downAt) / 1000
                        NotificationHub.post(Note("wifi", "", "Wi-Fi", "Wi-Fi reconnected", "Back online after ${if (s < 120) "$s seconds" else "${s / 60} minutes"}.",
                            System.currentTimeMillis(), null, null, NotificationHub.SYSTEM, action = "wifi", own = true))
                    }
                    downAt = 0L; told = false
                }
            }
        }, main)
    }

    // ---- Epple TV updates ----

    /** At most once a day: the latest GitHub release, if newer than this build, as one note per version. */
    fun checkUpdate(c: Context, content: ContentRepository, prefs: Prefs, current: String) {
        clearInstalled(c, current)
        if (prefs.get(Prefs.NOTIFY_UPDATES) != 0 || !due(c, "update")) return
        content.run({ JSONObject(content.net.text("https://api.github.com/repos/$REPO/releases/latest", mapOf("Accept" to "application/vnd.github+json"))) }) { o ->
            if (o == null) { Log.i(TAG, "update check: no release readable at $REPO"); return@run }
            done(c, "update")
            val tag = o.optString("tag_name").removePrefix("v")
            val apk = o.optJSONArray("assets")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } }
                ?.optString("browser_download_url").orEmpty()
            Log.i(TAG, "update check: latest $tag, this $current")
            if (!newer(tag, current) || apk.isEmpty()) return@run
            val notes = o.optString("body").lineSequence().map { it.trim().trimStart('-', '*', '#', ' ') }.firstOrNull { it.isNotEmpty() } ?: "A new version is ready."
            NotificationHub.post(Note("update:$tag", "", "Epple TV", "Epple TV $tag is available", notes, System.currentTimeMillis(), null, null,
                NotificationHub.UPDATE, action = "update:$apk", own = true), once = true)
        }
    }

    /** An update note whose version is now installed (or older) has been acted on: installing it restarted Epple TV. */
    fun clearInstalled(c: Context, current: String) {
        NotificationHub.notes.filter { it.own && it.key.startsWith("update:") && !newer(it.key.removePrefix("update:"), current) }
            .forEach { NotificationHub.dismiss(it.key) }
        // No update pending (installed, or none) and no download running: the downloaded APKs are no longer needed.
        // Epple TV is in front here, so no installer is reading one.
        if (downloading == 0L && NotificationHub.notes.none { it.own && it.key.startsWith("update:") }) purgeDownloads(c)
    }

    /** Deletes Epple TV's own downloaded update APKs (the download manager names repeats epple-update-1.apk, -2 …). */
    private fun purgeDownloads(c: Context) {
        c.getExternalFilesDir(null)?.listFiles { f -> f.isFile && f.name.startsWith("epple-update") && f.name.endsWith(".apk") }
            ?.forEach { if (!it.delete()) Log.w(TAG, "could not delete ${it.name}") }
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private var downloading = 0L
    /** Downloads the release's APK with the system's download manager, then opens the package installer. */
    fun install(c: Context, key: String, url: String) {
        if (downloading != 0L) return
        val dm = c.getSystemService(DownloadManager::class.java) ?: return
        purgeDownloads(c)              // copies from earlier attempts: the user is back in Epple TV, no installer has them open
        downloading = dm.enqueue(DownloadManager.Request(Uri.parse(url)).setMimeType("application/vnd.android.package-archive")
            .setTitle("Epple TV update").setDestinationInExternalFilesDir(c, null, "epple-update.apk"))
        NotificationHub.update(key, "Downloading the update…")
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloading) return
                ctx.applicationContext.unregisterReceiver(this); val id = downloading; downloading = 0
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) { NotificationHub.update(key, "The download failed. Open this again to retry."); return }
                NotificationHub.update(key, "Downloaded. Follow the installer to finish.")
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
                // The note stays while the installer is open (it can be cancelled: open the note again to retry). It goes
                // only when the new version is actually running: see clearInstalled().
                catch (e: Exception) { NotificationHub.update(key, "Downloaded, but the installer couldn't open.") }
            }
        }
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) c.applicationContext.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else c.applicationContext.registerReceiver(r, f)
    }

    // ---- followed titles ----

    enum class Change { NONE, SEASON, EPISODE }

    /** What is new for a followed show whose last aired episode was [was] ("season:episode"; null on the first look). */
    fun change(was: String?, se: Int, ep: Int): Change {
        if (was == null || se <= 0) return Change.NONE
        val p = was.split(':'); val ws = p.getOrNull(0)?.toIntOrNull() ?: return Change.NONE; val we = p.getOrNull(1)?.toIntOrNull() ?: 0
        return when { se > ws -> Change.SEASON; se == ws && ep > we -> Change.EPISODE; else -> Change.NONE }
    }

    /** The released movies of a franchise not seen before ([was] null on the first look: none are new). */
    fun newMovies(was: Set<String>?, released: List<String>): List<String> = if (was == null) emptyList() else released.filter { it !in was }

    /** A TMDB release date (yyyy-MM-dd) on or before [today]: the movie is out. */
    fun out(date: String, today: String) = date.length == 10 && date <= today

    /**
     * At most once a day (a failed check is retried an hour later): the shows and movies in the Watchlist and
     * Favourites. For a show, its latest aired episode (TMDB, cached 12 h): a new season or episode is one note each.
     * For a movie, its franchise (TMDB collection, cached 24 h; the movie's own record is the details page's cached
     * one): a newly released movie of that franchise is one note. The first look only records where everything is (no
     * alerts for what is already out); a note is never posted twice, even after it is dismissed. TMDB is updated by its
     * editors, so alerts can trail a release by hours; a movie counts as released on its TMDB (theatrical) date.
     */
    fun checkFollowed(c: Context, content: ContentRepository, catalog: Catalog, user: UserLibrary, prefs: Prefs) {
        if (prefs.get(Prefs.NOTIFY_FOLLOWED) != 0 || !catalog.ready) return
        val all = (user.items(UserLibrary.Kind.WATCHLIST) + user.items(UserLibrary.Kind.FAVOURITES)).distinctBy { it.id }
        val shows = all.filter { it.id.startsWith("tv:") }.take(30)
        val movies = all.filter { it.id.startsWith("movie:") }.take(30)
        if ((shows.isEmpty() && movies.isEmpty()) || !due(c, "followed")) return
        class Res(val shows: List<Pair<com.eppletv.launcher.content.ContentItem, JSONObject>>, val movieOk: Boolean, val collections: List<JSONObject>)
        content.run({
            val s = shows.mapNotNull { x -> try { x to catalog.show(x.id.removePrefix("tv:").toInt()) } catch (_: Exception) { null } }
            var got = 0
            val ids = movies.mapNotNull { m -> try { catalog.movieRecord(m.id.removePrefix("movie:").toInt()).also { got++ }
                .optJSONObject("belongs_to_collection")?.optInt("id")?.takeIf { it > 0 } } catch (_: Exception) { null } }
            Res(s, movies.isEmpty() || got > 0, ids.distinct().take(15).mapNotNull { id -> try { catalog.collection(id) } catch (_: Exception) { null } })
        }) { r ->
            // Offline or TMDB failing: nothing is recorded or posted, and the check runs again later.
            if (r == null || (shows.isNotEmpty() && r.shows.isEmpty()) || !r.movieOk) { Log.i(TAG, "followed check failed; retry later"); return@run }
            done(c, "followed")
            val p = prefs(c); val e = p.edit()
            for ((s, o) in r.shows) {
                val last = o.optJSONObject("last_episode_to_air") ?: continue
                val se = last.optInt("season_number"); val ep = last.optInt("episode_number")
                val ch = change(p.getString("f:${s.id}", null), se, ep)
                if (se > 0) e.putString("f:${s.id}", "$se:$ep")
                val art = img(o)
                val name = last.optString("name").takeIf { it.isNotBlank() }
                when {
                    ch == Change.SEASON && prefs.get(Prefs.NOTIFY_SEASONS) == 0 -> NotificationHub.post(Note("season:${s.id}:$se", "", "New season", "${s.title} · Season $se",
                        "Season $se is out${aired(last)}: episode $ep${name?.let { ", “$it”" } ?: ""}.", System.currentTimeMillis(), null, null,
                        NotificationHub.CONTENT, art, "details:${s.id}", own = true), once = true)
                    ch == Change.EPISODE && prefs.get(Prefs.NOTIFY_EPISODES) == 0 -> NotificationHub.post(Note("episode:${s.id}:$se:$ep", "", "New episode", "${s.title} · S$se E$ep",
                        "${name ?: "A new episode"} is out${aired(last)}.", System.currentTimeMillis(), null, null,
                        NotificationHub.CONTENT, art, "details:${s.id}", own = true), once = true)
                }
            }
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            for (o in r.collections) {
                val cid = o.optInt("id"); val franchise = o.optString("name").removeSuffix(" Collection")
                val parts = o.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: continue
                val released = parts.filter { out(it.optString("release_date"), today) }
                val ids = released.map { it.optInt("id").toString() }
                val was = p.getStringSet("c:$cid", null)?.toSet()
                e.putStringSet("c:$cid", ids.toSet() + (was ?: emptySet()))
                if (prefs.get(Prefs.NOTIFY_MOVIES) != 0) continue
                for (id in newMovies(was, ids)) {
                    val m = released.first { it.optInt("id").toString() == id }
                    val title = m.optString("title"); if (title.isBlank()) continue
                    val about = m.optString("overview").trim()
                    NotificationHub.post(Note("movie:$id", "", "New movie", "$franchise · $title",
                        "Released ${m.optString("release_date")}.${if (about.isNotEmpty()) " $about" else ""}", System.currentTimeMillis(), null, null,
                        NotificationHub.CONTENT, img(m), "details:movie:$id", own = true), once = true)
                }
            }
            e.apply()
            Log.i(TAG, "followed checked: ${r.shows.size} shows, ${r.collections.size} franchises")
        }
    }

    /** A title's wide artwork (or its poster), null when TMDB has neither. */
    private fun img(o: JSONObject): String? = (o.optString("backdrop_path").takeIf { it.startsWith("/") } ?: o.optString("poster_path").takeIf { it.startsWith("/") })
        ?.let { "https://image.tmdb.org/t/p/w780$it" }

    private fun aired(ep: JSONObject) = ep.optString("air_date").takeIf { it.length == 10 }?.let { " ($it)" } ?: ""
}
