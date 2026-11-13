package com.eppletv.launcher.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import java.text.Collator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One launchable app, with everything the home screen needs already rendered. */
class AppEntry(
    val packageName: String,
    val label: String,
    val launchIntent: Intent,
    val banner: Bitmap,     // tile artwork at final on-screen size
    val backdrop: Bitmap,   // tiny copy, scaled up with filtering into a soft full-screen backdrop
    val category: Int,      // ApplicationInfo.CATEGORY_*, or CATEGORY_UNDEFINED
)

/**
 * Loads launchable apps off the main thread and reloads when apps are installed, updated or removed.
 * Banners are rendered once per app version and reused across reloads.
 */
class AppRepository(
    context: Context,
    private val tileW: Int,
    private val tileH: Int,
    private val onApps: (List<AppEntry>) -> Unit,
    private val debugRepeat: Int = 1,   // debug builds only: repeats the list so scrolling can be tested on an emulator
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "epple-apps").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val cache = HashMap<String, Pair<String, AppEntry>>()   // package -> (version key, entry); worker thread only
    private val artDir = File(app.cacheDir, "art").apply { mkdirs() }   // rendered tile art, one PNG per package + version
    private var started = false

    private val reload = Runnable { load() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            main.removeCallbacks(reload)
            main.postDelayed(reload, 500)   // installs fire several broadcasts; coalesce them
        }
    }

    fun start() {
        if (started) return
        started = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        app.registerReceiver(receiver, f)
        load()
    }

    fun stop() {
        if (!started) return
        started = false
        app.unregisterReceiver(receiver)
        main.removeCallbacks(reload)
        worker.shutdownNow()
    }

    private fun load() {
        if (worker.isShutdown) return
        worker.execute {
            val apps = try { query() } catch (e: RuntimeException) { Log.w(TAG, "app query failed", e); null }
            if (apps != null) main.post { if (started) onApps(apps) }
        }
    }

    private fun query(): List<AppEntry> {
        val pm = app.packageManager
        // Leanback (TV) entry points first; phone-only apps are included only if they have no TV entry point.
        val found = LinkedHashMap<String, Pair<ActivityInfo, String>>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == app.packageName || found.containsKey(ai.packageName)) continue
                found[ai.packageName] = ai to cat
            }
        }
        val out = ArrayList<AppEntry>(found.size)
        val seen = HashSet<String>()
        for ((pkg, v) in found) {
            val (ai, cat) = v
            seen.add(pkg)
            val pi = try { pm.getPackageInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { continue }
            // Artwork is keyed by package, version and install time: an update (even one keeping the version) re-renders it.
            val key = "${pi.longVersionCode}_${pi.lastUpdateTime}_${tileW}x$tileH"
            val cached = cache[pkg]
            if (cached != null && cached.first == key) { out.add(cached.second); continue }
            val entry = build(pm, ai, cat, key) ?: continue
            cache[pkg] = key to entry
            out.add(entry)
        }
        cache.keys.retainAll(seen)
        // Drop art for uninstalled apps and superseded versions.
        artDir.listFiles()?.forEach { f -> val pkg = f.name.substringBefore("@"); if (pkg !in seen || cache[pkg]?.first?.let { f.name != "$pkg@$it.png" } == true) f.delete() }
        val collator = Collator.getInstance()
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        return if (debugRepeat > 1) List(out.size * debugRepeat) { out[it % out.size] } else out
    }

    private fun build(pm: PackageManager, ai: ActivityInfo, cat: String, key: String): AppEntry? = try {
        val label = ai.loadLabel(pm).toString().trim().ifEmpty { ai.packageName }
        val file = File(artDir, "${ai.packageName}@$key.png")
        val art = file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.also { b -> b.prepareToDraw() } }
            ?: render(pm, ai).also { b -> try { file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: java.io.IOException) { Log.w(TAG, "art cache write failed", e) } }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val info = ai.applicationInfo
        @Suppress("DEPRECATION")
        val game = info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        // Two-step resample: a 12x7 average of the artwork, smoothed up to 96x54, becomes a soft colour field at full screen.
        val soft = backdropOf(art)
        AppEntry(ai.packageName, label, intent, art, soft, if (game) ApplicationInfo.CATEGORY_GAME else info.category)
    } catch (e: RuntimeException) {
        Log.w(TAG, "skipping ${ai.packageName}", e); null
    }

    /**
     * Backdrop source: the artwork averaged down to 8x5 and smoothed back up, so only its colours survive,
     * then saturated and darkened so white text stays readable over any app (including white banners).
     */
    private fun backdropOf(art: Bitmap): Bitmap {
        val tiny = Bitmap.createScaledBitmap(art, 8, 5, true)
        val mid = Bitmap.createScaledBitmap(tiny, 32, 18, true)
        val out = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(1.5f); postConcat(ColorMatrix().apply { setScale(0.52f, 0.52f, 0.52f, 1f) }) }
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(mid, null, Rect(0, 0, 96, 54), p)
        tiny.recycle(); mid.recycle()
        return out
    }

    /**
     * The app's own artwork, loaded from its resources at high density so the tile stays sharp when focused:
     * its TV banner (activity's, else the app's), else its launcher icon. Nothing is shared between apps.
     */
    private fun render(pm: PackageManager, ai: ActivityInfo): Bitmap {
        val res = try { pm.getResourcesForApplication(ai.applicationInfo) } catch (_: PackageManager.NameNotFoundException) { null }
        fun hd(id: Int, density: Int): Drawable? = if (id == 0 || res == null) null else
            try { res.getDrawableForDensity(id, density, null) } catch (_: RuntimeException) { null }
        val bannerId = if (ai.banner != 0) ai.banner else ai.applicationInfo.banner
        val banner = hd(bannerId, DisplayMetrics.DENSITY_XXHIGH) ?: ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        if (banner != null) return renderBanner(banner)
        return renderIcon(hd(ai.iconResource, DisplayMetrics.DENSITY_XXXHIGH) ?: ai.loadIcon(pm))
    }

    private fun renderBanner(d: Drawable): Bitmap {
        val b = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        if (iw > 0 && ih > 0 && kotlin.math.abs(iw.toFloat() / ih - tileW.toFloat() / tileH) > 0.06f) {
            // Not 16:9: fit it whole, never stretched, on a field of its own colour.
            c.drawColor(mix(averageColor(d), BG, 0.15f))
            val s = minOf(tileW.toFloat() / iw, tileH.toFloat() / ih)
            val w = (iw * s).toInt(); val h = (ih * s).toInt()
            d.setBounds((tileW - w) / 2, (tileH - h) / 2, (tileW + w) / 2, (tileH + h) / 2)
        } else {
            c.drawColor(BG)
            d.setBounds(0, 0, tileW, tileH)
        }
        d.draw(c)
        b.prepareToDraw()
        return b
    }

    /** Phone apps have no banner: centre the icon on a soft gradient tinted from the icon's own colour. */
    private fun renderIcon(d: Drawable): Bitmap {
        val b = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val tint = averageColor(d)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, tileW.toFloat(), tileH.toFloat(), mix(tint, BG, 0.55f), mix(tint, BG, 0.85f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, tileW.toFloat(), tileH.toFloat(), p)
        val s = (tileH * 0.56f).toInt()
        val l = (tileW - s) / 2; val t = (tileH - s) / 2
        d.setBounds(l, t, l + s, t + s)
        d.draw(c)
        b.prepareToDraw()
        return b
    }

    private fun averageColor(d: Drawable): Int {
        val tiny = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 4, 4); d.draw(Canvas(tiny))
        var r = 0; var g = 0; var bl = 0; var n = 0
        for (x in 0 until 4) for (y in 0 until 4) {
            val px = tiny.getPixel(x, y)
            if (px ushr 24 < 64) continue
            r += (px shr 16) and 0xFF; g += (px shr 8) and 0xFF; bl += px and 0xFF; n++
        }
        tiny.recycle()
        return if (n == 0) BG else (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        const val TAG = "EppleApps"
        const val BG = 0xFF262A33.toInt()
    }
}
