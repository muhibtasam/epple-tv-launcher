package com.eppletv.launcher.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import com.eppletv.launcher.R
import com.eppletv.launcher.content.Apps

/**
 * The user's top row. Stored as an ordered list of package names; nothing leaves the device.
 * Uninstalled apps stay in the stored list (hidden) so a reinstall puts them back where they were.
 */
class Library(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    private fun stored(): List<String>? = prefs.getString("top_row", null)?.split('\n')?.filter { it.isNotEmpty() }
    private fun save(v: List<String>) = prefs.edit().putString("top_row", v.joinToString("\n")).apply()

    /** Stored order, or the defaults until the user changes anything (first five apps if none of the defaults exist). */
    private fun order(apps: List<AppEntry>): List<String> = stored() ?: run {
        val have = apps.map { it.packageName }.toSet()
        if (Apps.DEFAULT_PINS.any { it in have }) Apps.DEFAULT_PINS else apps.take(5).map { it.packageName }
    }

    fun pinned(apps: List<AppEntry>): List<AppEntry> { val by = apps.associateBy { it.packageName }; return order(apps).mapNotNull { by[it] } }
    fun rest(apps: List<AppEntry>): List<AppEntry> { val p = pinned(apps).map { it.packageName }.toSet(); return apps.filter { it.packageName !in p } }
    fun isPinned(apps: List<AppEntry>, pkg: String) = pkg in order(apps) && apps.any { it.packageName == pkg }

    fun add(apps: List<AppEntry>, pkg: String) = save(order(apps).filter { it != pkg } + pkg)
    fun remove(apps: List<AppEntry>, pkg: String) = save(order(apps) - pkg)

    /** Swaps [pkg] with its visible neighbour ([delta] = -1 left, +1 right). Hidden (uninstalled) entries keep their slots. */
    fun move(apps: List<AppEntry>, pkg: String, delta: Int) {
        val all = order(apps).toMutableList()
        val visible = pinned(apps).map { it.packageName }
        val other = visible.getOrNull(visible.indexOf(pkg) + delta) ?: return
        val a = all.indexOf(pkg); val b = all.indexOf(other)
        all[a] = other; all[b] = pkg
        save(all)
    }

    fun canMove(apps: List<AppEntry>, pkg: String, delta: Int): Boolean {
        val v = pinned(apps).map { it.packageName }; return v.indexOf(pkg) + delta in v.indices
    }

    /** Back to the default top row. */
    fun reset() = prefs.edit().remove("top_row").apply()

    fun categoryLabel(category: Int): String = when (category) {
        ApplicationInfo.CATEGORY_VIDEO -> app.getString(R.string.cat_video)
        ApplicationInfo.CATEGORY_AUDIO -> app.getString(R.string.cat_audio)
        else -> ""
    }
}
