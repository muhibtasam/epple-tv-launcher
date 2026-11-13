package com.eppletv.launcher.home

import android.content.Context

/** Launcher settings, stored on this TV only. Each option is an index into its list of choices. */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun set(k: String, v: Int) = p.edit().putInt(k, v).apply()

    class Choice(val key: String, val label: String, val options: List<String>, val default: Int, val restart: Boolean = false)

    companion object {
        val CLOCK = Choice("clock", "Clock", listOf("System", "12-hour", "24-hour"), 0)
        val ROTATE = Choice("rotate", "Artwork rotation", listOf("Every 9 seconds", "Every 15 seconds", "Every 30 seconds", "Off"), 0)
        val TEXT = Choice("text", "Text size", listOf("Standard", "Large", "Larger"), 0, restart = true)
        val WEIGHT = Choice("weight", "Text weight", listOf("Regular", "Bold"), 0, restart = true)
        val REGION = Choice("region", "Content region", listOf("Automatic", "Pakistan", "India", "United States", "United Kingdom"), 0, restart = true)
        val INDIAN = Choice("indian", "Indian titles", listOf("Balanced", "More Indian", "More Hollywood", "Hollywood only"), 0)
        val SAVER = Choice("saver", "Start screensaver after", listOf("Off", "2 minutes", "5 minutes", "10 minutes", "15 minutes", "30 minutes"), 2)
        val SAVER_STYLE = Choice("saver_style", "Screensaver motion", listOf("Slow pan", "Still"), 0)
        private val REGION_CODES = listOf("", "PK", "IN", "US", "GB")
        private val SAVER_MIN = listOf(0, 2, 5, 10, 15, 30)
        private val ROTATE_MS = listOf(9000L, 15000L, 30000L, 0L)
        private val TEXT_SCALE = listOf(1f, 1.1f, 1.2f)
    }

    fun get(c: Choice) = int(c.key, c.default).coerceIn(0, c.options.size - 1)
    fun put(c: Choice, v: Int) = set(c.key, v)

    val regionCode get() = REGION_CODES[get(REGION)]
    val saverMs get() = SAVER_MIN[get(SAVER)] * 60_000L
    val rotateMs get() = ROTATE_MS[get(ROTATE)]
    val textScale get() = TEXT_SCALE[get(TEXT)]
    val boldText get() = get(WEIGHT) == 1
}
