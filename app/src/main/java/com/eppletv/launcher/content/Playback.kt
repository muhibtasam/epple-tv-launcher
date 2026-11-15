package com.eppletv.launcher.content

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Verified viewing time: what the apps' own media sessions report as PLAYING (Android's MediaSessionManager, readable
 * because Epple TV has notification access). Only time an app says it is playing at normal speed counts, so opening an
 * app, browsing it or leaving a title paused never does. Started by the notification listener, so it keeps counting
 * while Epple TV is in the background; everything runs on the main thread, on callbacks (no polling, no threads).
 *
 * Apps that publish no media session (or no PLAYING state) can't be verified: their titles reach Continue Watching only
 * through the lists the apps write themselves (Watch Next, their own Continue Watching row).
 */
object Playback {
    const val ELIGIBLE_MS = 30_000L
    private const val TAG = "EppleWatch"
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var app: Context? = null
    private val tracked = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val played = HashMap<String, Long>()          // per app: verified playing time since its last mark
    private val since = HashMap<String, Long>()           // per app: when its current PLAYING stretch began
    private val checks = HashMap<String, Runnable>()      // per app: the pending 30-second check
    private val lastState = HashMap<String, PlaybackState>()                    // per app: the newest state seen
    private val lastMeta = HashMap<String, android.media.MediaMetadata>()       // per app: what it said was playing
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list.orEmpty()) }

    /** Main thread, from the notification listener once it is connected. */
    fun start(c: Context, component: ComponentName) {
        if (manager != null) return
        val m = c.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            m.addOnActiveSessionsChangedListener(listener, component, main)
            manager = m; app = c.applicationContext
            sync(m.getActiveSessions(component))
            Log.i(TAG, "watching media sessions")
        } catch (e: SecurityException) { Log.w(TAG, "media sessions not readable: ${e.message}") }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(listener); manager = null
        tracked.values.forEach { (mc, cb) -> mc.unregisterCallback(cb); state(mc.packageName, null) }
        tracked.clear()
    }

    /** Starts counting [pkg] from zero (a title was just opened in it from Epple TV). */
    fun mark(pkg: String) {
        played[pkg] = 0L
        if (since.containsKey(pkg)) since[pkg] = SystemClock.elapsedRealtime()
        schedule(pkg)
    }

    /** Verified playing time in [pkg] since its last [mark], including a stretch still playing. */
    fun played(pkg: String): Long = (played[pkg] ?: 0L) + (since[pkg]?.let { SystemClock.elapsedRealtime() - it } ?: 0L)

    /** True when sessions are being read at all (notification access granted and connected). */
    val active: Boolean get() = manager != null

    private fun sync(list: List<MediaController>) {
        val now = list.map { it.sessionToken }.toSet()
        for (t in tracked.keys.filter { it !in now }) tracked.remove(t)?.let { (mc, cb) -> mc.unregisterCallback(cb); state(mc.packageName, null) }
        for (mc in list) {
            if (mc.sessionToken in tracked) continue
            val pkg = mc.packageName
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(s: PlaybackState?) = state(pkg, s)
                override fun onMetadataChanged(m: android.media.MediaMetadata?) { if (m != null) lastMeta[pkg] = m }
                override fun onSessionDestroyed() = state(pkg, null)
            }
            mc.registerCallback(cb, main); tracked[mc.sessionToken] = mc to cb
            mc.metadata?.let { lastMeta[pkg] = it }
            state(pkg, mc.playbackState)
        }
    }

    private fun state(pkg: String, s: PlaybackState?) {
        val playing = s != null && s.state == PlaybackState.STATE_PLAYING && s.playbackSpeed > 0f
        val t = SystemClock.elapsedRealtime()
        val open = since[pkg]
        if (playing && open == null) { since[pkg] = t; Log.i(TAG, "$pkg playing"); app?.let { Recency.sessionSeen(it, pkg) } }
        else if (!playing && open != null) {
            since.remove(pkg); played[pkg] = (played[pkg] ?: 0L) + (t - open)
            remember(pkg, s ?: lastState[pkg])
            Log.i(TAG, "$pkg stopped: ${played(pkg) / 1000} s verified")
        }
        if (s != null) lastState[pkg] = s
        schedule(pkg)
    }

    /**
     * Saves where the session says [pkg] stands (position, duration, episode name) for Continue Watching (see [Resume]).
     * A PLAYING state is carried forward to now; nothing is saved without a title or a position.
     */
    private fun remember(pkg: String, st: PlaybackState?) {
        val c = app ?: return; st ?: return
        val md = tracked.values.firstOrNull { it.first.packageName == pkg }?.first?.metadata ?: lastMeta[pkg] ?: return
        val title = (md.description?.title ?: md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))?.toString()?.trim().orEmpty()
        if (title.isEmpty() || st.position <= 0) return
        val name = md.description?.subtitle?.toString()?.trim().orEmpty()
        val pos = st.position + if (st.state == PlaybackState.STATE_PLAYING) ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
        val dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        Resume.save(c, pkg, title, Resume.Saved(if (dur > 0) pos.coerceAtMost(dur) else pos, dur, name, System.currentTimeMillis()))
        Log.i(TAG, "$pkg at ${pos / 1000} s of ${dur / 1000} s")
    }

    /** While [pkg] plays below the threshold, one check at the moment it would cross it. */
    private fun schedule(pkg: String) {
        checks.remove(pkg)?.let { main.removeCallbacks(it) }
        if (!since.containsKey(pkg)) return
        val left = ELIGIBLE_MS - played(pkg)
        if (left <= 0) { reached(pkg); return }
        val r = Runnable { checks.remove(pkg); if (played(pkg) >= ELIGIBLE_MS) reached(pkg) }
        checks[pkg] = r; main.postDelayed(r, left + 50)
    }

    private fun reached(pkg: String) {
        val c = app ?: return
        remember(pkg, lastState[pkg])
        Recency.verified(c, pkg, played(pkg))
        // What the session says is playing (no extra permission): lets Taste reject trailers, previews and other titles.
        val md = tracked.values.firstOrNull { it.first.packageName == pkg }?.first?.metadata
        val names = if (md == null) emptyList() else listOf(android.media.MediaMetadata.METADATA_KEY_TITLE, android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, android.media.MediaMetadata.METADATA_KEY_ALBUM, android.media.MediaMetadata.METADATA_KEY_ARTIST)
            .mapNotNull { k -> md.getString(k)?.takeIf { it.isNotBlank() } }
        Taste.played(c, pkg, names, md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L)
    }
}
