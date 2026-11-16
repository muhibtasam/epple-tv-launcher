package com.eppletv.launcher.apps

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

/**
 * Dark tile art, the way tvOS / iOS dark icons look: the logo on a charcoal surface, at its own size and place.
 *  - banner on a flat or gradient background: the background is keyed out (flood fill from the edges, so details
 *    inside a logo stay) and every edge pixel is matted against the nearest solid logo colour: no pale fringe.
 *  - logo with its own transparency (adaptive icon foreground, transparent banner, legacy icon): used directly.
 * Colours: a white logo on a brand colour takes the brand colour; black or dark parts are lifted so they read on
 * charcoal (logos with both black and white parts keep their colours); other colours are kept. Unreadable results
 * and photos keep their original art. Work is done at [SS] times the final size and filtered down, once per app version on the
 * worker thread; the result is cached by [AppRepository].
 */
internal object DarkArt {
    const val SS = 2
    private const val TOP = 0xFF2E2F33.toInt()
    private const val BOTTOM = 0xFF111113.toInt()
    private const val T1 = 90f      // colour distance within which a pixel can belong to the background
    private const val R = 3         // matting search radius at SS scale

    /** Why the last artwork was or wasn't adapted (worker thread only; for the log). */
    var result = ""; private set
    private fun no(why: String): Bitmap? { result = "original ($why)"; return null }

    /** An opaque banner: a logo on a flat or gradient background. Null: keep the original. */
    fun banner(b: Bitmap): Bitmap? = try { keyed(b) } catch (e: RuntimeException) { no(e.toString()) }

    /** A logo with its own transparency; [brand]: the colour it normally sits on, if known. Null: keep the original. */
    fun layer(b: Bitmap, brand: Int?): Bitmap? = try { layered(b, brand) } catch (e: RuntimeException) { no(e.toString()) }

    private fun keyed(b: Bitmap): Bitmap? {
        val w = b.width; val h = b.height; val n = w * h
        val px = IntArray(n); b.getPixels(px, 0, w, 0, 0, w, h)
        val ring = 2 * SS
        fun border(i: Int): Boolean { val x = i % w; val y = i / w; return x < ring || y < ring || x >= w - ring || y >= h - ring }

        // 1. Background model: a plane per channel fitted to the border (flat colours and linear gradients).
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val m = DoubleArray(3); val gx = DoubleArray(3); val gy = DoubleArray(3); var sxx = 0.0; var syy = 0.0; var cnt = 0
        for (i in 0 until n) if (border(i)) {
            val p = px[i]; if ((p ushr 24) < 250) return no("transparent edge")
            val dx = i % w - cx; val dy = i / w - cy
            for (k in 0..2) { val v = ((p shr (16 - 8 * k)) and 255).toDouble(); m[k] += v; gx[k] += v * dx; gy[k] += v * dy }
            sxx += dx * dx; syy += dy * dy; cnt++
        }
        for (k in 0..2) { m[k] /= cnt; gx[k] /= sxx; gy[k] /= syy }
        val bg = IntArray(n)
        for (i in 0 until n) {
            val dx = i % w - cx; val dy = i / w - cy
            bg[i] = rgb((m[0] + gx[0] * dx + gy[0] * dy).toFloat(), (m[1] + gx[1] * dx + gy[1] * dy).toFloat(), (m[2] + gx[2] * dx + gy[2] * dy).toFloat())
        }
        var off = 0
        for (i in 0 until n) if (border(i) && dist(px[i], bg[i]) > 24f) off++
        if (off > cnt * 0.04f) return no("background not plain ${off * 100 / cnt}%")
        val mean = rgb(m[0].toFloat(), m[1].toFloat(), m[2].toFloat())

        // 2. Background region: flood fill from the border, so a white detail inside a logo (a play triangle) stays.
        val mask = BooleanArray(n); val stack = IntArray(n); var sp = 0
        val nb = IntArray(4)
        fun neighbours(i: Int) { val x = i % w; nb[0] = if (x > 0) i - 1 else -1; nb[1] = if (x < w - 1) i + 1 else -1; nb[2] = i - w; nb[3] = i + w }
        for (i in 0 until n) if (border(i) && dist(px[i], bg[i]) < T1) { mask[i] = true; stack[sp++] = i }
        while (sp > 0) {
            val i = stack[--sp]; neighbours(i)
            for (j in nb) if (j in 0 until n && !mask[j] && dist(px[j], bg[j]) < T1) { mask[j] = true; stack[sp++] = j }
        }
        // Enclosed background-coloured areas bounded by a neutral wordmark (letter counters, including their white-into-
        // background antialiased rims) are background too;
        // areas bounded by colour are part of the mark and stay.
        val seen = BooleanArray(n)
        for (s in 0 until n) {
            if (mask[s] || seen[s] || dist(px[s], bg[s]) >= T1) continue
            var top = 0; var edge = 0; var neutral = 0; var k = 0
            seen[s] = true; stack[top++] = s
            while (k < top) {
                neighbours(stack[k++])
                for (j in nb) {
                    if (j !in 0 until n || seen[j] || mask[j]) continue
                    if (dist(px[j], bg[j]) < T1) { seen[j] = true; stack[top++] = j } else { edge++; if (sat(px[j]) < 0.2f || towardWhite(px[j], bg[j]) >= 0f) neutral++ }
                }
            }
            if (edge > 0 && neutral >= edge * 0.6f) for (q in 0 until top) mask[stack[q]] = true
        }
        var bgCount = 0; for (v in mask) if (v) bgCount++
        if (bgCount < n * 0.35f) return no("background ${bgCount * 100 / n}%")

        // 3. Edge matting. Pixels within R of the background are a blend of the background and the nearest solid logo
        // colour F (the pixel within R that differs most from the background): coverage = projection onto bg→F,
        // colour = F. Away from edges pixels are kept as they are.
        val near = dilate(mask, w, h, R)
        val alpha = FloatArray(n); val fg = IntArray(n)
        for (i in 0 until n) {
            if (!near[i]) { alpha[i] = 1f; fg[i] = px[i]; continue }
            val x = i % w; val y = i / w; val b0 = bg[i]
            var f = 0; var fd = -1f
            for (yy in maxOf(0, y - R)..minOf(h - 1, y + R)) for (xx in maxOf(0, x - R)..minOf(w - 1, x + R)) {
                val j = yy * w + xx; if (mask[j]) continue
                val d = dist(px[j], bg[j]); if (d > fd) { fd = d; f = px[j] }
            }
            if (fd < T1) { alpha[i] = if (mask[i]) 0f else 1f; fg[i] = px[i]; continue }
            val vr = (r(f) - r(b0)).toFloat(); val vg = (g(f) - g(b0)).toFloat(); val vb = (bl(f) - bl(b0)).toFloat()
            val p = px[i]
            val a = (((r(p) - r(b0)) * vr + (g(p) - g(b0)) * vg + (bl(p) - bl(b0)) * vb) / (vr * vr + vg * vg + vb * vb)).coerceIn(0f, 1f)
            alpha[i] = if (a < 0.03f) 0f else a
            fg[i] = if (!mask[i] && a > 0.97f) p else f
        }
        return compose(w, h, alpha, fg, bg, mean, keep = lum(mean) < 0.2f, kind = "banner")
    }

