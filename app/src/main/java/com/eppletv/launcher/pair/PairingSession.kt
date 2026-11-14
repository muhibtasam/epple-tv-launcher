package com.eppletv.launcher.pair

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One phone-pairing attempt, shown on the TV as one QR code.
 *
 * The QR link carries a single-use pairing token. The first phone that opens it and asks to pair
 * gets a 4-digit code; the TV shows the same code with Allow and Deny. Only after Allow does the
 * phone's session secret work, and the pairing token is then spent. Every save needs the session
 * secret, the paired phone's address and a single-use nonce, so a captured request cannot be
 * replayed. API keys never pass through this class and nothing here is logged.
 *
 * Thread-safe. [listener] runs on whichever thread changed the state and must not block.
 */
class PairingSession(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
) {
    enum class State { WAITING, CONFIRMING, PAIRED, DONE, DENIED, EXPIRED, CLOSED }

    sealed class Pairing {
        data class Started(val code: String, val secret: String) : Pairing()
        object Busy : Pairing()        // another phone is already pairing
        object Gone : Pairing()        // token spent, denied, expired or closed
        object Unknown : Pairing()     // wrong token
    }

    enum class Gate { OK, FORBIDDEN, GONE, STALE, TOO_MANY }

    val token: String = randomId(16)
    private val created = now()
    @Volatile var state = State.WAITING
        private set
    /** Shown on the phone and the TV while [State.CONFIRMING]. */
    @Volatile var code: String? = null
        private set
    private var secret: String? = null
    private var client: String? = null
    private var nonce: String? = null
    private var lastActive = created
    private var saves = 0
    private var photos = 0
    var listener: (() -> Unit)? = null

    val isLive: Boolean get() { tick(); return state == State.WAITING || state == State.CONFIRMING || state == State.PAIRED }

    /** Milliseconds until this session expires if nothing else happens. */
    @Synchronized fun remainingMs(): Long {
        tick()
        return when (state) {
            State.WAITING, State.CONFIRMING -> PAIR_WINDOW_MS - (now() - created)
            State.PAIRED -> IDLE_MS - (now() - lastActive)
            else -> 0
        }.coerceAtLeast(0)
    }

    /** The setup page may be served only while the token is unspent. */
    @Synchronized fun pageAllowed(t: String): Boolean {
        tick()
        return same(t, token) && (state == State.WAITING || state == State.CONFIRMING)
    }

    @Synchronized fun pair(t: String, address: String): Pairing {
        tick()
        if (!same(t, token)) return Pairing.Unknown
        return when (state) {
            State.WAITING -> {
                client = address
                code = (1000 + random.nextInt(9000)).toString()
                secret = randomId(32)
                change(State.CONFIRMING)
                Pairing.Started(code!!, secret!!)
            }
            State.CONFIRMING -> if (address == client) Pairing.Started(code!!, secret!!) else Pairing.Busy
            else -> Pairing.Gone
        }
    }

    /** TV pressed Allow. */
    @Synchronized fun allow() {
        tick()
        if (state != State.CONFIRMING) return
        nonce = randomId(16)
        lastActive = now()
        change(State.PAIRED)
    }

    /** TV pressed Deny: this QR code is dead; the TV must show a new one. */
    @Synchronized fun deny() {
        tick()
        if (state == State.CONFIRMING) change(State.DENIED)
    }

    /** State for the paired phone's polling, or null if the caller is not that phone. */
    @Synchronized fun status(s: String?, address: String): Pair<State, String?>? {
        tick()
        if (!authorised(s, address)) return null
        if (state == State.PAIRED) lastActive = now()
        return state to (if (state == State.PAIRED) nonce else null)
    }

    /** Claims the current nonce for one save. A second request with the same nonce gets STALE. */
    /** [photo]: a wallpaper upload, counted against [MAX_PHOTOS] instead of [MAX_SAVES]. */
    @Synchronized fun claimSave(s: String?, address: String, n: String?, photo: Boolean = false): Gate {
        tick()
        if (!authorised(s, address)) return Gate.FORBIDDEN
        if (state != State.PAIRED) return Gate.GONE
        if (if (photo) photos >= MAX_PHOTOS else saves >= MAX_SAVES) return Gate.TOO_MANY
        val current = nonce
        if (current == null || n == null || !same(n, current)) return Gate.STALE
        nonce = null
        if (photo) photos++ else saves++
        lastActive = now()
        return Gate.OK
    }

    /** A fresh nonce after a save finished, or null if the session ended meanwhile. */
    @Synchronized fun nextNonce(): String? {
        tick()
        if (state != State.PAIRED) return null
        nonce = randomId(16)
        return nonce
    }

    /** Setup finished on the TV. */
    @Synchronized fun finish() { if (live()) change(State.DONE) }

    /** TV left the screen, asked for a new code, or the server stopped. */
    @Synchronized fun close() { if (live()) change(State.CLOSED) }

    private fun live() = state == State.WAITING || state == State.CONFIRMING || state == State.PAIRED

    private fun authorised(s: String?, address: String): Boolean {
        val mine = secret ?: return false
        return s != null && same(s, mine) && address == client
    }

    @Synchronized private fun tick() {
        val t = now()
        val expired = when (state) {
            State.WAITING, State.CONFIRMING -> t - created > PAIR_WINDOW_MS
            State.PAIRED -> t - lastActive > IDLE_MS
            else -> false
        }
        if (expired) change(State.EXPIRED)
    }

    private fun change(s: State) {
        state = s
        if (s != State.CONFIRMING && s != State.PAIRED) { nonce = null; if (s != State.DENIED) code = null }
        listener?.invoke()
    }

    private fun randomId(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val PAIR_WINDOW_MS = 5 * 60_000L
        const val IDLE_MS = 10 * 60_000L
        const val MAX_SAVES = 10
        const val MAX_PHOTOS = 30
    }
}
