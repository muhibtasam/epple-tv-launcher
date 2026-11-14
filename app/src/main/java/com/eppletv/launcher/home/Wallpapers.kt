package com.eppletv.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import java.security.SecureRandom

/**
 * Home's own pictures: the wallpaper shown while the Home tab is selected, before any streaming app is focused.
 *
 * They are the user's, never part of the repository, and they arrive three ways:
 *  - in the build: pictures in the project's `wallpapers/` folder are packed into the APK by scripts/build.sh, and
 *    [importBundled] takes each one in once after an install or update (one removed on the TV is never re-added);
 *  - from a phone, through the paired setup page (Settings > Wallpapers > Add from Phone), via [add];
 *  - from a computer: `scripts/wallpapers.sh` pushes images into this app's own external folder
 *    (Android/data/com.eppletv.launcher/files/wallpapers), and [importDropped] takes them in on the next pick.
 * Every picture is decoded, checked and re-encoded as a fresh JPEG of at most 1920x1080 in the app's private storage,
 * so nothing from the original file (metadata, odd formats, huge sizes) is kept. Worker threads only, except [count].
 */
class Wallpapers(private val ctx: Context, private val folder: String = "wallpapers") {
    private val dir = File(ctx.filesDir, folder).apply { mkdirs() }
    private val drop: File? = try { ctx.getExternalFilesDir(folder) } catch (_: Exception) { null }
    private val prefs = ctx.getSharedPreferences(folder, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    class Result(val ok: Boolean, val message: String)

    @Synchronized fun files(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun count() = files().size

    /** Re-encodes and stores one picture. Rejects anything that isn't a decodable image of a sensible size. */
    @Synchronized fun add(bytes: ByteArray): Result {
        if (files().size >= MAX) return Result(false, "Already $MAX pictures. Remove some on the TV first")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0 || o.outMimeType !in TYPES) return Result(false, "Not a picture this TV can use")
        if (w < 640 || h < 360) return Result(false, "Too small (at least 640×360)")
        if (w.toLong() * h > 64_000_000L) return Result(false, "Too large")
        var s = 1
        while (w / (s * 2) >= OUT_W && h / (s * 2) >= OUT_H) s *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            ?: return Result(false, "Couldn't read this picture")
        // Optimised for the screen: the centre 16:9 of the picture (what the screen would show anyway), at most
        // 1920x1080 and never upscaled, as a fresh JPEG. A 4K photo becomes a few hundred kilobytes.
        val cw = minOf(src.width.toFloat(), src.height * 16f / 9f); val ch = cw * 9f / 16f
        val k = minOf(1f, OUT_W / cw)
        val ow = (cw * k).toInt().coerceAtLeast(1); val oh = (ch * k).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Matrix().apply { setTranslate(-(src.width - cw) / 2f, -(src.height - ch) / 2f); postScale(k, k) },
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        val name = "w-${System.currentTimeMillis()}-${Integer.toHexString(random.nextInt())}.jpg"
        val tmp = File(dir, "$name.tmp")
        try {
            val sig = signature(out)
            if (files().any { f -> signatureOf(f)?.let { same(it, sig) } == true }) return Result(true, "Already on this TV")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!tmp.renameTo(File(dir, name))) { tmp.delete(); return Result(false, "Couldn't save this picture") }
            sigs[name] = sig
            android.util.Log.i("EppleWallpaper", "stored ${w}x$h as ${ow}x$oh, ${File(dir, name).length() / 1024} KB")
        } catch (_: Exception) {
            tmp.delete(); return Result(false, "Couldn't save this picture")
        } finally {
            out.recycle(); src.recycle()
        }
        return Result(true, "Added to Home")
    }

