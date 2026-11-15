package com.eppletv.launcher.content

/**
 * Where a title streams for this viewer, from TMDB's watch providers (data by JustWatch) for the content region.
 * Pure (no Android, no org.json): the decisions here are unit tested.
 *
 * - [State.CONFIRMED]: TMDB lists the title with a subscription (flatrate) in the region on a provider that maps to a
 *   pinned app. Only this state may show a service badge.
 * - [State.UNKNOWN]: no region data for the title, no subscription offer listed, or no provider mapping at all (the
 *   provider list failed, or no pinned app is a TMDB provider here). Missing data never means unavailable.
 * - [State.ELSEWHERE]: the region lists subscription providers for the title, none of them a pinned app.
 */
object Availability {
    enum class State { CONFIRMED, UNKNOWN, ELSEWHERE }
    class Result(val state: State, val app: String? = null)

    /** The service term of the ranking (Revision 2): confirmed 1, unknown neutral 0.3, elsewhere 0. */
    const val SCORE_CONFIRMED = 1.0
    const val SCORE_UNKNOWN = 0.3
    const val SCORE_ELSEWHERE = 0.0
    fun score(s: State) = when (s) { State.CONFIRMED -> SCORE_CONFIRMED; State.UNKNOWN -> SCORE_UNKNOWN; State.ELSEWHERE -> SCORE_ELSEWHERE }

    /** "New on your apps" shows only with this many confirmed titles; fewer hides it. */
    const val MIN_NEW = 6
    const val ATTRIBUTION = "Availability: JustWatch"

    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Provider id -> app package, from the region's provider list ([providers]: id -> name) and the apps' own
     * labels ([apps]: package to label). A provider maps when its name equals an app's label or ends with it
     * ("Amazon Prime Video" / "Prime Video"), and only when exactly one app matches. Labels under 4 letters never match.
     * Nothing is hardcoded: an app TMDB doesn't list for the region (Stremio, or a service absent there) maps to nothing.
     */
    fun map(providers: Map<Int, String>, apps: List<Pair<String, String>>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for ((id, name) in providers) {
            val n = norm(name)
            if (n.isEmpty()) continue
            val hit = apps.filter { (_, label) -> val l = norm(label); l.length >= 4 && (n == l || n.endsWith(l)) }.map { it.first }.distinct()
            if (hit.size == 1) out[id] = hit[0]
        }
        return out
    }

    /**
     * One title. [region]: TMDB has an entry for the region; [flatrate]: its subscription provider ids there.
     * [pinned]: pinned apps in order; [usage]: app -> verified plays, so with several matches the one used most wins
     * (then the first pinned).
     */
    fun classify(region: Boolean, flatrate: List<Int>?, map: Map<Int, String>, pinned: List<String>, usage: Map<String, Int>): Result {
        if (!region || flatrate.isNullOrEmpty() || map.isEmpty()) return Result(State.UNKNOWN)
        val apps = flatrate.mapNotNull { map[it] }.filter { it in pinned }.distinct()
        if (apps.isEmpty()) return Result(State.ELSEWHERE)
        val best = apps.sortedWith(compareByDescending<String> { usage[it] ?: 0 }.thenBy { pinned.indexOf(it) }).first()
        return Result(State.CONFIRMED, best)
    }

    /** "New on your apps": confirmed titles only, shown with at least [MIN_NEW]; otherwise nothing. */
    fun newRow(confirmed: List<String>): List<String> = if (confirmed.size >= MIN_NEW) confirmed else emptyList()
}
