package com.eppletv.launcher.pair

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.eppletv.launcher.content.Credentials
import com.eppletv.launcher.content.Net

/**
 * The TV side of phone setup: finds the LAN address, starts a [PairingSession] and its
 * [ConfigServer], and turns their events into a [phase] the setup screen draws.
 * All public members are used on the main thread; [onChange] runs there too.
 * A new code (or stop) bumps a generation number, so late events from an old code are ignored.
 */
class PhoneLink(private val ctx: Context, private val net: Net, private val wallpapers: com.eppletv.launcher.home.Wallpapers,
                private val profile: com.eppletv.launcher.home.Profile, private val saver: com.eppletv.launcher.home.Wallpapers,
                private val onChange: () -> Unit) {
    enum class Phase { IDLE, STARTING, NO_NETWORK, FAILED, WAITING, CONFIRMING, PAIRED, DENIED, EXPIRED, LOST, DONE }
    class Saved(val key: Credentials.Key, val check: Credentials.Check)

    private val main = Handler(Looper.getMainLooper())
    var phase = Phase.IDLE
        private set
    var qr: Qr? = null
        private set
    var code: String? = null
        private set
    /** Results of saves from the phone, newest last. */
    val saved = ArrayList<Saved>()
    var onSaved: ((Saved) -> Unit)? = null
    /** Pictures added to Home from the phone in this session, and the last refusal message, if any. */
    var photos = 0
        private set
    var photoError: String? = null
        private set
    /** Pictures added since the setup screen opened (survives New Code), for the Home screen to show the newest. */
    var photosTotal = 0
    /** The profile picture was set from the phone in this session. */
    var profileSet = false
        private set
    private var session: PairingSession? = null
    private var server: ConfigServer? = null
    private var gen = 0

    val remainingMs: Long get() = session?.remainingMs() ?: 0
    val live get() = phase == Phase.WAITING || phase == Phase.CONFIRMING || phase == Phase.PAIRED

    /** Shows a new code: any previous session and server are stopped first. */
    fun start() {
        stop()
        val g = gen
        phase = Phase.STARTING
        onChange()
        Thread({
            val ip = LanAddress.find(ctx)
            if (ip == null) { post(g) { phase = Phase.NO_NETWORK }; return@Thread }
            val s = PairingSession()
            val srv = ConfigServer(s, ip, handler(g))
            val q = try { srv.start(); Qr.encode(srv.link) } catch (_: Exception) {
                srv.stop(); post(g) { phase = Phase.FAILED }; return@Thread
            }
            s.listener = { post(g) { sync(s) } }
            main.post {
                if (gen != g) { srv.stop(); return@post }
                session = s; server = srv; qr = q
                sync(s); onChange()
            }
        }, "epple-pair-start").start()
    }

    fun allow() = session?.allow()
    fun deny() = session?.deny()

    /** Setup is done with the phone: ends the session and stops the server. */
    fun finish() { session?.finish(); stop() }

    /** Stops listening. Safe to call at any time. */
    fun stop() {
        gen++
        server?.stop()
        server = null; session = null; qr = null; code = null
        saved.clear(); photos = 0; photoError = null; profileSet = false
        phase = Phase.IDLE
    }

    private fun post(g: Int, block: () -> Unit) = main.post { if (gen == g) { block(); onChange() } }

    private fun sync(s: PairingSession) {
        code = s.code
        phase = when (s.state) {
            PairingSession.State.WAITING -> Phase.WAITING
            PairingSession.State.CONFIRMING -> Phase.CONFIRMING
            PairingSession.State.PAIRED -> Phase.PAIRED
            PairingSession.State.DENIED -> Phase.DENIED
            PairingSession.State.EXPIRED -> Phase.EXPIRED
            PairingSession.State.DONE -> Phase.DONE
            PairingSession.State.CLOSED -> if (phase == Phase.LOST) Phase.LOST else Phase.EXPIRED
        }
    }

    private fun handler(g: Int) = object : ConfigServer.Handler {
        override fun page(cspNonce: String) = PhonePage.html(cspNonce)

        /** Runs on a server handler thread: validates each submitted key with one request, stores the good ones. */
        override fun save(fields: Map<String, String>): List<ConfigServer.Result> {
            val out = ArrayList<ConfigServer.Result>()
            for (k in Credentials.Key.values()) {
                val field = k.name.lowercase()
                val v = fields[field]?.trim() ?: continue
                if (v.isEmpty()) continue
                val check = if (v.length > 400 || v.any { it.code !in 0x21..0x7E }) Credentials.Check(false, "That doesn't look like a key")
                    else Credentials.validate(net, k, v)
                if (check.ok) Credentials.set(k, v)
                out += ConfigServer.Result(field, check.ok, if (check.ok) "Connected and saved" else "${check.message}. Not saved")
                val item = Saved(k, check)
                post(g) { saved += item; onSaved?.invoke(item) }
            }
            return out
        }

        /** Runs on a server handler thread: decodes, checks and re-encodes one picture into the wallpapers. */
        override fun photo(bytes: ByteArray, kind: String): ConfigServer.Result {
            if (kind == "profile") {
                val r = profile.set(bytes)
                post(g) { if (r.ok) { profileSet = true; photoError = null } else photoError = r.message }
                return ConfigServer.Result("photo", r.ok, r.message)
            }
            val r = if (kind == "screensaver") saver.add(bytes).let { if (it.ok) com.eppletv.launcher.home.Wallpapers.Result(true, "Added to the screensaver") else it } else wallpapers.add(bytes)
            post(g) { if (r.ok) { photos++; photosTotal++; photoError = null } else photoError = r.message }
            return ConfigServer.Result("photo", r.ok, r.message)
        }

        override fun stopped(error: Throwable?) { if (error != null) post(g) { phase = Phase.LOST } }
    }
}