    private fun layered(b: Bitmap, brand: Int?): Bitmap? {
        val w = b.width; val h = b.height; val n = w * h
        val px = IntArray(n); b.getPixels(px, 0, w, 0, 0, w, h)   // unpremultiplied ARGB
        val alpha = FloatArray(n); val fg = IntArray(n)
        for (i in 0 until n) { val a = (px[i] ushr 24) / 255f; alpha[i] = if (a < 0.02f) 0f else a; fg[i] = px[i] or (0xFF shl 24) }
        return compose(w, h, alpha, fg, null, brand, keep = false, kind = "layer")
    }

    /** Colour rules, readability check and composite over the charcoal surface (lighter at the top). */
    private fun compose(w: Int, h: Int, alpha: FloatArray, fg: IntArray, bg: IntArray?, brand: Int?, keep: Boolean, kind: String): Bitmap? {
        val n = w * h
        var logo = 0; var white = 0; var darkN = 0; var lightN = 0
        for (i in 0 until n) {
            if (alpha[i] < 0.5f) continue
            val c = fg[i]; val l = lum(c); val s = sat(c); logo++
            if (if (bg != null) towardWhite(c, bg[i]) >= 0f else s < 0.2f && l > 0.7f) white++
            if (s < 0.2f && l < 0.35f) darkN++
            if (s < 0.2f && l > 0.7f) lightN++
        }
        if (logo < n * 0.004f || logo > n * 0.6f) return no("$kind: logo ${logo * 100 / n}%")
        val hsv = FloatArray(3)
        val brandC = if (brand != null) { Color.colorToHSV(brand, hsv); hsv[2] = maxOf(hsv[2], 0.8f); Color.HSVToColor(hsv) } else 0
        // A white logo on a brand colour: every white-into-background pixel becomes the logo colour at its coverage, so
        // no trace of the old background survives (not even in tiny letter counters). The logo colour is the brand
        // colour when it reads clearly on charcoal (Crunchyroll orange), otherwise white.
        val useBrand = brand != null && sat(brand) >= 0.35f && white >= logo * 0.85f && darkN < logo * 0.03f
        val logoC = if (lum(brandC) >= 0.45f) brandC else 0xFFF5F5F7.toInt()
        val mode = when {
            keep -> 0                                                    // logo already made for a dark background
            useBrand -> 2                                                // white logo takes the brand colour
            darkN >= logo * 0.15f && lightN >= logo * 0.15f -> 3              // black and white parts: see below
            else -> 1                                                    // colours kept, dark parts lifted
        }
        // Black and white logo: dark parts are lifted only away from white parts (lifting a black outline next to white
        // would merge the two); dark parts touching white keep their colour.
        val nearWhite = if (mode == 3) dilate(BooleanArray(n) { alpha[it] >= 0.5f && sat(fg[it]) < 0.2f && lum(fg[it]) > 0.7f }, w, h, 2 * SS) else null
        val out = IntArray(n); var vSum = 0f; var wSum = 0f
        for (y in 0 until h) {
            val base = mix(TOP, BOTTOM, y / (h - 1f))
            for (x in 0 until w) {
                val i = y * w + x; var a = alpha[i]
                if (a <= 0f) { out[i] = base; continue }
                var c = fg[i]
                when (mode) {
                    1 -> c = lift(c, hsv)
                    3 -> if (!nearWhite!![i]) c = lift(c, hsv)
                    2 -> if (bg != null) { val t = towardWhite(c, bg[i]); if (t >= 0f) { c = logoC; a *= t } else if (dist(c, bg[i]) < T1) a = 0f }
                         else if (sat(c) < 0.2f) { a *= (lum(c) / 0.95f).coerceAtMost(1f); c = logoC }
                }
                vSum += maxOf(r(c), g(c), bl(c)) / 255f * a; wSum += a
                out[i] = mix(base, c, a)
            }
        }
        if (wSum <= 0f || vSum / wSum < 0.45f) return no("$kind: unreadable")
        result = "dark ($kind, ${arrayOf("kept colours", "dark parts lifted", if (logoC == brandC) "brand colour" else "white logo", "dark parts lifted apart from white")[mode]}" +
            (if (brand != null) ", brand lum ${"%.2f".format(lum(brandC))})" else ")")
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Pixels within [rad] of a set pixel (separable box dilation). */
    private fun dilate(src: BooleanArray, w: Int, h: Int, rad: Int): BooleanArray {
        val tmp = BooleanArray(src.size); val out = BooleanArray(src.size)
        for (y in 0 until h) {
            var last = -1000
            for (x in 0 until w) { if (src[y * w + x]) last = x; if (x - last <= rad) tmp[y * w + x] = true }
            last = 1000000
            for (x in w - 1 downTo 0) { if (src[y * w + x]) last = x; if (last - x <= rad) tmp[y * w + x] = true }
        }
        for (x in 0 until w) {
            var last = -1000
            for (y in 0 until h) { if (tmp[y * w + x]) last = y; if (y - last <= rad) out[y * w + x] = true }
            last = 1000000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) last = y; if (last - y <= rad) out[y * w + x] = true }
        }
        return out
    }

    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun bl(c: Int) = c and 255
    private fun dist(a: Int, b: Int): Float {
        val dr = (r(a) - r(b)).toFloat(); val dg = (g(a) - g(b)).toFloat(); val db = (bl(a) - bl(b)).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }
    private fun lum(c: Int) = (0.299f * r(c) + 0.587f * g(c) + 0.114f * bl(c)) / 255f
    private fun sat(c: Int): Float { val mx = maxOf(r(c), g(c), bl(c)); val mn = minOf(r(c), g(c), bl(c)); return if (mx == 0) 0f else (mx - mn) / mx.toFloat() }
    private fun rgb(r: Float, g: Float, b: Float) =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    private fun grey(v: Float) = (v * 255f).let { rgb(it, it, it) }
    private fun mix(a: Int, b: Int, t: Float) =
        rgb(r(a) + (r(b) - r(a)) * t, g(a) + (g(b) - g(a)) * t, bl(a) + (bl(b) - bl(a)) * t)
    /**
     * If [c] is white mixed into [bg] (c = bg + t·(white - bg)), the white coverage t (0..1); otherwise -1.
     * Channels where the background is already near 255 can't tell, so they only have to stay bright.
     */
    private fun towardWhite(c: Int, bg: Int): Float {
        var lo = 2f; var hi = -2f; var sum = 0f; var k = 0
        for (sh in intArrayOf(16, 8, 0)) {
            val cv = (c shr sh) and 255; val bv = (bg shr sh) and 255
            if (255 - bv <= 25) { if (cv < bv - 20) return -1f; continue }
            val t = (cv - bv) / (255f - bv); lo = minOf(lo, t); hi = maxOf(hi, t); sum += t; k++
        }
        if (k == 0 || lo < -0.08f || hi > 1.08f || hi - lo > 0.2f) return -1f
        return (sum / k).coerceIn(0f, 1f)
    }
    /** Neutral dark parts become light grey; coloured parts are brightened. */
    private fun lift(c: Int, tmp: FloatArray): Int {
        val l = lum(c)
        return if (sat(c) < 0.2f) { if (l < 0.6f) grey(0.93f - 0.35f * l) else c } else liftDark(c, tmp)
    }
    /** A coloured part keeps its hue and saturation and is brought to near full brightness, so it reads on charcoal. */
    private fun liftDark(c: Int, tmp: FloatArray): Int {
        Color.colorToHSV(c, tmp)
        if (tmp[2] >= 0.85f) return c
        tmp[2] = 0.85f; return Color.HSVToColor(tmp)
    }
}
