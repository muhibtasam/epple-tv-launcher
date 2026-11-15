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
                override fun onSessionDestroyed() = state(pkg, null)
            }
            mc.registerCallback(cb, main); tracked[mc.sessionToken] = mc to cb
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
            Log.i(TAG, "$pkg stopped: ${played(pkg) / 1000} s verified")
        }
        schedule(pkg)
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

    private fun reached(pkg: String) { app?.let { Recency.verified(it, pkg, played(pkg)) } }
}
