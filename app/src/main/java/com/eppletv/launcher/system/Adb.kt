package com.eppletv.launcher.system

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * A minimal ADB client for the TV's own adbd (127.0.0.1:5555, open while network debugging is on), written from the
 * public protocol description (AOSP packages/modules/adb, protocol.txt). It only connects, authenticates and runs
 * shell commands that [LauncherMode] builds from fixed strings; nothing from outside reaches it.
 *
 * The first connection makes the TV ask "Allow debugging?" for this app's key; the user accepts on the TV
 * (ideally with "Always allow"). The key never leaves the Android Keystore: [Signer] signs the 20-byte token.
 */
class Adb private constructor(private val socket: Socket) : Closeable {
    interface Signer {
        val publicKey: RSAPublicKey
        /** PKCS#1 v1.5 signature of [data] as given (no hashing): adbd checks it as a SHA-1 DigestInfo. */
        fun signRaw(data: ByteArray): ByteArray
    }

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream()
    private var nextId = 1

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** Runs one shell command and returns its output (stdout and stderr together), at most [MAX_OUT] bytes. */
    @Throws(IOException::class)
    fun shell(command: String): String {
        val local = nextId++
        send(OPEN, local, 0, "shell:$command\u0000".toByteArray())
        val out = ByteArrayOutputStream()
        var remote = 0
        while (true) {
            val m = read()
            when (m.cmd) {
                OKAY -> remote = m.arg0
                WRTE -> { if (out.size() < MAX_OUT) out.write(m.data); send(OKAY, local, m.arg0, ByteArray(0)) }
                CLSE -> { if (remote != 0) send(CLSE, local, remote, ByteArray(0)); return out.toString("UTF-8") }
                else -> throw IOException("Unexpected reply")
            }
        }
    }

    override fun close() { try { socket.close() } catch (_: IOException) {} }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        output.write(header(cmd, a0, a1, data)); output.write(data); output.flush()
    }

    private fun read(): Msg {
        val h = ByteArray(24); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int; b.int; val magic = b.int
        if (magic != cmd.inv() || len < 0 || len > MAX_DATA) throw IOException("Bad message")
        val d = ByteArray(len); input.readFully(d)
        return Msg(cmd, a0, a1, d)
    }

    companion object {
        internal const val CNXN = 0x4e584e43; internal const val AUTH = 0x48545541; internal const val OPEN = 0x4e45504f
        internal const val OKAY = 0x59414b4f; internal const val CLSE = 0x45534c43; internal const val WRTE = 0x45545257
        private const val STLS = 0x534c5453
        private const val VERSION = 0x01000001; private const val MAX_DATA = 256 * 1024; private const val MAX_OUT = 512 * 1024
        private const val TOKEN = 1; private const val SIGNATURE = 2; private const val RSA_PUBLIC = 3
        /** DER prefix of a SHA-1 DigestInfo: adbd treats the token as the digest. */
        internal val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /**
         * Connects and authenticates. When the TV doesn't know the key yet it shows its prompt; [waiting] is called
         * then, and this blocks until the user answers or [promptMs] passes.
         */
        @Throws(IOException::class)
        fun connect(signer: Signer, waiting: () -> Unit, host: String = "127.0.0.1", port: Int = 5555, promptMs: Int = 90_000): Adb {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 3000)
                s.soTimeout = 10_000
            } catch (e: IOException) {
                s.close(); throw IOException("Network debugging is off on this TV", e)
            }
            val a = Adb(s)
            try {
                a.send(CNXN, VERSION, MAX_DATA, "host::epple\u0000".toByteArray())
                var sentSignature = false
                while (true) {
                    val m = a.read()
                    when {
                        m.cmd == CNXN -> { a.socket.soTimeout = 30_000; return a }
                        m.cmd == STLS -> throw IOException("This TV only allows paired wireless debugging")
                        m.cmd == AUTH && m.arg0 == TOKEN && !sentSignature -> {
                            a.send(AUTH, SIGNATURE, 0, signer.signRaw(SHA1_PREFIX + m.data)); sentSignature = true
                        }
                        m.cmd == AUTH && m.arg0 == TOKEN -> {
                            // Key unknown to the TV: offer it, which shows the prompt on screen.
                            a.send(AUTH, RSA_PUBLIC, 0, (androidPublicKey(signer.publicKey) + " epple-tv@tv\u0000").toByteArray())
                            a.socket.soTimeout = promptMs
                            waiting()
                        }
                        else -> throw IOException("Unexpected reply")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                a.close(); throw IOException("The TV didn't allow debugging", e)
            } catch (e: IOException) {
                a.close(); throw e
            }
        }

        internal fun header(cmd: Int, a0: Int, a1: Int, data: ByteArray): ByteArray {
            var sum = 0; for (x in data) sum += x.toInt() and 0xFF
            return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv()).array()
        }

        /** adb's RSA public key format (android_pubkey.h), base64: word count, n0inv, modulus and R^2 mod n little-endian, exponent. */
        internal fun androidPublicKey(k: RSAPublicKey): String {
            val n = k.modulus
            val words = 64                                    // 2048-bit keys only
            require(n.bitLength() <= words * 32) { "2048-bit key required" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
            val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
            val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(words).putInt(n0inv).put(littleEndian(n, words * 4)).put(littleEndian(rr, words * 4)).putInt(k.publicExponent.toInt())
            return Base64.getEncoder().encodeToString(b.array())
        }

        private fun littleEndian(v: BigInteger, size: Int): ByteArray {
            val be = v.toByteArray()
            val out = ByteArray(size)
            for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
            return out
        }
    }
}
