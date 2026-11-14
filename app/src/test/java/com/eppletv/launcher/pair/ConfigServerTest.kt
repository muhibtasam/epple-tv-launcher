package com.eppletv.launcher.pair

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConfigServerTest {
    private var clock = 0L
    private val session = PairingSession(now = { clock })
    private val saved = mutableListOf<Map<String, String>>()
    private val stopped = CountDownLatch(1)
    private val handler = object : ConfigServer.Handler {
        override fun page(cspNonce: String) = "<!doctype html><script nonce=\"$cspNonce\">1</script>"
        override fun save(fields: Map<String, String>): List<ConfigServer.Result> {
            saved += fields
            return fields.keys.map { ConfigServer.Result(it, true, "Connected") }
        }
        override fun photo(bytes: ByteArray): ConfigServer.Result { photos += String(bytes); return ConfigServer.Result("photo", true, "Added to Home") }
        override fun stopped(error: Throwable?) { stopped.countDown() }
    }
    private val photos = mutableListOf<String>()
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val server = ConfigServer(session, loopback, handler).also { it.start() }
    private val host get() = "127.0.0.1:${server.port}"

    @After fun tearDown() = server.stop()

    private class Resp(val status: Int, val headers: Map<String, String>, val body: String)

    private fun http(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = "",
                     hostHeader: String = host, length: Int? = null): Resp? {
        Socket(loopback, server.port).use { s ->
            s.soTimeout = 5000
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $hostHeader\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (method == "POST") sb.append("Content-Length: ${length ?: body.toByteArray().size}\r\n")
            sb.append("\r\n").append(body)
            s.getOutputStream().write(sb.toString().toByteArray())
            val raw = ByteArrayOutputStream()
            try { s.getInputStream().copyTo(raw) } catch (_: Exception) {}
            val text = raw.toString("UTF-8")
            if (text.isEmpty()) return null
            val head = text.substringBefore("\r\n\r\n").split("\r\n")
            val hs = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Resp(head[0].split(' ')[1].toInt(), hs, text.substringAfter("\r\n\r\n"))
        }
    }

    private fun field(json: String, name: String) = Regex("\"$name\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private val form = mapOf("Content-Type" to "application/x-www-form-urlencoded")

    /** Pairs, allows on the "TV", returns (secret, nonce). */
    private fun paired(): Pair<String, String> {
        val r = http("POST", "/p/${session.token}/pair")!!
        assertEquals(200, r.status)
        val secret = field(r.body, "secret")!!
        session.allow()
        val st = http("GET", "/s/state", mapOf("X-Epple-Session" to secret))!!
        assertEquals("paired", field(st.body, "state"))
        return secret to field(st.body, "nonce")!!
    }

    private val jpeg = mapOf("Content-Type" to "image/jpeg")

    @Test fun photoFlowUsesNonceOnce() {
        val (secret, nonce) = paired()
        val h = jpeg + ("X-Epple-Session" to secret) + ("X-Epple-Nonce" to nonce)
        val r = http("POST", "/s/photo", h, "fakejpeg")!!
        assertEquals(200, r.status)
        assertEquals(listOf("fakejpeg"), photos)
        assertTrue(r.body.contains("\"field\":\"photo\",\"ok\":true"))
        assertNotNull(field(r.body, "nonce"))
        assertEquals(409, http("POST", "/s/photo", h, "again")!!.status)
        assertEquals(1, photos.size)
    }

    @Test fun photoNeedsJpegTypeAndSecret() {
        val (secret, nonce) = paired()
        assertEquals(415, http("POST", "/s/photo", mapOf("X-Epple-Session" to secret, "X-Epple-Nonce" to nonce, "Content-Type" to "image/png"), "x")!!.status)
        assertEquals(403, http("POST", "/s/photo", jpeg + ("X-Epple-Nonce" to nonce), "x")!!.status)
        assertTrue(photos.isEmpty())
    }

    @Test fun largeBodyOnlyForPairedPhone() {
        val (secret, nonce) = paired()
        // Without the secret a picture-sized body is refused before it is read.
        assertEquals(400, http("POST", "/s/photo", jpeg + ("X-Epple-Nonce" to nonce), "", length = 100_000)!!.status)
        val big = "a".repeat(200_000)
        assertEquals(200, http("POST", "/s/photo", jpeg + ("X-Epple-Session" to secret) + ("X-Epple-Nonce" to nonce), big)!!.status)
        assertEquals(200_000, photos.single().length)
        // Never more than MAX_PHOTO, even for the paired phone.
        assertEquals(400, http("POST", "/s/photo", jpeg + ("X-Epple-Session" to secret), "", length = ConfigServer.MAX_PHOTO + 1)!!.status)
    }

    @Test fun rootAfterReplaceStateIsGone() {
        assertEquals(410, http("GET", "/")!!.status)
    }

    @Test fun linkUsesBoundAddressPortAndToken() {
        assertEquals("http://127.0.0.1:${server.port}/p/${session.token}", server.link)
        assertTrue(server.port in 1024..65535)
    }

    @Test fun pageHasSecurityHeaders() {
        val r = http("GET", "/p/${session.token}")!!
        assertEquals(200, r.status)
        assertEquals("no-store", r.headers["cache-control"])
        assertEquals("no-referrer", r.headers["referrer-policy"])
        assertEquals("nosniff", r.headers["x-content-type-options"])
        val csp = r.headers["content-security-policy"]!!
        val nonce = Regex("'nonce-([^']+)'").find(csp)!!.groupValues[1]
        assertTrue(r.body.contains("nonce=\"$nonce\""))
        assertTrue(csp.contains("default-src 'none'") && csp.contains("connect-src 'self'"))
    }

    @Test fun wrongTokenGetsGonePage() {
        assertEquals(410, http("GET", "/p/nope")!!.status)
    }

    @Test fun fullFlowSavesFieldsWithoutNonce() {
        val (secret, nonce) = paired()
        assertEquals(410, http("GET", "/p/${session.token}")!!.status)        // token spent
        val r = http("POST", "/s/save", form + ("X-Epple-Session" to secret), "nonce=$nonce&tmdb=abc%2B1")!!
        assertEquals(200, r.status)
        assertEquals(listOf(mapOf("tmdb" to "abc+1")), saved)
        assertTrue(r.body.contains("\"field\":\"tmdb\",\"ok\":true"))
        assertNotNull(field(r.body, "nonce"))
    }

    @Test fun replayedSaveIsRejected() {
        val (secret, nonce) = paired()
        val body = "nonce=$nonce&tmdb=abc"
        assertEquals(200, http("POST", "/s/save", form + ("X-Epple-Session" to secret), body)!!.status)
        assertEquals(409, http("POST", "/s/save", form + ("X-Epple-Session" to secret), body)!!.status)
        assertEquals(1, saved.size)
    }

    @Test fun saveNeedsSecretAndFormType() {
        val (secret, nonce) = paired()
        assertEquals(403, http("POST", "/s/save", form, "nonce=$nonce&tmdb=x")!!.status)
        assertEquals(403, http("POST", "/s/save", form + ("X-Epple-Session" to "bad"), "nonce=$nonce")!!.status)
        assertEquals(415, http("POST", "/s/save", mapOf("X-Epple-Session" to secret), "nonce=$nonce")!!.status)
        assertTrue(saved.isEmpty())
    }

    @Test fun saveBeforeTvAllowsIsRejected() {
        val r = http("POST", "/p/${session.token}/pair")!!
        val secret = field(r.body, "secret")!!
        assertEquals("confirming", field(http("GET", "/s/state", mapOf("X-Epple-Session" to secret))!!.body, "state"))
        assertEquals(410, http("POST", "/s/save", form + ("X-Epple-Session" to secret), "nonce=x&tmdb=y")!!.status)
    }

    @Test fun deniedPhoneSeesDenied() {
        val secret = field(http("POST", "/p/${session.token}/pair")!!.body, "secret")!!
        session.deny()
        // Deny ends the session, so the server stops; either a "denied" answer or a closed port is fine.
        val r = try { http("GET", "/s/state", mapOf("X-Epple-Session" to secret)) } catch (_: Exception) { null }
        if (r != null) assertEquals("denied", field(r.body, "state"))
    }

    @Test fun hostOriginMethodAndPathAreChecked() {
        assertEquals(421, http("GET", "/p/${session.token}", hostHeader = "evil.example:80")!!.status)
        assertEquals(403, http("POST", "/p/${session.token}/pair", mapOf("Origin" to "http://evil.example"))!!.status)
        assertEquals(405, http("GET", "/s/save")!!.status)
        assertEquals(404, http("GET", "/admin")!!.status)
    }

    @Test fun oversizedAndChunkedRequestsAreRefused() {
        assertEquals(400, http("POST", "/s/save", form, "", length = ConfigServer.MAX_BODY + 1)!!.status)
        assertEquals(400, http("POST", "/s/save", form + ("Transfer-Encoding" to "chunked"), "")!!.status)
    }

    @Test fun formParserIsStrict() {
        assertEquals(mapOf("a" to "1 2", "b" to ""), ConfigServer.parseForm("a=1+2&b="))
        assertEquals(null, ConfigServer.parseForm("a=1&a=2"))
        assertEquals(null, ConfigServer.parseForm("=x"))
        assertEquals(null, ConfigServer.parseForm("a=%zz"))
        assertEquals("\"a\\\"b\\u003c\"", ConfigServer.quote("a\"b<"))
    }

    @Test fun extraConnectionsBeyondHandlerLimitAreDropped() {
        val idle = (1..ConfigServer.MAX_HANDLERS).map { Socket(loopback, server.port) }
        Thread.sleep(300)
        val dropped = http("GET", "/p/${session.token}")
        assertEquals(null, dropped)
        idle.forEach { it.close() }
        Thread.sleep(300)
        assertEquals(200, http("GET", "/p/${session.token}")!!.status)
    }

    @Test fun serverStopsWhenSessionExpires() {
        clock = PairingSession.PAIR_WINDOW_MS + 1
        assertTrue(stopped.await(3, TimeUnit.SECONDS))
        assertFalse(session.isLive)
        val refused = try { Socket(loopback, server.port).close(); false } catch (_: Exception) { true }
        assertTrue(refused)
    }

    @Test fun stopIsIdempotentAndClosesSession() {
        server.stop(); server.stop()
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        assertEquals(PairingSession.State.CLOSED, session.state)
    }
}
