package com.eppletv.launcher.content

import android.content.Context
import org.json.JSONArray

/**
 * The user's own lists, kept only on this TV (SharedPreferences): Continue Watching (titles started from Epple TV,
 * with the app they were opened in), Watchlist, Favourites and Recently Viewed (details pages opened).
 * Main thread; each list is small and bounded.
 */
class UserLibrary(ctx: Context) {
    enum class Kind(val key: String, val max: Int) { CONTINUE("continue", 20), WATCHLIST("watchlist", 100), FAVOURITES("favourites", 100), RECENT("recent", 30) }

    private val prefs = ctx.applicationContext.getSharedPreferences("user_library", Context.MODE_PRIVATE)
    private val cache = HashMap<Kind, ArrayList<ContentItem>>()
    var version = 0; private set           // bumps on every change, so screens know when to rebuild

    fun items(l: Kind): kotlin.collections.List<ContentItem> = load(l)

    fun contains(l: Kind, id: String) = load(l).any { it.id == id }

    /** Adds (or moves to the front) [item]; the oldest entries beyond the list's size are dropped. */
    fun add(l: Kind, item: ContentItem) {
        val a = load(l); a.removeAll { it.id == item.id }; a.add(0, item)
        while (a.size > l.max) a.removeAt(a.size - 1)
        save(l)
    }

    fun remove(l: Kind, id: String) { if (load(l).removeAll { it.id == id }) save(l) }

    fun toggle(l: Kind, item: ContentItem): Boolean = if (contains(l, item.id)) { remove(l, item.id); false } else { add(l, item); true }

    fun clear(l: Kind) { load(l).clear(); save(l) }

    private fun load(l: Kind): ArrayList<ContentItem> = cache.getOrPut(l) {
        try { JSONArray(prefs.getString(l.key, "[]")).let { a -> ArrayList((0 until a.length()).map { ContentItem.of(a.getJSONObject(it)) }) } }
        catch (_: Exception) { ArrayList() }
    }

    private fun save(l: Kind) {
        version++
        prefs.edit().putString(l.key, JSONArray(load(l).map { it.json() }).toString()).apply()
    }
}
