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
    override fun onListenerConnected() { NotificationHub.connected(this, activeNotifications.orEmpty().toList()) }
    override fun onListenerDisconnected() { NotificationHub.disconnected() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { NotificationHub.posted(this, sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { NotificationHub.removed(sbn.key) }
}

object NotificationHub {
    class Note(val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long,
               val intent: PendingIntent?, val icon: Bitmap?)

    private val main = Handler(Looper.getMainLooper())
    /** Newest first. Main thread. */
    var notes: List<Note> = emptyList(); private set
    var unread = 0; private set
    private val dismissed = HashSet<String>()
    /** Main thread: called with a newly posted note (for the popup), or null when the list only changed. */
    var onChange: ((Note?) -> Unit)? = null

    fun component(ctx: Context) = ComponentName(ctx, EppleNotifications::class.java)
    fun allowed(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(component(ctx).flattenToString()) == true

    fun markRead() { if (unread != 0) { unread = 0; onChange?.invoke(null) } }
    /** Removes a note from Epple TV's list only; the app's own notification stays where it is. */
    fun dismiss(key: String) { dismissed += key; notes = notes.filter { it.key != key }; onChange?.invoke(null) }
    fun clearAll() { dismissed += notes.map { it.key }; notes = emptyList(); unread = 0; onChange?.invoke(null) }

    internal fun connected(ctx: Context, all: List<StatusBarNotification>) {
        val list = all.mapNotNull { note(ctx, it) }.sortedByDescending { it.time }
        main.post { notes = list.filter { it.key !in dismissed }; onChange?.invoke(null) }
    }
    internal fun disconnected() { main.post { notes = emptyList(); onChange?.invoke(null) } }
    internal fun posted(ctx: Context, sbn: StatusBarNotification) {
        val n = note(ctx, sbn) ?: return
        main.post {
            val fresh = notes.none { it.key == n.key && it.title == n.title && it.text == n.text }
            dismissed -= n.key
            notes = (listOf(n) + notes.filter { it.key != n.key }).take(30)
            if (fresh) unread++
            onChange?.invoke(if (fresh) n else null)
        }
    }
    internal fun removed(key: String) { main.post { if (notes.any { it.key == key }) { notes = notes.filter { it.key != key }; onChange?.invoke(null) } } }

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
        if (!test && (n.category in SKIP_CATEGORIES || SYSTEM.containsMatchIn(pkg))) return null
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
    private val SYSTEM = Regex("^(android|com\\.android\\.|com\\.google\\.android\\.(tvlauncher|apps\\.tv\\.launcherx|tungsten|tv\\.)|com\\.tcl\\.|com\\.mediatek\\.)")
}
