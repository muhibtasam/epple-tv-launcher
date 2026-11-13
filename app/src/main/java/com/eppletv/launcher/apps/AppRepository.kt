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
import android.graphics.drawable.AdaptiveIconDrawable
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
    private val decorate: ((Canvas, Int, Int) -> Unit)? = null,   // static tile surface baked into the art (saves a layer per tile)
    private val backdropFilter: ((Bitmap) -> Unit)? = null,       // e.g. the hero scrim, baked into the app's backdrop field
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
            val key = "r${RENDER}_${pi.longVersionCode}_${pi.lastUpdateTime}_${tileW}x$tileH"
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
            ?: render(pm, ai).also { b -> decorate?.invoke(Canvas(b), b.width, b.height); b.prepareToDraw() }.also { b -> try { file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: java.io.IOException) { Log.w(TAG, "art cache write failed", e) } }
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
        backdropFilter?.invoke(out)
        tiny.recycle(); mid.recycle()
        return out
    }

    /**
     * The app's own artwork, loaded from its resources at high density so the tile stays sharp when focused,
     * laid out the tvOS way: full-bleed, the brand's own colour edge to edge, the mark centred with padding.
     *  - opaque 16:9 TV banner: used as is.
     *  - banner of another shape, or with transparent edges: fitted whole on its own edge colour.
     *  - adaptive launcher icon: its background layer fills the tile, its foreground sits centred on it.
     *  - legacy launcher icon: centred on its own edge colour.
     * Nothing is shared between apps.
     */
    private fun render(pm: PackageManager, ai: ActivityInfo): Bitmap {
        val res = try { pm.getResourcesForApplication(ai.applicationInfo) } catch (_: PackageManager.NameNotFoundException) { null }
        fun hd(id: Int, density: Int): Drawable? = if (id == 0 || res == null) null else
            try { res.getDrawableForDensity(id, density, null) } catch (_: RuntimeException) { null }
        val bannerId = if (ai.banner != 0) ai.banner else ai.applicationInfo.banner
        val banner = hd(bannerId, DisplayMetrics.DENSITY_XXHIGH) ?: ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        if (banner != null) return renderBanner(banner)
        val icon = hd(ai.iconResource, DisplayMetrics.DENSITY_XXXHIGH) ?: ai.loadIcon(pm)
        return if (icon is AdaptiveIconDrawable && icon.background != null) renderAdaptive(icon) else renderIcon(icon)
    }

    private fun canvasFor(): Pair<Bitmap, Canvas> = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888).let { it to Canvas(it) }

    private fun renderBanner(d: Drawable): Bitmap {
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        val shapeOk = iw <= 0 || ih <= 0 || kotlin.math.abs(iw.toFloat() / ih - tileW.toFloat() / tileH) <= 0.06f
        val probe = probe(d)
        if (shapeOk && probe.opaqueEdges) {
            val (b, c) = canvasFor()
            d.setBounds(0, 0, tileW, tileH); d.draw(c)
            b.prepareToDraw(); return b
        }
        // Fitted whole (never stretched) with a little padding, on the artwork's own edge colour.
        val (b, c) = canvasFor()
        c.drawColor(probe.field)
        val pad = if (probe.opaqueEdges) 0f else 0.08f
        val aw = if (iw > 0) iw.toFloat() else tileW.toFloat(); val ah = if (ih > 0) ih.toFloat() else tileH.toFloat()
        val s = minOf(tileW * (1 - 2 * pad) / aw, tileH * (1 - 2 * pad) / ah)
        val w = (aw * s).toInt(); val h = (ah * s).toInt()
        d.setBounds((tileW - w) / 2, (tileH - h) / 2, (tileW + w) / 2, (tileH + h) / 2); d.draw(c)
        b.prepareToDraw(); return b
    }

    /** Adaptive icon: background layer full-bleed (cover), foreground centred with its safe zone ~70% of the tile height. */
    private fun renderAdaptive(d: AdaptiveIconDrawable): Bitmap {
        val (b, c) = canvasFor()
        // A layer is 108dp with a 72dp visible area; cover the tile with the background's visible area.
        val bgSide = tileW * 108f / 72f
        val bl = (tileW - bgSide) / 2f; val bt = (tileH - bgSide) / 2f
        d.background?.let { it.setBounds(bl.toInt(), bt.toInt(), (bl + bgSide).toInt(), (bt + bgSide).toInt()); it.draw(c) }
        d.foreground?.let {
            val fgSide = tileH * 0.70f * 108f / 66f        // the 66dp safe zone spans 70% of the tile height
            val l = (tileW - fgSide) / 2f; val t = (tileH - fgSide) / 2f
            it.setBounds(l.toInt(), t.toInt(), (l + fgSide).toInt(), (t + fgSide).toInt()); it.draw(c)
        }
        b.prepareToDraw(); return b
    }

    /** Legacy icon: centred at 62% of the tile height on its own edge colour, so the mark reads as the tile. */
    private fun renderIcon(d: Drawable): Bitmap {
        val (b, c) = canvasFor()
        val probe = probe(d)
        c.drawColor(probe.field)
        val s = (tileH * 0.62f).toInt()
        val l = (tileW - s) / 2; val t = (tileH - s) / 2
        d.setBounds(l, t, l + s, t + s); d.draw(c)
        b.prepareToDraw(); return b
    }

    private class Probe(val field: Int, val opaqueEdges: Boolean)

    /**
     * Samples the artwork's border at 32x18: if the edges are opaque their mean colour continues the artwork outward;
     * transparent edges take the artwork's mean colour, darkened so a white mark keeps its contrast.
     */
    private fun probe(d: Drawable): Probe {
        val w = 32; val h = 18
        val tiny = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val old = d.copyBounds(); d.setBounds(0, 0, w, h); d.draw(Canvas(tiny)); d.bounds = old
        val px = IntArray(w * h); tiny.getPixels(px, 0, w, 0, 0, w, h); tiny.recycle()
        var r = 0L; var g = 0L; var bl = 0L; var n = 0; var edges = 0; var opaque = 0
        var ar = 0L; var ag = 0L; var ab = 0L; var an = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]; val a = p ushr 24
            if (a > 160) { ar += (p shr 16) and 0xFF; ag += (p shr 8) and 0xFF; ab += p and 0xFF; an++ }
            if (x != 0 && y != 0 && x != w - 1 && y != h - 1) continue
            edges++
            if (a < 230) continue
            opaque++; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
        }
        val opaqueEdges = opaque >= edges * 0.9f
        val field = when {
            n > edges / 3 -> (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
            an > 0 -> mix((0xFF shl 24) or ((ar / an).toInt() shl 16) or ((ag / an).toInt() shl 8) or (ab / an).toInt(), BG, 0.45f)
            else -> BG
        }
        return Probe(field, opaqueEdges)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        const val TAG = "EppleApps"
        const val RENDER = 3   // bump when the artwork layout changes, so cached art is re-rendered
        const val BG = 0xFF262A33.toInt()
    }
}
