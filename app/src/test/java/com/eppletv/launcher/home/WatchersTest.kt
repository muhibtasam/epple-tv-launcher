package com.eppletv.launcher.home

import com.eppletv.launcher.home.Watchers.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions behind followed-title alerts: first-look baseline, what counts as new, and no repeats. */
class WatchersTest {
    @Test fun firstLookIsOnlyABaseline() {
        assertEquals(Change.NONE, Watchers.change(null, 3, 8))
        assertEquals(emptyList<String>(), Watchers.newMovies(null, listOf("1", "2")))
    }

    @Test fun newSeasonAndEpisode() {
        assertEquals(Change.SEASON, Watchers.change("2:10", 3, 1))
        assertEquals(Change.EPISODE, Watchers.change("3:1", 3, 2))
    }

    @Test fun nothingNewOrGoingBackIsNoAlert() {
        assertEquals(Change.NONE, Watchers.change("3:2", 3, 2))      // same episode again: no duplicate
        assertEquals(Change.NONE, Watchers.change("3:2", 3, 1))      // TMDB corrected an episode back
        assertEquals(Change.NONE, Watchers.change("3:2", 0, 0))      // incomplete record
        assertEquals(Change.NONE, Watchers.change("garbage", 3, 2))
    }

    @Test fun onlyUnseenReleasedMoviesAreNew() {
        assertEquals(listOf("3"), Watchers.newMovies(setOf("1", "2"), listOf("1", "2", "3")))
        assertEquals(emptyList<String>(), Watchers.newMovies(setOf("1", "2", "3"), listOf("1", "2", "3")))
    }

    @Test fun releasedMeansDateHasCome() {
        assertTrue(Watchers.out("2026-11-15", "2026-11-15"))
        assertTrue(Watchers.out("2024-05-01", "2026-11-15"))
        assertFalse(Watchers.out("2027-01-01", "2026-11-15"))
        assertFalse(Watchers.out("", "2026-11-15"))                   // announced, no date yet
    }
}
