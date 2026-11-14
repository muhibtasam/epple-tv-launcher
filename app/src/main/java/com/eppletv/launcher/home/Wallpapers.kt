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
 * They are the user's, never part of the APK or the repository, and they arrive two ways:
 *  - from a phone, through the paired setup page (Settings > Wallpapers > Add from Phone), via [add];
 *  - from a computer: `scripts/wallpapers.sh` pushes images into this app's own external folder
 *    (Android/data/com.eppletv.launcher/files/wallpapers), and [importDropped] takes them in on the next pick.
 * Every picture is decoded, checked and re-encoded as a fresh JPEG of at most 1920x1080 in the app's private storage,
 * so nothing from the original file (metadata, odd formats, huge sizes) is kept. Worker threads only, except [count].
 */
class Wallpapers(ctx: Context) {
    private val dir = File(ctx.filesDir, "wallpapers").apply { mkdirs() }
    private val drop: File? = try { ctx.getExternalFilesDir("wallpapers") } catch (_: Exception) { null }
    private val prefs = ctx.getSharedPreferences("wallpapers", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    class Result(val ok: Boolean, val message: String)

    @Synchronized fun files(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun count() = files().size

    /** Re-encodes and stores one picture. Rejects anything that isn't a decodable image of a sensible size. */
    @Synchronized fun add(bytes: ByteArray): Result {
        if (files().size >= MAX) return Result(false, "Already $MAX wallpapers. Remove some on the TV first")
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
        // Just large enough to fill 1920x1080 (the screen crops the rest); never upscaled.
        val k = minOf(1f, maxOf(OUT_W.toFloat() / src.width, OUT_H.toFloat() / src.height))
        val out = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
        val name = "w-${System.currentTimeMillis()}-${Integer.toHexString(random.nextInt())}.jpg"
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!tmp.renameTo(File(dir, name))) { tmp.delete(); return Result(false, "Couldn't save this picture") }
        } catch (_: Exception) {
            tmp.delete(); return Result(false, "Couldn't save this picture")
        } finally {
            if (out !== src) out.recycle()
            src.recycle()
        }
        return Result(true, "Added to Home")
    }

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
    @Synchronized fun removeAll() { files().forEach { it.delete() } }

    /** The wallpaper as a full-screen backdrop: 1920x1080, centre-cropped, with the hero scrim painted in. */
    fun load(f: File): Bitmap? {
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
        Scrim.paint(out)
        out.prepareToDraw()
        return out
    }

    companion object {
        const val MAX = 40
        private const val OUT_W = 1920
        private const val OUT_H = 1080
        private val TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val EXTS = setOf("jpg", "jpeg", "png", "webp")
    }
}
