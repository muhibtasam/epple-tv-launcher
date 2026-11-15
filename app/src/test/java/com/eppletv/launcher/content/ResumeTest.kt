package com.eppletv.launcher.content

import com.eppletv.launcher.home.ContinueWatching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Continue Watching correctness: metadata fallback, one request per lookup, completion, progress, the 14-day window. */
class ResumeTest {
    private val min = 60_000L
    private val day = 24 * 60 * min
    private val lanterns = Resume.Series("A series.", 1, 10, mapOf(1 to 10))

    @Test fun providerMissingFallsBackToTmdb() =
        assertEquals("TMDB overview", Resume.fill(null, "", "TMDB overview"))

    @Test fun partialDataKeepsProviderValues() {
        assertEquals("Stremio text", Resume.fill("Stremio text", "TMDB overview"))   // provider first
        assertEquals("Pilot", Resume.fill("", "Pilot"))                             // only the missing field is filled
    }

    @Test fun bothProvidersMissing() = assertEquals("", Resume.fill(null, null, "  "))

    @Test fun concurrentLookupsMakeOneRequest() {
        val hits = AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x -> hits.incrementAndGet(); Thread.sleep(150); val b = "{}".toByteArray()
            x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) } }
        server.executor = Executors.newFixedThreadPool(4); server.start()
        try {
            val net = Net(File(System.getProperty("java.io.tmpdir"), "resume-net-" + System.nanoTime()).apply { mkdirs() })
            val url = "http://127.0.0.1:${server.address.port}/find"
            val pool = Executors.newFixedThreadPool(6); val done = CountDownLatch(6)
            repeat(6) { pool.execute { try { net.cached("tmdb-imdb-tt1", 60_000, url) } finally { done.countDown() } } }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, hits.get())
            assertEquals("{}", net.stored("tmdb-imdb-tt1"))          // the offline pass reads it without a request
            assertEquals(1, hits.get())
            pool.shutdown()
        } finally { server.stop(0) }
    }

    @Test fun duplicateEpisodeRecordsShareOneIdentity() {
        val rows = listOf("Lanterns", "  lanterns ", "LANTERNS").map { "com.stremio.one|" + ContinueWatching.norm(it) }
        assertEquals(1, rows.distinct().size)
        assertEquals(Resume.key("com.stremio.one", "Lanterns"), Resume.key("com.stremio.one", " LANTERNS "))
    }

    @Test fun finishedWithNothingAiredIsRemoved() {
        // VisionQuest-style: last episode watched to the end, no later episode out yet.
        val vq = Resume.Series("", 1, 8, mapOf(1 to 8))
        val saved = Resume.Saved(66 * min, 69 * min, "Finale", 0)
        assertNull(Resume.decide(false, 1, 8, 301, 69 * min, saved, 1 to 8, vq))
        // Listed ahead of its air date (app keeps "up next" at 0 ms): left out.
        assertNull(Resume.decide(false, 1, 9, 301, 69 * min, null, null, vq))
        // A failed lookup never removes or completes it.
        assertNotNull(Resume.decide(false, 1, 8, 301, 69 * min, null, null, null))
        // A finished movie leaves the row.
        assertNull(Resume.decide(true, null, null, 0, 0, Resume.Saved(118 * min, 120 * min, "", 0), null, null))
    }

    @Test fun lanternsTenMinutesShowsProgress() {
        // Stremio's row is stale (S1E2 at 1 ms); the session saved S1E9 at 10 minutes.
        val s = Resume.decide(false, 1, 2, 1, 56 * min, Resume.Saved(10 * min, 56 * min, "Episode Name", 0), 1 to 9, lanterns)!!
        assertEquals(9, s.e)
        val p = Resume.progress(s.position, s.duration)
        assertTrue(p > 0.17f && p < 0.18f)
        assertEquals("S1 E9", Resume.label(s.s, s.e))
        // No duration known: no bar rather than a misleading one.
        assertEquals(-1f, Resume.progress(10 * min, 0), 0f)
    }

    @Test fun finishedEpisodeAdvancesToNext() {
        val s = Resume.decide(false, 1, 2, 1, 56 * min, Resume.Saved(55 * min, 56 * min, "x", 0), 1 to 9, lanterns)!!
        assertEquals(1 to 10, s.s to s.e)
        assertEquals(-1f, Resume.progress(s.position, s.duration), 0f)
        // Season end rolls to the next season only if it has aired.
        assertEquals(2 to 1, Resume.next(1, 10, Resume.Series("", 2, 3, mapOf(1 to 10, 2 to 8))))
        assertNull(Resume.next(1, 10, lanterns))
    }

    @Test fun insideWindowIncluded() {
        val tz = TimeZone.getTimeZone("Asia/Karachi"); val now = 1_763_280_000_000L
        assertTrue(Resume.inWindow(now - 2 * day, now, tz))
        assertTrue(Resume.inWindow(Resume.cutoff(now, tz), now, tz))           // start of the 14th day counts
    }

    @Test fun olderThanWindowExcluded() {
        val tz = TimeZone.getTimeZone("Asia/Karachi"); val now = 1_763_280_000_000L
        assertFalse(Resume.inWindow(now - 15 * day, now, tz))
        assertFalse(Resume.inWindow(Resume.cutoff(now, tz) - 1, now, tz))
        assertFalse(Resume.inWindow(0, now, tz))                                // no activity time at all
    }

    @Test fun cutoffRollsWithTheDay() {
        val tz = TimeZone.getTimeZone("Asia/Karachi"); val now = 1_763_280_000_000L
        assertEquals(day, Resume.cutoff(now + day, tz) - Resume.cutoff(now, tz))
        val t = Resume.cutoff(now, tz)                     // included today, excluded tomorrow
        assertTrue(Resume.inWindow(t, now, tz)); assertFalse(Resume.inWindow(t, now + day, tz))
        // Same instant, other zone: the local day decides.
        assertTrue(Resume.cutoff(now, TimeZone.getTimeZone("UTC")) != Resume.cutoff(now, tz))
    }

    @Test fun savedProgressSurvivesRestart() {
        val v = Resume.Saved(600_000, 3_388_636, "Pilot; Part 1", 1_763_280_000_000L)
        val back = Resume.decode(Resume.encode(v))!!
        assertEquals(v.position, back.position); assertEquals(v.duration, back.duration)
        assertEquals(v.name, back.name); assertEquals(v.at, back.at)
        assertNull(Resume.decode("garbage")); assertNull(Resume.decode(null))
    }
}
