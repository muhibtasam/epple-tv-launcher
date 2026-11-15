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
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private fun due(c: Context, k: String): Boolean {
        val p = prefs(c); val now = System.currentTimeMillis()
        if (now - p.getLong(k, 0) < DAY) return false
        p.edit().putLong(k, now).apply(); return true
    }

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
        if (prefs.get(Prefs.NOTIFY_UPDATES) != 0 || !due(c, "update")) return
        content.run({ JSONObject(content.net.text("https://api.github.com/repos/$REPO/releases/latest", mapOf("Accept" to "application/vnd.github+json"))) }) { o ->
            if (o == null) { Log.i(TAG, "update check: no release readable at $REPO"); return@run }
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
                catch (e: Exception) { NotificationHub.update(key, "Downloaded, but the installer couldn't open.") }
            }
        }
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) c.applicationContext.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else c.applicationContext.registerReceiver(r, f)
    }

    // ---- followed shows ----

    /**
     * At most once a day: each show in the Watchlist or Favourites, its latest aired episode (TMDB, cached 12 h). The
     * first look only records where each show is (no alerts for what already aired); later, a new season or episode is
     * one note each, never repeated. TMDB is updated by its editors, so alerts can trail a release by hours.
     */
    fun checkFollowed(c: Context, content: ContentRepository, catalog: Catalog, user: UserLibrary, prefs: Prefs) {
        if (prefs.get(Prefs.NOTIFY_FOLLOWED) != 0 || !catalog.ready || !due(c, "followed")) return
        val shows = (user.items(UserLibrary.Kind.WATCHLIST) + user.items(UserLibrary.Kind.FAVOURITES))
            .filter { it.id.startsWith("tv:") }.distinctBy { it.id }.take(30)
        if (shows.isEmpty()) return
        content.run({ shows.mapNotNull { s -> try { s to catalog.show(s.id.removePrefix("tv:").toInt()) } catch (_: Exception) { null } } }) { res ->
            val p = prefs(c); val e = p.edit()
            for ((s, o) in res.orEmpty()) {
                val last = o.optJSONObject("last_episode_to_air") ?: continue
                val se = last.optInt("season_number"); val ep = last.optInt("episode_number")
                val was = p.getString("f:${s.id}", null)
                e.putString("f:${s.id}", "$se:$ep")
                if (was == null) continue
                val (ws, we) = was.split(':').map { it.toIntOrNull() ?: 0 }
                val art = o.optString("backdrop_path").takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/w780$it" }
                val season = se > ws; val episode = !season && se == ws && ep > we
                when {
                    season && prefs.get(Prefs.NOTIFY_SEASONS) == 0 -> NotificationHub.post(Note("season:${s.id}:$se", "", "New season", "${s.title} · Season $se",
                        "Season $se has started: episode $ep${last.optString("name").takeIf { it.isNotEmpty() }?.let { ", “$it”" } ?: ""}.", System.currentTimeMillis(), null, null,
                        NotificationHub.CONTENT, art, "details:${s.id}", own = true), once = true)
                    episode && prefs.get(Prefs.NOTIFY_EPISODES) == 0 -> NotificationHub.post(Note("episode:${s.id}:$se:$ep", "", "New episode", "${s.title} · S$se E$ep",
                        last.optString("name").ifEmpty { "A new episode is out." }, System.currentTimeMillis(), null, null,
                        NotificationHub.CONTENT, art, "details:${s.id}", own = true), once = true)
                }
            }
            e.apply()
            Log.i(TAG, "followed shows checked: ${res?.size ?: 0}")
        }
    }
}
