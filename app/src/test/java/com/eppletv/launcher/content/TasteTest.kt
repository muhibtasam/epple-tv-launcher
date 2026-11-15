package com.eppletv.launcher.content

import com.eppletv.launcher.content.Taste.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind taste signals: confidence, trailers, other titles, 6-hour dedup, decay, unreadable data. */
class TasteTest {
    private val min = 60_000L

    @Test fun confidenceLevels() {
        // Opened from Epple TV, session names it: strong.
        assertEquals(Taste.STRONG, Taste.confidence("netflix", "netflix", "tv:1", "Dark", listOf("Dark"), 50 * min), 0.0)
        // Long session, no title reported: strong.
        assertEquals(Taste.STRONG, Taste.confidence("netflix", "netflix", "movie:1", "Dune", emptyList(), 120 * min), 0.0)
        // Nothing reported at all: medium.
        assertEquals(Taste.MEDIUM, Taste.confidence("netflix", "netflix", "movie:1", "Dune", emptyList(), 0), 0.0)
    }

    @Test fun startedInsideAppIsNeverCredited() {
        assertEquals(0.0, Taste.confidence("netflix", "netflix", null, null, listOf("Dark"), 50 * min), 0.0)
        assertEquals(0.0, Taste.confidence("prime", "netflix", "tv:1", "Dark", listOf("Dark"), 50 * min), 0.0)
    }

    @Test fun trailersAreRejected() {
        assertEquals(0.0, Taste.confidence("netflix", "netflix", "movie:1", "Dune", listOf("Dune"), 2 * min), 0.0)
    }

    @Test fun otherTitlesAreRejected() {
        assertEquals(0.0, Taste.confidence("netflix", "netflix", "movie:1", "Dune", listOf("Barbie"), 110 * min), 0.0)
        // A series' episode name differs from the show's: a long session still counts, at medium.
        assertEquals(Taste.MEDIUM, Taste.confidence("netflix", "netflix", "tv:1", "Dark", listOf("Secrets"), 50 * min), 0.0)
    }

    @Test fun sameTitleWithinSixHoursCountsOnce() {
        val t = 1_000_000_000L
        var ev = Taste.add(emptyList(), Event("tv:1", "Dark", 0.6, t, "watch"))
        ev = Taste.add(ev, Event("tv:1", "Dark", 0.6, t + 3600_000L, "watch"))
        assertEquals(1, ev.size)
        ev = Taste.add(ev, Event("tv:1", "Dark", 1.0, t + 7200_000L, "watch"))       // stronger replaces
        assertEquals(1, ev.size); assertEquals(1.0, ev[0].w, 0.0)
        ev = Taste.add(ev, Event("tv:1", "Dark", 1.0, t + Taste.DEDUP + 7200_000L, "watch"))
        assertEquals(2, ev.size)
    }

    @Test fun decay() {
        val t = 0L
        assertEquals(0.5, Taste.weight(Event("tv:1", "", 1.0, t, "watch"), t + Taste.HALF_LIFE), 1e-9)
        assertEquals(0.05, Taste.weight(Event("tv:1", "", 0.1, t, "details"), t + 7 * Taste.DAY), 1e-9)
        assertEquals(0.0, Taste.weight(Event("tv:1", "", 0.1, t, "details"), t + 15 * Taste.DAY), 0.0)
    }

    @Test fun unreadableDataStartsEmpty() {
        val d = Taste.parse("{not json")
        assertTrue(d.events.isEmpty() && d.dismissed.isEmpty() && d.shown.isEmpty())
        assertTrue(Taste.parse(null).events.isEmpty())
    }
}
