package com.eppletv.launcher.pair

/**
 * QR Code encoder written from ISO/IEC 18004: byte mode, error correction level M,
 * versions 1 to 10 (up to 213 bytes). A setup link is about 50 bytes, which is version 4.
 * Pure Kotlin so it can be unit-tested on the JVM; drawing happens in the UI layer.
 */
class Qr private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size

    /** True if the module at column [x], row [y] is dark. Outside the symbol is light. */
    fun isDark(x: Int, y: Int): Boolean = y in modules.indices && x in modules.indices && modules[y][x]

    companion object {
        const val MAX_VERSION = 10

        // Error correction codewords per block and number of blocks, level M (spec table 9).
        private val EC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        // Alignment pattern centres (spec annex E).
        private val ALIGN = arrayOf(
            intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26),
            intArrayOf(6, 30), intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42),
            intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )

        fun encode(text: String): Qr = encode(text.toByteArray(Charsets.UTF_8))

        fun encode(data: ByteArray): Qr {
            val version = (1..MAX_VERSION).firstOrNull { capacityBits(it) >= 4 + countBits(it) + data.size * 8 }
                ?: throw IllegalArgumentException("Too long for a QR code: ${data.size} bytes")
            val codewords = interleave(version, dataCodewords(version, data))
            val size = version * 4 + 17
            val function = Array(size) { BooleanArray(size) }
            val base = Array(size) { BooleanArray(size) }
            drawFunctionPatterns(version, base, function)
            placeData(codewords, base, function)
            var best: Array<BooleanArray>? = null
            var bestMask = 0
            var bestScore = Int.MAX_VALUE
            for (m in 0 until 8) {
                val grid = Array(size) { base[it].copyOf() }
                applyMask(m, grid, function)
                drawFormat(m, grid)
                val score = penalty(grid)
                if (score < bestScore) { bestScore = score; best = grid; bestMask = m }
            }
            return Qr(version, bestMask, best!!)
        }

        private fun rawModules(v: Int): Int {
            var r = (16 * v + 128) * v + 64
            if (v >= 2) {
                val n = v / 7 + 2
                r -= (25 * n - 10) * n - 55
                if (v >= 7) r -= 36
            }
            return r
        }

        private fun totalCodewords(v: Int) = rawModules(v) / 8
        private fun dataCount(v: Int) = totalCodewords(v) - EC_PER_BLOCK[v] * BLOCKS[v]
        private fun capacityBits(v: Int) = dataCount(v) * 8
        private fun countBits(v: Int) = if (v < 10) 8 else 16

        private fun dataCodewords(v: Int, data: ByteArray): ByteArray {
            val bits = BitBuffer()
            bits.add(0b0100, 4)                       // byte mode
            bits.add(data.size, countBits(v))
            for (b in data) bits.add(b.toInt() and 0xFF, 8)
            val cap = capacityBits(v)
            bits.add(0, minOf(4, cap - bits.length))   // terminator
            bits.add(0, (8 - bits.length % 8) % 8)
            var pad = 0xEC
            while (bits.length < cap) { bits.add(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return bits.toBytes()
        }

        private fun interleave(v: Int, data: ByteArray): ByteArray {
            val blocks = BLOCKS[v]
            val ec = EC_PER_BLOCK[v]
            val total = totalCodewords(v)
            val shortBlocks = blocks - total % blocks
            val shortLen = total / blocks                // data + ec of a short block
            val gen = Rs.generator(ec)
            val dataParts = ArrayList<ByteArray>()
            val ecParts = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - ec + if (i < shortBlocks) 0 else 1
                val part = data.copyOfRange(k, k + len)
                k += len
                dataParts += part
                ecParts += Rs.remainder(part, gen)
            }
            val out = ByteArray(total)
            var o = 0
            val longest = dataParts.maxOf { it.size }
            for (i in 0 until longest) for (p in dataParts) if (i < p.size) out[o++] = p[i]
            for (i in 0 until ec) for (p in ecParts) out[o++] = p[i]
            check(o == total)
            return out
        }

        private fun drawFunctionPatterns(v: Int, g: Array<BooleanArray>, f: Array<BooleanArray>) {
            val n = g.size
            fun set(x: Int, y: Int, dark: Boolean) { g[y][x] = dark; f[y][x] = true }
            for (i in 0 until n) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }   // timing
            for ((cx, cy) in listOf(3 to 3, n - 4 to 3, 3 to n - 4)) {               // finders + separators
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx; val y = cy + dy
                    if (x !in 0 until n || y !in 0 until n) continue
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    set(x, y, d != 2 && d != 4)
                }
            }
            val pos = ALIGN[v]
            for (ay in pos) for (ax in pos) {
                val nearFinder = (ax == 6 && ay == 6) || (ax == 6 && ay == pos.last()) || (ax == pos.last() && ay == 6)
                if (nearFinder) continue
                for (dy in -2..2) for (dx in -2..2) set(ax + dx, ay + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            // Reserve format areas (filled per mask) and the always-dark module.
            for (i in 0..8) { f[8][i] = true; f[i][8] = true }
            for (i in 0 until 8) { f[8][n - 1 - i] = true; f[n - 1 - i][8] = true }
            set(8, n - 8, true)
            if (v >= 7) {
                var rem = v
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (v shl 12) or rem
                for (i in 0 until 18) {
                    val dark = (bits ushr i) and 1 == 1
                    val a = n - 11 + i % 3; val b = i / 3
                    set(a, b, dark); set(b, a, dark)
                }
            }
        }

        private fun placeData(data: ByteArray, g: Array<BooleanArray>, f: Array<BooleanArray>) {
            val n = g.size
            var i = 0
            val total = data.size * 8
            var right = n - 1
            while (right >= 1) {
                if (right == 6) right = 5
                val upward = ((right + 1) and 2) == 0
                for (vert in 0 until n) {
                    val y = if (upward) n - 1 - vert else vert
                    for (j in 0..1) {
                        val x = right - j
                        if (f[y][x]) continue
                        if (i < total) g[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1
                        i++   // remainder bits stay light
                    }
                }
                right -= 2
            }
        }

        private fun maskBit(m: Int, x: Int, y: Int): Boolean = when (m) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        }

        private fun applyMask(m: Int, g: Array<BooleanArray>, f: Array<BooleanArray>) {
            for (y in g.indices) for (x in g.indices) if (!f[y][x] && maskBit(m, x, y)) g[y][x] = !g[y][x]
        }

        /** Level M is 00; 15 bits = 5 data + 10 BCH, XOR 0x5412. */
        internal fun formatBits(mask: Int): Int {
            val data = (0b00 shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        private fun drawFormat(mask: Int, g: Array<BooleanArray>) {
            val n = g.size
            val bits = formatBits(mask)
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) g[i][8] = bit(i)
            g[7][8] = bit(6); g[8][8] = bit(7); g[8][7] = bit(8)
            for (i in 9 until 15) g[8][14 - i] = bit(i)
            for (i in 0 until 8) g[8][n - 1 - i] = bit(i)
            for (i in 8 until 15) g[n - 15 + i][8] = bit(i)
            g[n - 8][8] = true
        }

        private fun penalty(g: Array<BooleanArray>): Int {
            val n = g.size
            var score = 0
            fun at(x: Int, y: Int, horizontal: Boolean) = if (horizontal) g[y][x] else g[x][y]
            for (horizontal in listOf(true, false)) for (a in 0 until n) {
                var run = 1
                for (b in 1 until n) {
                    if (at(b, a, horizontal) == at(b - 1, a, horizontal)) run++ else { if (run >= 5) score += run - 2; run = 1 }
                }
                if (run >= 5) score += run - 2
                // Finder-like 1:1:3:1:1 with four light modules on one side; outside counts as light.
                for (b in -10 until n) {
                    fun d(k: Int) = (b + k) in 0 until n && at(b + k, a, horizontal)
                    val core = d(0) && !d(1) && d(2) && d(3) && d(4) && !d(5) && d(6)
                    if (core && ((7..10).none { d(it) } || (-4..-1).none { d(it) })) score += 40
                }
            }
            for (y in 0 until n - 1) for (x in 0 until n - 1) {
                val c = g[y][x]
                if (c == g[y][x + 1] && c == g[y + 1][x] && c == g[y + 1][x + 1]) score += 3
            }
            val dark = g.sumOf { row -> row.count { it } }
            score += kotlin.math.abs(dark * 20 - n * n * 10) / (n * n) * 10
            return score
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length get() = bits.size
        fun add(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
        fun toBytes(): ByteArray {
            val out = ByteArray(bits.size / 8)
            for (i in bits.indices) if (bits[i]) out[i ushr 3] = (out[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
            return out
        }
    }

    /** Reed-Solomon over GF(256) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1. */
    private object Rs {
        private fun mul(a: Int, b: Int): Int {
            var x = a; var y = b; var r = 0
            while (y != 0) {
                if (y and 1 != 0) r = r xor x
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
                y = y ushr 1
            }
            return r
        }

        /** Coefficients of prod (x - 2^i), i in 0 until degree, highest term dropped (it is always 1). */
        fun generator(degree: Int): IntArray {
            val g = IntArray(degree)
            g[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    g[j] = mul(g[j], root)
                    if (j + 1 < degree) g[j] = g[j] xor g[j + 1]
                }
                root = mul(root, 2)
            }
            return g
        }

        fun remainder(data: ByteArray, gen: IntArray): ByteArray {
            val r = IntArray(gen.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor r[0]
                for (i in 0 until r.size - 1) r[i] = r[i + 1]
                r[r.size - 1] = 0
                for (i in r.indices) r[i] = r[i] xor mul(gen[i], factor)
            }
            return ByteArray(r.size) { r[it].toByte() }
        }
    }
}
