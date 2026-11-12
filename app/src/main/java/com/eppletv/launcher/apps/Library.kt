package com.eppletv.launcher.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import com.eppletv.launcher.R

/** A titled row of apps on Home. [key] is stable across reloads so focus can be restored. */
class Section(val key: String, val title: String?, val apps: List<AppEntry>)

/** The user's own arrangement: pinned top-row apps and recently opened apps. Stored locally; nothing leaves the device. */
class Library(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    private fun list(key: String): List<String>? = prefs.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() }
    private fun save(key: String, v: List<String>) = prefs.edit().putString(key, v.joinToString("\n")).apply()

    fun recordLaunch(pkg: String) = save("recents", (listOf(pkg) + (list("recents") ?: emptyList()).filter { it != pkg }).take(12))

    /** Top-row apps: the user's pins, or until they pin anything, video apps first then the rest. */
    fun topRow(apps: List<AppEntry>): List<AppEntry> {
        val by = apps.associateBy { it.packageName }
        val pins = list("pins")
        if (pins != null) return pins.mapNotNull { by[it] }
        return (apps.filter { it.category == ApplicationInfo.CATEGORY_VIDEO } + apps.filter { it.category != ApplicationInfo.CATEGORY_VIDEO }).take(TOP_ROW_DEFAULT)
    }

    fun isPinned(apps: List<AppEntry>, pkg: String) = topRow(apps).any { it.packageName == pkg }

    fun togglePin(apps: List<AppEntry>, pkg: String) {
        val cur = topRow(apps).map { it.packageName }
        save("pins", if (pkg in cur) cur - pkg else cur + pkg)
    }

    fun sections(apps: List<AppEntry>): List<Section> {
        if (apps.isEmpty()) return emptyList()
        val by = apps.associateBy { it.packageName }
        val out = ArrayList<Section>()
        out.add(Section("top", null, topRow(apps)))
        val recents = (list("recents") ?: emptyList()).mapNotNull { by[it] }
        if (recents.isNotEmpty()) out.add(Section("recents", app.getString(R.string.row_recents), recents))
        val used = HashSet<String>()
        for ((cat, title) in listOf(
            ApplicationInfo.CATEGORY_VIDEO to R.string.cat_video, ApplicationInfo.CATEGORY_AUDIO to R.string.cat_audio,
            ApplicationInfo.CATEGORY_GAME to R.string.cat_game)) {
            val l = apps.filter { it.category == cat }
            if (l.isNotEmpty()) { out.add(Section("cat$cat", app.getString(title), l)); l.forEach { used.add(it.packageName) } }
        }
        val top = out[0].apps.map { it.packageName }.toSet()
        val rest = apps.filter { it.packageName !in used }
        // Skip the catch-all row when it would only repeat the top row.
        if (rest.any { it.packageName !in top } || rest.size > top.size) out.add(Section("more", app.getString(if (used.isEmpty()) R.string.row_all else R.string.row_more), rest))
        return out.filter { it.apps.isNotEmpty() || it.key == "top" }.let { l -> if (l.first().apps.isEmpty()) l.drop(1) else l }
    }

    fun categoryLabel(category: Int): String = if (category == ApplicationInfo.CATEGORY_UNDEFINED) "" else app.getString(when (category) {
        ApplicationInfo.CATEGORY_VIDEO -> R.string.cat_video
        ApplicationInfo.CATEGORY_AUDIO -> R.string.cat_audio
        ApplicationInfo.CATEGORY_GAME -> R.string.cat_game
        ApplicationInfo.CATEGORY_NEWS -> R.string.cat_news
        ApplicationInfo.CATEGORY_IMAGE -> R.string.cat_image
        ApplicationInfo.CATEGORY_SOCIAL -> R.string.cat_social
        ApplicationInfo.CATEGORY_MAPS -> R.string.cat_maps
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> R.string.cat_productivity
        else -> R.string.cat_app
    })

    private companion object { const val TOP_ROW_DEFAULT = 6 }
}
