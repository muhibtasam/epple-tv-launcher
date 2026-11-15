package com.eppletv.launcher.system

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads other apps' notifications (Android's notification access, which Epple TV allows itself when it becomes the
 * launcher, or from Settings > Notifications). Nothing is stored or sent anywhere: notifications live in memory only,
 * for the popup, the dot on the clock and the list in Control Center.
 */
class EppleNotifications : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationHub.connected(this, activeNotifications.orEmpty().toList())
        // The same access lets Epple TV read the apps' media sessions: verified viewing time for Continue Watching.
        com.eppletv.launcher.content.Playback.start(this, NotificationHub.component(this))
    }
    override fun onListenerDisconnected() { NotificationHub.disconnected(); com.eppletv.launcher.content.Playback.stop() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { NotificationHub.posted(this, sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { NotificationHub.removed(sbn.key) }
}

object NotificationHub {
    /** [cat]: CONTENT, SYSTEM or UPDATE. [own]: written by Epple TV itself (Wi-Fi, updates, followed titles), kept across
     *  restarts; the others are other apps' notifications, in memory only. [action]: what Open does for an own note. */
    class Note(val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long,
               val intent: PendingIntent?, val icon: Bitmap?, val cat: Int = SYSTEM, val art: String? = null,
               val action: String? = null, var read: Boolean = false, val own: Boolean = false)

    const val CONTENT = 1; const val SYSTEM = 2; const val UPDATE = 3

    private val main = Handler(Looper.getMainLooper())
    private var appNotes: List<Note> = emptyList()
    private var mine: List<Note> = emptyList()
    /** Newest first: Epple TV's own and other apps'. Main thread. */
    val notes: List<Note> get() = (mine + appNotes).sortedByDescending { it.time }
    val unread: Int get() = notes.count { !it.read }
    private val dismissed = HashSet<String>()
    /** Main thread: called with a newly posted note (for the popup), or null when the list only changed. */
    var onChange: ((Note?) -> Unit)? = null
    private var prefs: android.content.SharedPreferences? = null

    fun component(ctx: Context) = ComponentName(ctx, EppleNotifications::class.java)
    fun allowed(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(component(ctx).flattenToString()) == true

    /** Main thread, once at launch: Epple TV's own notes and their read state come back. */
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("notes", Context.MODE_PRIVATE); prefs = p
        mine = try {
            val a = org.json.JSONArray(p.getString("own", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                Note(o.getString("k"), o.optString("p"), o.optString("a"), o.optString("t"), o.optString("x"), o.optLong("w"), null, null,
                    o.optInt("c", SYSTEM), o.optString("r").ifEmpty { null }, o.optString("n").ifEmpty { null }, o.optBoolean("d"), true) } }
        } catch (_: Exception) { emptyList() }
    }
    private fun save() {
        val a = org.json.JSONArray()
        for (n in mine) a.put(org.json.JSONObject().put("k", n.key).put("p", n.pkg).put("a", n.app).put("t", n.title).put("x", n.text)
            .put("w", n.time).put("c", n.cat).put("r", n.art ?: "").put("n", n.action ?: "").put("d", n.read))
        prefs?.edit()?.putString("own", a.toString())?.apply()
    }

    /**
     * Main thread. Adds or replaces Epple TV's own note [n] by its stable key (one Wi-Fi note, one note per release or
     * episode). [once]: a key already posted before (even if dismissed since) is never posted again.
     */
    fun post(n: Note, once: Boolean = false) {
        val p = prefs ?: return
        if (once && p.getStringSet("posted", emptySet())!!.contains(n.key)) return
        val same = mine.firstOrNull { it.key == n.key }
        if (same != null && same.title == n.title && same.text == n.text) return
        mine = (listOf(n) + mine.filter { it.key != n.key }).take(40); save()
        if (once) p.edit().putStringSet("posted", p.getStringSet("posted", emptySet())!! + n.key).apply()
        onChange?.invoke(n)
    }
    /** Replaces an own note's text (an update's download state) without a popup. */
    fun update(key: String, text: String) {
        val n = mine.firstOrNull { it.key == key } ?: return
        mine = mine.map { if (it.key == key) Note(n.key, n.pkg, n.app, n.title, text, n.time, null, null, n.cat, n.art, n.action, n.read, true) else it }
        save(); onChange?.invoke(null)
    }

    fun markRead(key: String) {
        val n = notes.firstOrNull { it.key == key } ?: return
        if (n.read) return
        n.read = true; if (n.own) save(); onChange?.invoke(null)
    }
    fun markRead() { if (unread != 0) { notes.forEach { it.read = true }; save(); onChange?.invoke(null) } }
    /** Removes a note from Epple TV's list only; another app's own notification stays where it is. */
    fun dismiss(key: String) {
        if (mine.any { it.key == key }) { mine = mine.filter { it.key != key }; save() }
        else { dismissed += key; appNotes = appNotes.filter { it.key != key } }
        onChange?.invoke(null)
    }
    fun clearAll() { dismissed += appNotes.map { it.key }; appNotes = emptyList(); mine = emptyList(); save(); onChange?.invoke(null) }

    internal fun connected(ctx: Context, all: List<StatusBarNotification>) {
        val list = all.mapNotNull { note(ctx, it) }
        main.post { appNotes = list.filter { it.key !in dismissed }; onChange?.invoke(null) }
    }
    internal fun disconnected() { main.post { appNotes = emptyList(); onChange?.invoke(null) } }
    internal fun posted(ctx: Context, sbn: StatusBarNotification) {
        val n = note(ctx, sbn) ?: return
        main.post {
            val fresh = appNotes.none { it.key == n.key && it.title == n.title && it.text == n.text }
            dismissed -= n.key
            appNotes = (listOf(n) + appNotes.filter { it.key != n.key }).take(30)
            onChange?.invoke(if (fresh) n else null)
        }
    }
    internal fun removed(key: String) { main.post { if (appNotes.any { it.key == key }) { appNotes = appNotes.filter { it.key != key }; onChange?.invoke(null) } } }

    /**
     * Only what is worth seeing on a TV: from an app you can open, finished (not ongoing or in progress), with a title or
     * text, and not media controls, system status, the TV maker's or Google TV's own, or a group's summary line.
     */
    private fun note(ctx: Context, sbn: StatusBarNotification): Note? {
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        if (pkg == ctx.packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        val test = pkg == "com.android.shell" && sbn.tag?.startsWith("epple-test") == true    // adb shell cmd notification post, for testing
        if (!test && (n.category in SKIP_CATEGORIES || SYSTEM_PKGS.containsMatchIn(pkg))) return null
        val pm = ctx.packageManager
        if (!test && pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) return null
        val e = n.extras
        val title = (e.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()
        if (title.isEmpty() && text.isEmpty()) return null
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        val icon = try {
            (n.getLargeIcon() ?: n.smallIcon)?.loadDrawable(ctx)?.let { d ->
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)) }
            }
        } catch (_: Exception) { null }
        return Note(sbn.key, pkg, app, title.ifEmpty { app }, text, sbn.postTime, n.contentIntent, icon)
    }

    private val SKIP_CATEGORIES = setOf(Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE, Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_RECOMMENDATION)
    private val SYSTEM_PKGS = Regex("^(android|com\\.android\\.|com\\.google\\.android\\.(tvlauncher|apps\\.tv\\.launcherx|tungsten|tv\\.)|com\\.tcl\\.|com\\.mediatek\\.)")
}
