package com.eppletv.launcher.content

import com.eppletv.launcher.content.Recommender.Cand
import com.eppletv.launcher.content.Recommender.Feedback
import com.eppletv.launcher.content.Recommender.Profile
import com.eppletv.launcher.content.Recommender.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deterministic engine: formula, variety caps, near-duplicates, repeat skips, exclusions, cold start. */
class RecommenderTest {
    private val day = Recommender.DAY
    private val now = 1_800_000_000_000L
    private val crime = 80; private val drama = 18; private val comedy = 35; private val scifi = 878

    private fun cand(id: String, genres: List<Int>, seeds: Set<String> = emptySet(), lang: String = "en", year: Int = 2015,
                     rating: Double = 7.0, votes: Int = 1000, franchise: Int = 0, trending: Boolean = false, age: Int = 3000) =
        Cand(id, id, genres, lang, year, age, rating, votes, franchise, emptySet(), seeds, trending)

    private val profile = Profile(
        genres = mapOf(crime to 2.0, drama to 1.0), langs = mapOf("en" to 2.0),
        seeds = mapOf("tv:1" to Seed("tv:1", "Reacher", 1.0, "watch"), "tv:2" to Seed("tv:2", "Dark", 1.0, "watch")),
        genreNames = mapOf(crime to "Crime", drama to "Drama"))

    @Test fun formula() {
        val c = cand("movie:9", listOf(crime), seeds = setOf("tv:1"), rating = 8.0, votes = 500, age = 100)
        // seed 1/2, genre 1.0, people/franchise 0, service unknown 0.3, quality 0.8, fresh 1
        val want = 0.30 * 0.5 + 0.20 * 1.0 + 0.0 + 0.15 * 0.3 + 0.10 * 0.8 + 0.05 * 1.0
        assertEquals(want, Recommender.base(c, profile), 1e-9)
    }

    @Test fun varietyCapsInFirstTwelve() {
        val many = (1..15).map { cand("movie:$it", listOf(crime), seeds = setOf("tv:1")) } +
            (16..30).map { cand("movie:$it", listOf(drama, comedy), seeds = setOf("tv:2")) } +
            (31..45).map { cand("movie:$it", listOf(100 + it)) }        // enough other genres for the caps to hold
        val top = Recommender.order(many, profile, Feedback(), now).take(12)
        assertTrue(top.count { it.c.genres.first() == crime } <= 3)
        assertTrue(top.count { "tv:1" in it.c.seeds } <= 2)
        assertTrue(top.count { "tv:2" in it.c.seeds } <= 2)
        assertEquals(12, top.size)
    }

    @Test fun franchiseCap() {
        val many = (1..8).map { cand("movie:$it", listOf(crime), franchise = 7) } + (9..30).map { cand("movie:$it", listOf(100 + it)) }
        val p = Profile(genres = mapOf(crime to 1.0), franchises = mapOf(7 to 1.0))
        assertTrue(Recommender.order(many, p, Feedback(), now).take(12).count { it.c.franchise == 7 } <= 2)
    }

    @Test fun nearDuplicatesMoveDown() {
        // a and b score the same; b is a near copy of the first pick, c differs: c comes before b.
        val first = cand("movie:1", listOf(crime, drama), seeds = setOf("tv:1"), rating = 9.0)
        val b = cand("movie:2", listOf(crime, drama), lang = "en", year = 2015)
        val c = cand("movie:3", listOf(comedy), lang = "fr", year = 1985)
        val p = Profile(genres = mapOf(crime to 1.0, drama to 1.0, comedy to 1.0), seeds = profile.seeds)
        assertTrue(Recommender.base(b, p) >= Recommender.base(c, p))
        val order = Recommender.order(listOf(b, c, first), p, Feedback(), now).map { it.c.id }
        assertEquals(listOf("movie:1", "movie:3", "movie:2"), order)
    }

    @Test fun repeatSkips() {
        val (pen1, rest1) = Recommender.exposure(listOf(now - day), now)
        assertEquals(0.10, pen1, 1e-9); assertFalse(rest1)
        val (_, rest3) = Recommender.exposure(listOf(now - 10 * day, now - 5 * day, now - day), now)
        assertTrue(rest3)
        val (_, restOld) = Recommender.exposure(listOf(now - 50 * day, now - 45 * day, now - 40 * day), now)
        assertFalse(restOld)                                            // the 30-day rest is over
        val x = cand("movie:1", listOf(crime)); val y = cand("movie:2", listOf(crime))
        val out = Recommender.order(listOf(x, y), profile, Feedback(shown = mapOf("movie:1" to listOf(now - 3 * day, now - 2 * day, now - day))), now)
        assertEquals(listOf("movie:2"), out.map { it.c.id })
    }

    @Test fun exclusions() {
        val kept = cand("movie:1", listOf(crime)); val dismissed = cand("movie:2", listOf(crime)); val unreleased = cand("movie:3", listOf(crime), age = -10)
        val out = Recommender.order(listOf(kept, dismissed, unreleased), profile, Feedback(exclude = setOf("movie:2")), now)
        assertEquals(listOf("movie:1"), out.map { it.c.id })
    }

    @Test fun coldStartNeedsNoHistory() {
        val cands = (1..20).map { cand("movie:$it", listOf(it % 5 + 1), trending = it % 2 == 0, rating = 6.0 + it % 4) }
        val out = Recommender.order(cands, Profile(), Feedback(), now)
        assertEquals(20, out.size)
        assertTrue(out.all { it.reason == "Trending this week" || it.reason == "Highly rated" })
    }

    @Test fun reasons() {
        val c = cand("movie:9", listOf(crime), seeds = setOf("tv:1"))
        assertEquals("Because you watched Reacher" to "tv:1", Recommender.reason(c, profile))
        val g = cand("movie:8", listOf(crime))
        assertEquals("Because you like Crime", Recommender.reason(g, profile).first)
    }

    @Test fun deterministic() {
        val cands = (1..40).map { cand("movie:$it", listOf(listOf(crime, drama, comedy, scifi)[it % 4]), seeds = if (it % 3 == 0) setOf("tv:1") else emptySet(), rating = 5.0 + it % 5) }
        val a = Recommender.order(cands, profile, Feedback(), now).map { it.c.id }
        val b = Recommender.order(cands.reversed(), profile, Feedback(), now).map { it.c.id }
        assertEquals(a, b)
    }

    @Test fun unreadableDeckIsNoDeck() {
        assertNull(Recommender.parseDeck("{broken"))
        assertNull(Recommender.parseDeck(null))
    }

    @Test fun releaseAge() {
        assertEquals(-1, Recommender.ageDays("2027-01-02", java.time.LocalDate.parse("2027-01-01").toEpochDay() * day))
        assertEquals(100_000, Recommender.ageDays("", now))
    }
}
