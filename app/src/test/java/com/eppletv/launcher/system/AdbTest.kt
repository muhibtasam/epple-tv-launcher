package com.eppletv.launcher.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey

    @Test fun publicKeyHasAdbLayout() {
        val raw = Base64.getDecoder().decode(Adb.androidPublicKey(key))
        assertEquals(4 + 4 + 256 + 256 + 4, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xFFFFFFFFL
        val mod = ByteArray(256); b.get(mod)
        val rr = ByteArray(256); b.get(rr)
        assertEquals(key.publicExponent.toInt(), b.int)
        val n = BigInteger(1, mod.reversedArray())
        assertEquals(key.modulus, n)
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv = -1/n mod 2^32
        assertEquals(r32.subtract(BigInteger.ONE), n.multiply(BigInteger.valueOf(n0inv)).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, rr.reversedArray()))
    }

    @Test fun headerHasChecksumAndMagic() {
        val data = "shell:pm path x\u0000".toByteArray()
        val h = ByteBuffer.wrap(Adb.header(Adb.OPEN, 1, 0, data)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Adb.OPEN, h.int); assertEquals(1, h.int); assertEquals(0, h.int)
        assertEquals(data.size, h.int)
        assertEquals(data.sumOf { it.toInt() and 0xFF }, h.int)
        assertEquals(Adb.OPEN.inv(), h.int)
        assertTrue(Adb.SHA1_PREFIX.size == 15)
    }
}
