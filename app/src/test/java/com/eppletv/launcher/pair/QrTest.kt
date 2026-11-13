package com.eppletv.launcher.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class QrTest {
    @Test fun formatBitsMatchSpecTable() {
        // ISO/IEC 18004 table C.1, level M.
        val expected = listOf(0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0)
        assertEquals(expected, (0..7).map { Qr.formatBits(it) })
    }

    @Test fun picksSmallestVersion() {
        assertEquals(1, Qr.encode("HELLO WORLD").version)
        assertEquals(21, Qr.encode("HELLO WORLD").size)
        val link = "http://192.168.100.200:65535/p/" + "A".repeat(22)
        assertEquals(4, Qr.encode(link).version)          // a real setup link fits in version 4
        assertEquals(10, Qr.encode("x".repeat(213)).version)
    }

    @Test(expected = IllegalArgumentException::class) fun refusesTooLong() { Qr.encode("x".repeat(214)) }

    @Test fun finderPatternsAndDarkModuleArePresent() {
        val q = Qr.encode("test")
        val n = q.size
        for ((x, y) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            assertTrue(q.isDark(x, y) && q.isDark(x + 6, y + 6) && q.isDark(x + 3, y + 3))
            assertTrue(!q.isDark(x + 1, y + 1) && !q.isDark(x + 5, y + 5))
        }
        assertTrue(q.isDark(8, n - 8))
    }

    /** Writes samples to build/qr-samples for an independent decoder (see scripts/check-qr.py). */
    @Test fun writeSamplesForExternalDecoder() {
        val dir = File("build/qr-samples").apply { mkdirs() }
        val samples = mapOf(
            "v1" to "HELLO WORLD",
            "link" to "http://192.168.1.69:43817/p/q8N3bX0vT_2-kLmZy7cQaA",
            "v7" to "http://example.invalid/" + "abcdefghij".repeat(10),
            "v10" to "Z".repeat(200),
            "utf8" to "Epple TV · सेटअप · 設定",
        )
        val manifest = StringBuilder()
        for ((name, text) in samples) {
            val q = Qr.encode(text)
            val scale = 8
            val quiet = 4
            val px = (q.size + quiet * 2) * scale
            val img = BufferedImage(px, px, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until px) for (x in 0 until px) {
                val mx = x / scale - quiet
                val my = y / scale - quiet
                img.setRGB(x, y, if (q.isDark(mx, my)) 0x000000 else 0xFFFFFF)
            }
            ImageIO.write(img, "png", File(dir, "$name.png"))
            manifest.append(name).append('\t').append(q.version).append('\t')
                .append(java.util.Base64.getEncoder().encodeToString(text.toByteArray())).append('\n')
        }
        File(dir, "expected.tsv").writeText(manifest.toString())
    }
}