    /**
     * Takes in the pictures packed into this build (assets/wallpapers), each once: a picture is remembered by name and
     * size after it is taken in, so a later build adds only new ones and one the user removed on the TV stays removed.
     * Pictures already on the TV (sent from a phone or a computer before) are recognised by look and not added twice.
     */
    @Synchronized fun importBundled(): Int {
        if (folder != "wallpapers") return 0
        val names = try { ctx.assets.list(BUNDLED) ?: return 0 } catch (_: Exception) { return 0 }
        val seen = prefs.getStringSet("bundled", emptySet())!!.toMutableSet()
        var n = 0
        for (name in names.sorted()) {
            if (name.substringAfterLast('.', "").lowercase() !in EXTS) continue
            val bytes = try { ctx.assets.open("$BUNDLED/$name").use { it.readBytes() } } catch (_: Exception) { continue }
            val key = "$name:${bytes.size}"
            if (key in seen) continue
            val r = add(bytes)
            if (!r.ok && files().size >= MAX) break                          // full: try again after some are removed
            if (r.ok && r.message != "Already on this TV") n++
            seen += key
        }
        prefs.edit().putStringSet("bundled", seen).apply()
        if (n > 0) android.util.Log.i("EppleWallpaper", "took in $n wallpaper(s) from this build")
        return n
    }

    // A 32x18 grey-level thumbprint: two encodings of the same picture match even at different sizes or qualities.
    private val sigs = java.util.concurrent.ConcurrentHashMap<String, IntArray>()
    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 32, 18, true)
        val px = IntArray(32 * 18); s.getPixels(px, 0, 32, 0, 0, 32, 18); if (s !== b) s.recycle()
        return IntArray(px.size) { val c = px[it]; ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10 }
    }
    private fun signatureOf(f: File): IntArray? = sigs[f.name] ?: try {
        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
        BitmapFactory.decodeFile(f.path, o)?.let { b -> signature(b).also { b.recycle(); sigs[f.name] = it } }
    } catch (_: Exception) { null }
    private fun same(a: IntArray, b: IntArray) = a.size == b.size && a.indices.sumOf { kotlin.math.abs(a[it] - b[it]) } / a.size < 10

    /** Takes in pictures copied from a computer into the app's external folder, then removes the copies. */
    @Synchronized fun importDropped(): Int {
        val d = drop ?: return 0
        // scripts/wallpapers.sh --replace leaves this marker: the copied pictures replace all the current ones.
        val marker = File(d, ".replace")
        if (marker.exists()) { removeAll(); marker.delete() }
        var n = 0
        for (f in d.listFiles() ?: return 0) {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (!f.isFile || ext !in EXTS || f.length() > 40L * 1024 * 1024) continue
            try { if (add(f.readBytes()).ok) n++ } catch (_: Exception) {}
            f.delete()
        }
        return n
    }

    /** A random wallpaper, never the one shown last time (when there are two or more). */
    fun pick(): File? {
        val all = files()
        if (all.isEmpty()) return null
        val last = prefs.getString("last", null)
        val pool = if (all.size > 1) all.filter { it.name != last } else all
        return pool[random.nextInt(pool.size)].also { prefs.edit().putString("last", it.name).apply() }
    }

    fun newest(): File? = files().lastOrNull()?.also { prefs.edit().putString("last", it.name).apply() }

    @Synchronized fun remove(f: File) { if (f.parentFile == dir) f.delete() }

    /** A small 16:9 preview for the remove picker (decoded at a reduced size: a few hundred KB each). */
    fun thumb(f: File, w: Int, h: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val k = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply { setScale(k, k); postTranslate((w - src.width * k) / 2f, (h - src.height * k) / 2f) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle(); out.prepareToDraw()
        return out
    }
    /** The picture on screen now (the last one shown), if it is one of ours. */
    fun current(): String? = prefs.getString("last", null)
    @Synchronized fun removeAll() { files().forEach { it.delete() } }

    /** The wallpaper as a full-screen backdrop: 1920x1080, centre-cropped, with the hero scrim painted in. */
    fun load(f: File, scrim: Boolean = true): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= OUT_W && o.outHeight / (s * 2) >= OUT_H) s *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.RGB_565)
        val k = maxOf(OUT_W.toFloat() / src.width, OUT_H.toFloat() / src.height)
        val m = Matrix().apply { setScale(k, k); postTranslate((OUT_W - src.width * k) / 2f, (OUT_H - src.height * k) / 2f) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        src.recycle()
        if (scrim) { Scrim.paint(out); Frost.prepare(out) }  // the hero's scrim and glass textures (not for the screensaver)
        out.prepareToDraw()
        return out
    }

    companion object {
        const val MAX = 40
        private const val BUNDLED = "wallpapers"
        private const val QUALITY = 85
        private const val OUT_W = 1920
        private const val OUT_H = 1080
        private val TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val EXTS = setOf("jpg", "jpeg", "png", "webp")
    }
}
