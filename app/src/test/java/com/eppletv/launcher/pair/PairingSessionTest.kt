package com.eppletv.launcher.pair

import com.eppletv.launcher.pair.PairingSession.Gate
import com.eppletv.launcher.pair.PairingSession.Pairing
import com.eppletv.launcher.pair.PairingSession.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSessionTest {
    private var t = 0L
    private val s = PairingSession(now = { t })
    private val phone = "192.168.1.20"
    private val other = "192.168.1.30"

    private fun pairPhone(): Pairing.Started = s.pair(s.token, phone) as Pairing.Started

    @Test fun photosHaveTheirOwnCapAndShareTheNonceChain() {
        val p = pairPhone(); s.allow()
        var n = s.status(p.secret, phone)!!.second!!
        repeat(PairingSession.MAX_PHOTOS) {
            assertEquals(Gate.OK, s.claimSave(p.secret, phone, n, photo = true))
            if (it < PairingSession.MAX_PHOTOS - 1) assertEquals(Gate.STALE, s.claimSave(p.secret, phone, n, photo = true))
            n = s.nextNonce()!!
        }
        assertEquals(Gate.TOO_MANY, s.claimSave(p.secret, phone, n, photo = true))
        assertEquals(Gate.OK, s.claimSave(p.secret, phone, n))         // key saves are counted separately
        assertEquals(Gate.FORBIDDEN, s.claimSave(p.secret, other, s.nextNonce(), photo = true))
    }

    @Test fun tokenIsRandomAndLongEnough() {
        assertTrue(s.token.length >= 22)
        assertNotEquals(s.token, PairingSession().token)
    }

    @Test fun firstPhonePairsAndGetsFourDigitCode() {
        val p = pairPhone()
        assertTrue(p.code.matches(Regex("\\d{4}")))
        assertTrue(p.secret.length >= 40)
        assertEquals(State.CONFIRMING, s.state)
        assertEquals(p.code, s.code)
    }

    @Test fun wrongTokenIsUnknown() {
        assertEquals(Pairing.Unknown, s.pair("x" + s.token.drop(1), phone))
        assertEquals(State.WAITING, s.state)
    }

    @Test fun secondPhoneIsBusyWhileFirstIsConfirming() {
        pairPhone()
        assertEquals(Pairing.Busy, s.pair(s.token, other))
    }

    @Test fun samePhoneRetryGetsSameCode() {
        val a = pairPhone()
        assertEquals(a, s.pair(s.token, phone))
    }

    @Test fun secretDoesNothingUntilTvAllows() {
        val p = pairPhone()
        assertEquals(State.CONFIRMING to null, s.status(p.secret, phone))
        assertEquals(Gate.GONE, s.claimSave(p.secret, phone, "anything"))
    }

    @Test fun allowSpendsTokenAndIssuesNonce() {
        val p = pairPhone()
        s.allow()
        val (state, nonce) = s.status(p.secret, phone)!!
        assertEquals(State.PAIRED, state)
        assertNotNull(nonce)
        assertFalse(s.pageAllowed(s.token))
        assertEquals(Pairing.Gone, s.pair(s.token, other))
    }

    @Test fun denyKillsSession() {
        val p = pairPhone()
        s.deny()
        assertEquals(State.DENIED, s.status(p.secret, phone)!!.first)
        assertEquals(Pairing.Gone, s.pair(s.token, phone))
        assertFalse(s.isLive)
    }

    @Test fun nonceIsSingleUse() {
        val p = pairPhone(); s.allow()
        val n = s.status(p.secret, phone)!!.second
        assertEquals(Gate.OK, s.claimSave(p.secret, phone, n))
        assertEquals(Gate.STALE, s.claimSave(p.secret, phone, n))      // replay
        val next = s.nextNonce()
        assertNotEquals(n, next)
        assertEquals(Gate.OK, s.claimSave(p.secret, phone, next))
    }

    @Test fun otherDevicesAndWrongSecretsAreForbidden() {
        val p = pairPhone(); s.allow()
        val n = s.status(p.secret, phone)!!.second
        assertNull(s.status(p.secret, other))
        assertNull(s.status("wrong", phone))
        assertNull(s.status(null, phone))
        assertEquals(Gate.FORBIDDEN, s.claimSave(p.secret, other, n))
        assertEquals(Gate.FORBIDDEN, s.claimSave(p.secret.reversed(), phone, n))
    }

    @Test fun saveAttemptsAreCapped() {
        val p = pairPhone(); s.allow()
        var n = s.status(p.secret, phone)!!.second
        repeat(PairingSession.MAX_SAVES) { assertEquals(Gate.OK, s.claimSave(p.secret, phone, n)); n = s.nextNonce() }
        assertEquals(Gate.TOO_MANY, s.claimSave(p.secret, phone, n))
    }

    @Test fun unpairedSessionExpiresAfterFiveMinutes() {
        t = PairingSession.PAIR_WINDOW_MS - 1
        assertTrue(s.isLive)
        t = PairingSession.PAIR_WINDOW_MS + 1
        assertFalse(s.isLive)
        assertEquals(State.EXPIRED, s.state)
        assertFalse(s.pageAllowed(s.token))
    }

    @Test fun pairedSessionExpiresWhenIdle() {
        val p = pairPhone(); s.allow()
        t += PairingSession.IDLE_MS - 1000
        assertNotNull(s.status(p.secret, phone))     // activity keeps it alive
        t += PairingSession.IDLE_MS - 1000
        assertTrue(s.isLive)
        t += 2000
        assertFalse(s.isLive)
    }

    @Test fun listenerFiresOnChanges() {
        var calls = 0
        s.listener = { calls++ }
        pairPhone(); s.allow(); s.finish()
        assertEquals(3, calls)
        assertEquals(State.DONE, s.state)
        s.close()                                     // already ended: no change
        assertEquals(3, calls)
    }
}
