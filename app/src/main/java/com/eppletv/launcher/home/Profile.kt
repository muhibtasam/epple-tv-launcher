package com.eppletv.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File

/**
 * The profile picture in the clock capsule (top right), as on tvOS. Sent from a phone (the setup page's
 * "Profile picture"); stored privately as a 256x256 centre-cropped JPEG, re-encoded so nothing of the original file
 * is kept. Worker threads, except [cached].
 */
class Profile(ctx: Context) {
    private val file = File(ctx.filesDir, "profile.jpg")
    @Volatile var cached: Bitmap? = null
        private set

    fun exists() = file.exists()

    @Synchronized fun set(bytes: ByteArray): Wallpapers.Result {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0 || o.outMimeType !in setOf("image/jpeg", "image/png", "image/webp")) return Wallpapers.Result(false, "Not a picture this TV can use")
        if (w < 96 || h < 96) return Wallpapers.Result(false, "Too small")
        if (w.toLong() * h > 64_000_000L) return Wallpapers.Result(false, "Too large")
        var s = 1
        while (minOf(w, h) / (s * 2) >= SIZE) s *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            ?: return Wallpapers.Result(false, "Couldn't read this picture")
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val k = SIZE.toFloat() / minOf(src.width, src.height)
        val m = Matrix().apply { setScale(k, k); postTranslate((SIZE - src.width * k) / 2f, (SIZE - src.height * k) / 2f) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        val tmp = File(file.path + ".tmp")
        return try {
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(file)) { tmp.delete(); Wallpapers.Result(false, "Couldn't save this picture") }
            else { out.prepareToDraw(); cached = out; android.util.Log.i("EppleProfile", "stored ${w}x$h as ${SIZE}x$SIZE, ${file.length() / 1024} KB"); Wallpapers.Result(true, "Set as your profile picture") }
        } catch (_: Exception) { tmp.delete(); Wallpapers.Result(false, "Couldn't save this picture") }
    }

    fun load(): Bitmap? = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null).also { cached = it }

    @Synchronized fun remove() { file.delete(); cached = null }

    private companion object { const val SIZE = 256 }
}
