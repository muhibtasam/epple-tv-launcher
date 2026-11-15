package com.eppletv.launcher.content

import com.eppletv.launcher.content.Availability.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityTest {
    // As TMDB returned for Pakistan (watch/providers/movie?watch_region=PK), in part.
    private val pk = mapOf(8 to "Netflix", 119 to "Amazon Prime Video", 232 to "Zee5", 1899 to "HBO Max", 538 to "Plex")
    private val NF = "com.netflix.ninja"; private val PV = "com.amazon.amazonvideo.livingroom"
    private val CR = "com.crunchyroll.crunchyroid"; private val ST = "com.stremio.one"; private val YT = "com.google.android.youtube.tv"
    private val pinned = listOf(NF to "Netflix", PV to "Prime Video", CR to "Crunchyroll", "com.apple.atve.androidtv.appletv" to "Apple TV", YT to "YouTube", ST to "Stremio")
    private val map = Availability.map(pk, pinned)
    private val order = pinned.map { it.first }

    @Test fun mapsOnlyRealProvidersToPinnedApps() {
        assertEquals(mapOf(8 to NF, 119 to PV), map)            // Stremio, Crunchyroll, Apple TV, YouTube: no Pakistan provider
        assertTrue(ST !in map.values)
    }

    @Test fun ambiguousOrPartialNamesNeverMap() {
        assertEquals(emptyMap<Int, String>(), Availability.map(mapOf(1 to "Netflix"), listOf("a" to "Netflix", "b" to "Netflix")))
        assertEquals(emptyMap<Int, String>(), Availability.map(mapOf(1 to "Joy TV"), listOf("a" to "TV")))
    }

    @Test fun unrelatedProviderNamesNeverMap() {
        val unrelated = mapOf(1796 to "Netflix basic with Ads", 2100 to "Amazon Prime Video with Ads", 9 to "Amazon Video",
            10 to "Amazon Prime Video Channels", 2 to "Apple TV", 350 to "Apple TV Plus", 300 to "Pluto TV", 457 to "VIX Video",
            175 to "Netflix Kids", 283 to "Crunchyroll Premium")
        // Labels a suffix rule would have caught ("…Video", "…TV", "…Prime Video").
        val labels = listOf(NF to "Netflix", PV to "Prime Video", "v" to "Video", "t" to "Pluto", "p" to "Prime", ST to "Stremio")
        assertEquals(emptyMap<Int, String>(), Availability.map(unrelated, labels))
    }

    @Test fun primeVideoOnlyThroughItsAlias() {
        assertEquals(mapOf(119 to PV), Availability.map(mapOf(119 to "Amazon Prime Video"), listOf(PV to "Prime Video")))
        assertEquals(mapOf(119 to PV), Availability.map(mapOf(119 to "Prime Video"), listOf(PV to "Prime Video")))
        assertEquals(emptyMap<Int, String>(), Availability.map(mapOf(119 to "Amazon Prime Video"), listOf("x" to "Amazon")))
    }

    @Test fun confirmedOnPinnedApp() {
        val r = Availability.classify(true, listOf(8), map, order, emptyMap())
        assertEquals(State.CONFIRMED, r.state); assertEquals(NF, r.app)
    }

    @Test fun unknownWithoutRegionData() {
        assertEquals(State.UNKNOWN, Availability.classify(false, null, map, order, emptyMap()).state)
        assertEquals(State.UNKNOWN, Availability.classify(true, emptyList(), map, order, emptyMap()).state)   // rent/buy only: no claim
    }

    @Test fun failedOrEmptyProviderListMeansUnknownNotUnavailable() {
        val none = Availability.map(emptyMap(), pinned)
        val r = Availability.classify(true, listOf(8), none, order, emptyMap())
        assertEquals(State.UNKNOWN, r.state); assertNull(r.app)
    }

    @Test fun confirmedElsewhere() {
        val r = Availability.classify(true, listOf(232, 1899), map, order, emptyMap())
        assertEquals(State.ELSEWHERE, r.state); assertNull(r.app)
    }

    @Test fun mappedButNotPinnedIsElsewhere() {
        val r = Availability.classify(true, listOf(8), map, listOf(PV, ST), emptyMap())
        assertEquals(State.ELSEWHERE, r.state)
    }

    @Test fun mostUsedOfSeveralPinnedServices() {
        assertEquals(NF, Availability.classify(true, listOf(119, 8), map, order, emptyMap()).app)            // tie: first pinned
        assertEquals(PV, Availability.classify(true, listOf(8, 119), map, order, mapOf(PV to 5, NF to 2)).app)
    }

    @Test fun badgeOnlyWhenConfirmed() {
        for (r in listOf(Availability.classify(false, null, map, order, emptyMap()), Availability.classify(true, listOf(232), map, order, emptyMap())))
            assertNull(r.app)
    }

    @Test fun newOnYourAppsNeedsSix() {
        assertEquals(emptyList<String>(), Availability.newRow(listOf("a", "b", "c", "d", "e")))
        assertEquals(6, Availability.newRow(listOf("a", "b", "c", "d", "e", "f")).size)
    }

    @Test fun serviceScores() {
        assertEquals(1.0, Availability.score(State.CONFIRMED), 0.0)
        assertEquals(0.3, Availability.score(State.UNKNOWN), 0.0)
        assertEquals(0.0, Availability.score(State.ELSEWHERE), 0.0)
    }

    // Ranking: availability changes order, never membership.
    private fun cand(id: String, service: Double) = Recommender.Cand(id, id, listOf(id.hashCode() and 0xFFFF), "en", 2020, 400, 7.5, 500, service = service)

    @Test fun unknownAndElsewhereStayEligible() {
        val cs = listOf(cand("movie:1", Availability.SCORE_CONFIRMED), cand("movie:2", Availability.SCORE_UNKNOWN), cand("movie:3", Availability.SCORE_ELSEWHERE))
        val picks = Recommender.order(cs, Recommender.Profile(), Recommender.Feedback(), 0L)
        assertEquals(listOf("movie:1", "movie:2", "movie:3"), picks.map { it.c.id })
    }

    @Test fun unknownIsTheReleaseOneScore() {
        val a = cand("movie:1", Availability.SCORE_UNKNOWN); val b = Recommender.Cand("movie:1", "movie:1", a.genres, "en", 2020, 400, 7.5, 500)
        assertEquals(Recommender.base(b, Recommender.Profile()), Recommender.base(a, Recommender.Profile()), 1e-12)
    }
}
