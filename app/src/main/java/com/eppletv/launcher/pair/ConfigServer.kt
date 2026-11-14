package com.eppletv.launcher.pair

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The temporary setup server a phone talks to while the TV shows a QR code.
 *
 * It listens only on the TV's private LAN address (never 0.0.0.0) on a random port, answers only
 * private-range clients whose Host header names that exact address, and stops itself when the
 * session ends or expires. One accept thread plus at most [MAX_HANDLERS] short-lived handler
 * threads; extra connections are dropped. Requests are capped in size and time. Paths, headers
 * and bodies are never logged, because the path carries the pairing token and bodies carry keys.
 *
 * Routes:
 *   GET  /p/{token}        the setup page (only while the token is unspent)
 *   POST /p/{token}/pair   ask to pair; returns the 4-digit code and a session secret
 *   GET  /s/state          paired phone polls; header X-Epple-Session
 *   POST /s/save           form body with nonce and keys; header X-Epple-Session
 *   POST /s/photo          one JPEG for the Home wallpapers, or the profile picture (X-Epple-Kind: profile);
 *                          headers X-Epple-Session and X-Epple-Nonce.
 *                          The larger body limit applies only after the session secret checks out.
 */
class ConfigServer(
    private val session: PairingSession,
    private val address: InetAddress,
    private val handler: Handler,
) {
    interface Handler {
        /** The setup page; inline script and style must carry [cspNonce]. */
        fun page(cspNonce: String): String
        /** Validates and stores the submitted values; returns one result per field it handled. */
        fun save(fields: Map<String, String>): List<Result>
        /** Validates and stores one picture (JPEG bytes, at most [MAX_PHOTO]); [kind] is "wallpaper" or "profile". */
        fun photo(bytes: ByteArray, kind: String): Result = Result("photo", false, "Not supported")
        /** Called once when the server stops, with the reason if it failed. */
        fun stopped(error: Throwable?) {}
    }

    data class Result(val field: String, val ok: Boolean, val message: String)

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var stopped = false
    private val requests = AtomicInteger()
    private val random = SecureRandom()
    private val pool = ThreadPoolExecutor(0, MAX_HANDLERS, 5, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "epple-pair-handler").apply { isDaemon = true }
    }

    val port: Int get() = socket?.localPort ?: -1
    val origin: String get() = "http://${address.hostAddress}:$port"
    /** The link inside the QR code. */
    val link: String get() = "$origin/p/${session.token}"

    /** Binds and starts accepting. Throws IOException if no port could be bound. */
    fun start() {
        require(address is Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress)) { "Not a private address" }
        var last: IOException? = null
        for (attempt in 0 until 3) {
            try {
                socket = ServerSocket(0, 4, address).apply { soTimeout = 1000 }
                break
            } catch (e: BindException) { last = e }
        }
        val s = socket ?: throw (last ?: IOException("Could not bind"))
        Thread({ acceptLoop(s) }, "epple-pair-accept").apply { isDaemon = true }.start()
    }

    fun stop() = stop(null)

    private fun stop(error: Throwable?) {
        synchronized(this) { if (stopped) return; stopped = true }
        try { socket?.close() } catch (_: IOException) {}
        pool.shutdownNow()
        session.close()
        handler.stopped(error)
    }

    private fun acceptLoop(s: ServerSocket) {
        var error: Throwable? = null
        try {
            while (!stopped && session.isLive) {
                val c = try { s.accept() } catch (_: SocketTimeoutException) { continue }
                if (!allowedClient(c.inetAddress) || requests.incrementAndGet() > MAX_REQUESTS) { quietClose(c); continue }
                try { pool.execute { serve(c) } } catch (_: Exception) { quietClose(c) }
            }
        } catch (e: IOException) {
            if (!stopped) error = e     // socket died: network lost or interface gone
        }
        stop(error)
    }

    private fun allowedClient(a: InetAddress) =
        a is Inet4Address && (a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress)

    private fun serve(c: Socket) {
        try {
            c.soTimeout = READ_TIMEOUT_MS
            val deadline = System.nanoTime() + TOTAL_TIMEOUT_MS * 1_000_000L
            val from = c.inetAddress.hostAddress ?: ""
            val req = read(c.getInputStream(), deadline, from)
            val reply = if (req == null) Reply(400, text("Bad request")) else route(req, from)
            write(c, reply)
        } catch (_: Exception) {
            // Timeouts, resets and malformed input end the connection; nothing is logged.
        } finally {
            quietClose(c)
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private class Reply(val status: Int, val body: Body, val extra: Map<String, String> = emptyMap())
    private class Body(val type: String, val bytes: ByteArray)

    private fun route(r: Request, from: String): Reply {
        if (r.headers["host"] != "${address.hostAddress}:$port") return Reply(421, text("Wrong host"))
        val origin = r.headers["origin"]
        if (origin != null && origin != this.origin) return Reply(403, text("Wrong origin"))
        val p = r.path.substringBefore('?')
        return when {
            // The page removes the token from the address bar, so a reload lands here.
            p == "/" -> if (r.method == "GET") Reply(410, html(GONE_PAGE)) else Reply(405, text("Method not allowed"))
            p.startsWith("/p/") && !p.endsWith("/pair") -> {
                if (r.method != "GET") return Reply(405, text("Method not allowed"))
                val t = p.removePrefix("/p/")
                if (!session.pageAllowed(t)) return Reply(410, html(GONE_PAGE))
                val nonce = randomId()
                Reply(200, html(handler.page(nonce)), mapOf("Content-Security-Policy" to csp(nonce)))
            }
            p.startsWith("/p/") && p.endsWith("/pair") -> {
                if (r.method != "POST") return Reply(405, text("Method not allowed"))
                when (val res = session.pair(p.removePrefix("/p/").removeSuffix("/pair"), from)) {
                    is PairingSession.Pairing.Started -> Reply(200, json("""{"code":"${res.code}","secret":"${res.secret}"}"""))
                    PairingSession.Pairing.Busy -> Reply(409, json("""{"error":"busy"}"""))
                    PairingSession.Pairing.Gone -> Reply(410, json("""{"error":"gone"}"""))
                    PairingSession.Pairing.Unknown -> Reply(404, json("""{"error":"unknown"}"""))
                }
            }
            p == "/s/state" -> {
                if (r.method != "GET") return Reply(405, text("Method not allowed"))
                val (state, nonce) = session.status(r.headers["x-epple-session"], from)
                    ?: return Reply(403, json("""{"error":"forbidden"}"""))
                val n = if (nonce != null) ""","nonce":"$nonce"""" else ""
                Reply(200, json("""{"state":"${state.name.lowercase()}"$n}"""))
            }
            p == "/s/save" -> {
                if (r.method != "POST") return Reply(405, text("Method not allowed"))
                if (r.headers["content-type"]?.substringBefore(';')?.trim() != FORM) return Reply(415, json("""{"error":"type"}"""))
                val fields = parseForm(String(r.body, Charsets.UTF_8)) ?: return Reply(400, json("""{"error":"form"}"""))
                gated(session.claimSave(r.headers["x-epple-session"], from, fields["nonce"])) {
                    try { handler.save(fields - "nonce") } catch (_: Exception) { emptyList() }
                }
            }
            p == "/s/photo" -> {
                if (r.method != "POST") return Reply(405, text("Method not allowed"))
                if (r.headers["content-type"]?.substringBefore(';')?.trim() != JPEG) return Reply(415, json("""{"error":"type"}"""))
                if (r.body.isEmpty()) return Reply(400, json("""{"error":"empty"}"""))
                gated(session.claimSave(r.headers["x-epple-session"], from, r.headers["x-epple-nonce"], photo = true)) {
                    val kind = if (r.headers["x-epple-kind"] == "profile") "profile" else "wallpaper"
                    listOf(try { handler.photo(r.body, kind) } catch (_: Exception) { Result("photo", false, "Couldn't save this picture") })
                }
            }
            else -> Reply(404, text("Not found"))
        }
    }

    /** The reply for a claimed save: runs [work] only when the gate is OK, then hands the phone its next nonce. */
    private fun gated(g: PairingSession.Gate, work: () -> List<Result>): Reply = when (g) {
        PairingSession.Gate.FORBIDDEN -> Reply(403, json("""{"error":"forbidden"}"""))
        PairingSession.Gate.GONE -> Reply(410, json("""{"error":"gone"}"""))
        PairingSession.Gate.STALE -> Reply(409, json("""{"error":"stale"}"""))
        PairingSession.Gate.TOO_MANY -> Reply(429, json("""{"error":"limit"}"""))
        PairingSession.Gate.OK -> {
            val results = work()
            val next = session.nextNonce()
            val list = results.joinToString(",") {
                """{"field":${quote(it.field)},"ok":${it.ok},"message":${quote(it.message)}}"""
            }
            Reply(200, json("""{"results":[$list],"nonce":${next?.let { quote(it) } ?: "null"}}"""))
        }
    }

    private fun read(input: InputStream, start: Long, from: String): Request? {
        var deadline = start
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            if (System.nanoTime() > deadline || head.size() >= MAX_HEAD) return null
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            if (l.isEmpty()) continue
            val i = l.indexOf(':')
            if (i <= 0) return null
            headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) return null
        // Only the paired phone may send a picture-sized body, and it gets more time for it.
        val photo = parts[0] == "POST" && parts[1] == "/s/photo" && session.status(headers["x-epple-session"], from) != null
        if (photo) deadline += PHOTO_EXTRA_MS * 1_000_000L
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > (if (photo) MAX_PHOTO else MAX_BODY)) return null
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            if (System.nanoTime() > deadline) return null
            val n = input.read(body, got, length - got)
            if (n < 0) return null
            got += n
        }
        return Request(parts[0], parts[1], headers, body)
    }

    private fun write(c: Socket, r: Reply) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
        head.append("Content-Type: ${r.body.type}\r\n")
        head.append("Content-Length: ${r.body.bytes.size}\r\n")
        head.append("Cache-Control: no-store\r\nPragma: no-cache\r\n")
        head.append("Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n")
        if (!r.extra.containsKey("Content-Security-Policy")) head.append("Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n")
        for ((k, v) in r.extra) head.append("$k: $v\r\n")
        head.append("Connection: close\r\n\r\n")
        val out = c.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(r.body.bytes)
        out.flush()
    }

    private fun csp(nonce: String) =
        "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; connect-src 'self'; " +
            "img-src data: blob:; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

    private fun randomId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun text(s: String) = Body("text/plain; charset=utf-8", s.toByteArray())
    private fun html(s: String) = Body("text/html; charset=utf-8", s.toByteArray())
    private fun json(s: String) = Body("application/json", s.toByteArray())

    private fun quietClose(c: Socket) { try { c.close() } catch (_: IOException) {} }

    companion object {
        const val MAX_HANDLERS = 3
        const val MAX_REQUESTS = 400
        const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 4 * 1024
        const val MAX_PHOTO = 6 * 1024 * 1024
        const val PHOTO_EXTRA_MS = 45_000L
        const val READ_TIMEOUT_MS = 10_000
        const val TOTAL_TIMEOUT_MS = 15_000L
        private const val FORM = "application/x-www-form-urlencoded"
        private const val JPEG = "image/jpeg"

        private val GONE_PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            |<title>Epple TV</title><h1>This code has ended</h1>
            |<p>Choose <b>New code</b> on your TV and scan again.</p>""".trimMargin()

        /** Strict application/x-www-form-urlencoded parser; null on duplicates or malformed input. */
        internal fun parseForm(s: String): Map<String, String>? {
            if (s.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in s.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) return null
                val k = try { URLDecoder.decode(pair.substring(0, i), "UTF-8") } catch (_: Exception) { return null }
                val v = try { URLDecoder.decode(pair.substring(i + 1), "UTF-8") } catch (_: Exception) { return null }
                if (k.length > 32 || v.length > 512 || out.containsKey(k)) return null
                out[k] = v
            }
            return out
        }

        internal fun quote(s: String): String {
            val b = StringBuilder("\"")
            for (ch in s) when {
                ch == '"' -> b.append("\\\"")
                ch == '\\' -> b.append("\\\\")
                ch < ' ' || ch == '<' || ch == '>' || ch == '&' || ch.code > 126 -> b.append(String.format("\\u%04x", ch.code))
                else -> b.append(ch)
            }
            return b.append('"').toString()
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            409 -> "Conflict"; 410 -> "Gone"; 415 -> "Unsupported Media Type"; 421 -> "Misdirected Request"
            429 -> "Too Many Requests"; else -> "Error"
        }
    }
}
