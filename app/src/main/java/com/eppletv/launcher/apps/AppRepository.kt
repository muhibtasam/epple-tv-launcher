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
    /** Work for each app that can wait until the list is on screen (e.g. the glass frost of its backdrop), in this order. */
    private val warm: ((List<AppEntry>) -> List<AppEntry>)? = null,
    private val warmOne: ((AppEntry) -> Unit)? = null,
    /**
     * Packages wanted on screen first (Home's top row). On the first load they are built before the others and handed
     * over at once through [onFirst], so the top row doesn't wait for every installed app.
     */
    private val first: (() -> Collection<String>)? = null,
    private val onFirst: ((List<AppEntry>) -> Unit)? = null,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "epple-apps").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val cache = HashMap<String, Pair<String, AppEntry>>()   // package -> (version key, entry); worker thread only
    private val artDir = File(app.cacheDir, "art").apply { mkdirs() }   // rendered tile art, one PNG per package + version
    private var started = false
    /** Dark tile art (Settings > App icons). Set before [start]; change it later with [setDark]. */
    @Volatile var darkArt = true

    /** Switches between dark and original art: both are cached on disk, so this only decodes, never re-renders. */
    fun setDark(on: Boolean) {
        if (on == darkArt) return
        darkArt = on
        if (started) load()
    }

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
            val t0 = android.os.SystemClock.uptimeMillis()
            val apps = try { query() } catch (e: RuntimeException) { Log.w(TAG, "app query failed", e); null }
            Log.i("EppleStart", "app query ${android.os.SystemClock.uptimeMillis() - t0} ms (${apps?.size} apps, ${cache.size} cached)")
            if (apps != null) main.post { if (started) onApps(apps) }
            // Only after the list is handed over: preparing all of this first held the app row back by about 2 s.
            if (apps != null && warmOne != null) for (e in warm?.invoke(apps) ?: apps) { if (worker.isShutdown) break; warmOne.invoke(e) }
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
        val initial = cache.isEmpty()
        val want = if (initial && onFirst != null) first?.invoke()?.toSet().orEmpty() else emptySet()
        val ordered = found.entries.sortedBy { if (it.key in want) 0 else 1 }      // stable: wanted ones first
        var told = want.isEmpty()
        for ((pkg, v) in ordered) {
            if (!told && pkg !in want) { told = true; val part = ArrayList(out); main.post { if (started) onFirst?.invoke(part) } }
            val (ai, cat) = v
            seen.add(pkg)
            val pi = try { pm.getPackageInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { continue }
            // Artwork is keyed by package, version and install time: an update (even one keeping the version) re-renders it.
            val key = "r${RENDER}_${pi.longVersionCode}_${pi.lastUpdateTime}_${tileW}x$tileH"
            val dark = darkArt
            val cached = cache[pkg]
            if (cached != null && cached.first == key + dark) { out.add(cached.second); continue }
            val entry = build(pm, ai, cat, key, dark) ?: continue
            cache[pkg] = key + dark to entry
            out.add(entry)
        }
        cache.keys.retainAll(seen)
        // Drop art for uninstalled apps and superseded versions.
        artDir.listFiles()?.forEach { f -> val pkg = f.name.substringBefore("@"); if (pkg !in seen || cache[pkg]?.first?.removeSuffix("true")?.removeSuffix("false")?.let { f.name != "$pkg@$it.png" && f.name != "$pkg@$it.orig.png" && f.name != "$pkg@$it.src.png" } == true) f.delete() }
        val collator = Collator.getInstance()
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        return if (debugRepeat > 1) List(out.size * debugRepeat) { out[it % out.size] } else out
    }

    private fun build(pm: PackageManager, ai: ActivityInfo, cat: String, key: String, dark: Boolean): AppEntry? = try {
        val label = ai.loadLabel(pm).toString().trim().ifEmpty { ai.packageName }
        val file = File(artDir, "${ai.packageName}@$key.png")
        val origFile = File(artDir, "${ai.packageName}@$key.orig.png")  // original artwork (Settings > App icons: Light)
        val srcFile = File(artDir, "${ai.packageName}@$key.src.png")   // backdrop colours, taken from the original artwork
        // Both looks are cached; only the one in use is decoded and kept in memory.
        val want = if (dark) file else origFile
        var art = if (want.exists() && srcFile.exists()) BitmapFactory.decodeFile(want.path) else null
        var src = if (art != null) BitmapFactory.decodeFile(srcFile.path) else null
        if (art == null || src == null) {
            val t0 = android.os.SystemClock.uptimeMillis()
            val r = render(pm, ai)
            val t1 = android.os.SystemClock.uptimeMillis()
            src = Bitmap.createScaledBitmap(r.original, 8, 5, true)   // from the original: Home's backdrop colours don't change
            decorate?.invoke(Canvas(r.original), r.original.width, r.original.height)
            r.dark?.let { decorate?.invoke(Canvas(it), it.width, it.height) }
            val d = r.dark ?: r.original                              // dark tile art, or the original where it can't be adapted cleanly
            save(d, file); save(r.original, origFile); save(src, srcFile)
            Log.i(TAG, "art ${ai.packageName}: ${DarkArt.result}; render ${t1 - t0} ms, save ${android.os.SystemClock.uptimeMillis() - t1} ms")
            art = if (dark) d else r.original
            if (art !== d) d.recycle() else if (art !== r.original) r.original.recycle()
        }
        art.prepareToDraw()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val info = ai.applicationInfo
        // Two-step resample: a 12x7 average of the artwork, smoothed up to 96x54, becomes a soft colour field at full screen.
        val soft = backdropOf(src)
        AppEntry(ai.packageName, label, intent, art, soft, info.category)
    } catch (e: RuntimeException) {
        Log.w(TAG, "skipping ${ai.packageName}", e); null
    }

    /**
     * Backdrop source: the artwork averaged down to 8x5 and smoothed back up, so only its colours survive,
     * then saturated and darkened so white text stays readable over any app (including white banners).
     */
    private fun backdropOf(tiny: Bitmap): Bitmap {   // tiny: the original artwork averaged down to 8x5
        val mid = Bitmap.createScaledBitmap(tiny, 32, 18, true)
        val out = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(1.5f); postConcat(ColorMatrix().apply { setScale(0.52f, 0.52f, 0.52f, 1f) }) }
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(mid, null, Rect(0, 0, 96, 54), p)
        backdropFilter?.invoke(out)
        tiny.recycle(); mid.recycle()
        return out
    }

    private fun save(b: Bitmap, f: File) {
        try { f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: java.io.IOException) { Log.w(TAG, "art cache write failed", e) }
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
    /** The original tile art, and its dark version when one could be made cleanly (both at tile size). */
    private class Art(val original: Bitmap, val dark: Bitmap?)

    private fun render(pm: PackageManager, ai: ActivityInfo): Art {
        val res = try { pm.getResourcesForApplication(ai.applicationInfo) } catch (_: PackageManager.NameNotFoundException) { null }
        fun hd(id: Int, density: Int): Drawable? = if (id == 0 || res == null) null else
            try { res.getDrawableForDensity(id, density, null) } catch (_: RuntimeException) { null }
        val bannerId = if (ai.banner != 0) ai.banner else ai.applicationInfo.banner
        val banner = hd(bannerId, DisplayMetrics.DENSITY_XXHIGH) ?: ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        if (banner != null) return renderBanner(banner)
        val icon = hd(ai.iconResource, DisplayMetrics.DENSITY_XXXHIGH) ?: ai.loadIcon(pm)
        return if (icon is AdaptiveIconDrawable && icon.background != null) renderAdaptive(icon) else renderIcon(icon)
    }

    private fun canvasFor(w: Int = tileW, h: Int = tileH): Pair<Bitmap, Canvas> = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).let { it to Canvas(it) }

    /** Dark art is drawn and processed at [DarkArt.SS] times the tile size, then filtered down: smooth edges. */
    private fun big(): Pair<Bitmap, Canvas> = canvasFor(tileW * DarkArt.SS, tileH * DarkArt.SS)

    private fun finish(original: Bitmap, big: Bitmap, dark: Bitmap?): Art {
        big.recycle()
        if (dark == null) return Art(original, null)
        val (b, c) = canvasFor()
        c.drawBitmap(dark, null, Rect(0, 0, tileW, tileH), Paint(Paint.FILTER_BITMAP_FLAG))
        dark.recycle()
        return Art(original, b)
    }

    private fun renderBanner(d: Drawable): Art {
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        val shapeOk = iw <= 0 || ih <= 0 || kotlin.math.abs(iw.toFloat() / ih - tileW.toFloat() / tileH) <= 0.06f
        val probe = probe(d)
        val (b, c) = canvasFor(); drawBanner(c, b.width, b.height, d, probe, shapeOk, field = true)
        val (bb, bc) = big()
        // Opaque banner: its background is keyed out. Transparent banner: its own transparency is the logo's.
        val dark = if (probe.opaqueEdges) { drawBanner(bc, bb.width, bb.height, d, probe, shapeOk, field = true); DarkArt.banner(bb) }
                   else { drawBanner(bc, bb.width, bb.height, d, probe, shapeOk, field = false); DarkArt.layer(bb, null) }
        return finish(b, bb, dark)
    }

    /** Opaque 16:9 banner full-bleed; otherwise fitted whole (never stretched), with padding, on its edge colour. */
    private fun drawBanner(c: Canvas, cw: Int, ch: Int, d: Drawable, probe: Probe, shapeOk: Boolean, field: Boolean) {
        if (shapeOk && probe.opaqueEdges) { d.setBounds(0, 0, cw, ch); d.draw(c); return }
        if (field) c.drawColor(probe.field)
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        val pad = if (probe.opaqueEdges) 0f else 0.08f
        val aw = if (iw > 0) iw.toFloat() else cw.toFloat(); val ah = if (ih > 0) ih.toFloat() else ch.toFloat()
        val s = minOf(cw * (1 - 2 * pad) / aw, ch * (1 - 2 * pad) / ah)
        val w = (aw * s).toInt(); val h = (ah * s).toInt()
        d.setBounds((cw - w) / 2, (ch - h) / 2, (cw + w) / 2, (ch + h) / 2); d.draw(c)
    }

    /** Adaptive icon: the dark version uses the foreground layer alone, with the background layer's colour as brand. */
    private fun renderAdaptive(d: AdaptiveIconDrawable): Art {
        val (b, c) = canvasFor(); drawAdaptive(c, b.width, b.height, d, background = true)
        val brand = d.background?.let { probe(it) }?.field
        val (bb, bc) = big(); drawAdaptive(bc, bb.width, bb.height, d, background = false)
        return finish(b, bb, DarkArt.layer(bb, brand))
    }

    /** Background layer full-bleed (cover), foreground centred with its safe zone ~70% of the tile height. */
    private fun drawAdaptive(c: Canvas, cw: Int, ch: Int, d: AdaptiveIconDrawable, background: Boolean) {
        if (background) {
            // A layer is 108dp with a 72dp visible area; cover the tile with the background's visible area.
            val bgSide = cw * 108f / 72f
            val bl = (cw - bgSide) / 2f; val bt = (ch - bgSide) / 2f
            d.background?.let { it.setBounds(bl.toInt(), bt.toInt(), (bl + bgSide).toInt(), (bt + bgSide).toInt()); it.draw(c) }
        }
        d.foreground?.let {
            val fgSide = ch * 0.70f * 108f / 66f        // the 66dp safe zone spans 70% of the tile height
            val l = (cw - fgSide) / 2f; val t = (ch - fgSide) / 2f
            it.setBounds(l.toInt(), t.toInt(), (l + fgSide).toInt(), (t + fgSide).toInt()); it.draw(c)
        }
    }

    /** Legacy icon: centred at 62% of the tile height on its own edge colour, so the mark reads as the tile. */
    private fun renderIcon(d: Drawable): Art {
        val probe = probe(d)
        val (b, c) = canvasFor(); drawIcon(c, b.width, b.height, d, probe.field)
        val (bb, bc) = big()
        val dark = if (probe.opaqueEdges) { drawIcon(bc, bb.width, bb.height, d, probe.field); DarkArt.banner(bb) }
                   else { drawIcon(bc, bb.width, bb.height, d, null); DarkArt.layer(bb, null) }
        return finish(b, bb, dark)
    }

    private fun drawIcon(c: Canvas, cw: Int, ch: Int, d: Drawable, field: Int?) {
        field?.let { c.drawColor(it) }
        val s = (ch * 0.62f).toInt()
        val l = (cw - s) / 2; val t = (ch - s) / 2
        d.setBounds(l, t, l + s, t + s); d.draw(c)
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
        const val RENDER = 17   // bump when the artwork layout changes, so cached art is re-rendered
        const val BG = 0xFF262A33.toInt()
    }
}
