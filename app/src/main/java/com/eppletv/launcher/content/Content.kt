package com.eppletv.launcher.content

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One piece of content shown in the hero area. [linkUri] opens the title inside [linkPkg] when the app supports it. */
class ContentItem(
    val id: String, val title: String, val meta: String, val imageUrl: String,
    val linkUri: String?, val linkPkg: String?,
    val kind: String = "",       // section label shown above the title, e.g. "New on Netflix"
    val summary: String = "",    // short description, may be empty
    val weight: Double = 0.0,    // 0..1 selection weight (popularity, recency, trending); 0 = rank by position
    val origin: String = "",     // "in" (Indian) or "intl"; used to balance the selection
    val logoUrl: String = "",    // transparent title logo when the source supplies one directly
    val posterUrl: String = "",  // portrait poster (2:3) for rows; empty = the landscape art, cropped
) {
    fun intent(): Intent? = linkUri?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(linkPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("meta", meta).put("image", imageUrl)
        .put("uri", linkUri ?: JSONObject.NULL).put("pkg", linkPkg ?: JSONObject.NULL).put("kind", kind).put("summary", summary)
        .put("w", weight).put("o", origin).put("logo", logoUrl).put("poster", posterUrl)
    companion object {
        fun of(o: JSONObject) = ContentItem(o.getString("id"), o.getString("title"), o.getString("meta"), o.getString("image"),
            if (o.isNull("uri")) null else o.getString("uri"), if (o.isNull("pkg")) null else o.getString("pkg"),
            o.optString("kind"), o.optString("summary"), o.optDouble("w", 0.0), o.optString("o"), o.optString("logo"), o.optString("poster"))
    }
}

/** A provider of content for one or more apps. Add a new provider by implementing this and listing it in [Sources]. */
interface ContentSource {
    val key: String                       // cache key, unique per source
    val ready: Boolean                    // false when the source lacks what it needs (e.g. an API key)
    /** The provider keys this source's requests use: changing one refreshes this source (see [ContentRepository.invalidate]). */
    val uses: Set<Credentials.Key> get() = emptySet()
    fun handles(pkg: String): Boolean
    fun load(net: Net, pkg: String): List<ContentItem>   // worker thread; throws on failure; may return a larger pool to pick from
    /** Worker thread. URL of a transparent title logo for [item], or null. */
    fun logo(net: Net, item: ContentItem): String? = item.logoUrl.ifEmpty { null }
}

/**
 * Minimal HTTPS client. Every request is counted and logged (tag EppleNet) by host and path only:
 * query strings and headers, where API keys travel, are never logged.
 */
class Net(private val dir: File) {
    val calls = AtomicInteger()
    private val locks = HashMap<String, Any>()

    fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val n = calls.incrementAndGet()
        Log.i("EppleNet", "#$n GET ${url.substringBefore('?')}")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/json, image/*")
            c.setRequestProperty("User-Agent", "EppleTV/0.3")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    fun text(url: String, headers: Map<String, String> = emptyMap()) = String(bytes(url, headers))

    /**
     * JSON response cached on disk under [key] for [ttlMs]. Concurrent callers for the same key wait for one request.
     * On failure a stale copy is returned if there is one.
     */
    fun cached(key: String, ttlMs: Long, url: String, headers: Map<String, String> = emptyMap()): String {
        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        synchronized(lock) {
            val f = File(dir, "raw-$key.json")
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttlMs) return f.readText()
            return try {
                text(url, headers).also { body -> File(dir, f.name + ".tmp").apply { writeText(body); renameTo(f) } }
            } catch (e: Exception) {
                if (f.exists()) f.readText() else throw e
            }
        }
    }

    class HttpError(val code: Int) : java.io.IOException("HTTP $code")
}

/**
 * Loads content lists and artwork with three cache levels (memory, disk, network) and in-flight de-duplication,
 * so a list is fetched at most once per [TTL_MS] and an image at most once ever.
 */
class ContentRepository(ctx: Context, private val sources: List<ContentSource>, private val backdropFilter: ((Bitmap) -> Unit)? = null) {
    private val listDir = File(ctx.filesDir, "content").apply { mkdirs() }
    val net = Net(listDir)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "epple-content").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val imgDir = File(ctx.cacheDir, "img").apply { mkdirs() }
    private val launchedAt = System.currentTimeMillis()
    init { io.execute { trimImages() } }   // once per launch, off the main thread
    private val lists = HashMap<String, List<ContentItem>>()                       // source key -> items (main thread)
    private val listWaiters = HashMap<String, ArrayList<(List<ContentItem>) -> Unit>>()
    private val failedAt = HashMap<String, Long>()
    private val images = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val imageWaiters = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()
    private val logos = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {    // decoded title logos by URL
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val logoUrls = HashMap<String, String>()                             // item id -> logo URL ("" = none), this launch
    private val logoWaiters = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()
    private val shownPrefs = ctx.getSharedPreferences("shown", Context.MODE_PRIVATE)
    private val usedThisLaunch = HashSet<String>()                                // titles already picked for another app
    private val rnd = java.util.Random()
    private val thumbs = object : LruCache<String, Bitmap>(14 * 1024 * 1024) {      // card artwork (no scrim)
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thumbWaiters = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()
    private val results = LruCache<String, Any>(64)                                    // catalogue results this launch
    private val workWaiters = HashMap<String, ArrayList<(Any?) -> Unit>>()
    /** 0 balanced, 1 more Indian, 2 more Hollywood, 3 Hollywood only (Settings > Indian titles). */
    var indianMode = 0

    fun sourceFor(pkg: String): ContentSource? = sources.firstOrNull { it.ready && it.handles(pkg) }

    /** Main thread. Calls back at once when cached in memory; otherwise from disk or network. Empty list = show the fallback. */
    fun items(pkg: String, cb: (List<ContentItem>) -> Unit) {
        val src = sourceFor(pkg) ?: return cb(emptyList())
        val key = "${src.key}-$pkg"
        lists[key]?.let { return cb(it) }
        if (System.currentTimeMillis() - (failedAt[key] ?: 0L) < RETRY_MS) return cb(emptyList())
        listWaiters[key]?.let { it.add(cb); return }
        listWaiters[key] = arrayListOf(cb)
        val epoch = epochs[src.key] ?: 0
        io.execute {
            val file = File(listDir, "$key.json")
            var out = readList(file, fresh = true)
            if (out == null) {
                out = try {
                    src.load(net, pkg).also { l -> if (l.isNotEmpty() && (epochs[src.key] ?: 0) == epoch) file.writeText(JSONArray(l.map { it.json() }).toString()) }
                } catch (e: Exception) {
                    Log.w(TAG, "load ${src.key} failed: $e"); readList(file, fresh = false)   // stale copy beats nothing
                }
            }
            val result = out ?: emptyList()
            main.post {
                if ((epochs[src.key] ?: 0) != epoch) return@post          // the key changed meanwhile: its waiters were answered
                val picked = if (result.isEmpty()) result else curate(key, result)
                if (picked.isEmpty()) failedAt[key] = System.currentTimeMillis() else lists[key] = picked
                listWaiters.remove(key)?.forEach { it(picked) }
            }
        }
    }

    /**
     * Picks what an app shows this launch from its source's pool: weighted random sampling (Efraimidis-Spirakis) on the
     * source's popularity/recency/trending weight, so popular titles lead more often but the selection and order change
     * every launch. Titles picked in the last launches, or already picked for another app this launch, are weighted down.
     * When the pool has both Indian and international titles they alternate, starting with either, for a balanced mix.
     */
    private fun curate(key: String, pool: List<ContentItem>): List<ContentItem> {
        val recent = shownPrefs.getString(key, "")!!.split('\n').toHashSet()
        val keyed = pool.mapIndexed { i, it ->
            var w = if (it.weight > 0) it.weight else 1.0 / (1 + i * 0.15)
            if (it.id in recent) w *= 0.2
            if (it.id in usedThisLaunch) w *= 0.05
            it to Math.pow(rnd.nextDouble(), 1.0 / w.coerceAtLeast(1e-3))
        }.sortedByDescending { it.second }.map { it.first }
        val n = minOf(SHOW, keyed.size)
        val byOrigin = keyed.groupBy { it.origin }
        val out = ArrayList<ContentItem>(n)
        if (byOrigin.keys.containsAll(listOf("in", "intl"))) {
            val ind = ArrayDeque(byOrigin.getValue("in")); val intl = ArrayDeque(byOrigin.getValue("intl"))
            // Interleaving pattern per setting; the first slot is random so the opening title alternates between launches.
            val pattern = when (indianMode) { 1 -> "iiw"; 2 -> "wwi"; 3 -> "w"; else -> if (rnd.nextBoolean()) "iw" else "wi" }
            var k = 0
            while (out.size < n && (ind.isNotEmpty() || intl.isNotEmpty())) {
                val wantIndian = pattern[k++ % pattern.length] == 'i'
                val next = if (wantIndian) ind.removeFirstOrNull() ?: if (indianMode == 3) null else intl.removeFirstOrNull()
                           else intl.removeFirstOrNull() ?: if (indianMode == 3) null else ind.removeFirstOrNull()
                if (next == null) { if (indianMode == 3 && intl.isEmpty()) break else continue }
                out.add(next)
            }
        } else {
            val groups = byOrigin.values.map { ArrayDeque(it) }.shuffled(rnd)
            while (out.size < n) for (g in groups) { if (out.size < n) g.removeFirstOrNull()?.let { out.add(it) } }
        }
        usedThisLaunch += out.map { it.id }
        Log.i(TAG, "picked $key (pool ${pool.size}): " + out.joinToString(" | ") { "${it.origin}:${it.title}" })
        shownPrefs.edit().putString(key, (out.map { it.id } + recent.filter { it.isNotEmpty() }).distinct().take(40).joinToString("\n")).apply()
        return out
    }

    private fun readList(f: File, fresh: Boolean): List<ContentItem>? = try {
        if (!f.exists() || (fresh && System.currentTimeMillis() - f.lastModified() > TTL_MS)) null
        else JSONArray(f.readText()).let { a -> List(a.length()) { ContentItem.of(a.getJSONObject(it)) } }
    } catch (e: Exception) { null }

    /** Main thread. Calls back at once when the bitmap is in memory. */
    fun image(url: String, cb: (Bitmap?) -> Unit) {
        images.get(url)?.let { return cb(it) }
        imageWaiters[url]?.let { it.add(cb); return }
        imageWaiters[url] = arrayListOf(cb)
        io.execute {
            val bmp = try {
                val f = File(imgDir, sha1(url))
                if (!f.exists()) { val tmp = File(imgDir, f.name + ".tmp"); tmp.writeBytes(net.bytes(url)); tmp.renameTo(f) }
                else f.setLastModified(System.currentTimeMillis())          // recency for trimImages
                decode(f)
            } catch (e: Exception) { Log.w(TAG, "image failed: $e"); null }
            main.post {
                if (bmp != null) images.put(url, bmp)
                imageWaiters.remove(url)?.forEach { it(bmp) }
            }
        }
    }

    fun prefetch(url: String) = image(url) {}

    /** Main thread. Card artwork, decoded once at card size (no scrim), from the shared disk cache. */
    fun thumb(url: String, cb: (Bitmap?) -> Unit) {
        if (url.isEmpty()) return cb(null)
        thumbs.get(url)?.let { return cb(it) }
        thumbWaiters[url]?.let { it.add(cb); return }
        thumbWaiters[url] = arrayListOf(cb)
        io.execute {
            // w780 artwork decodes at 390 px: card size, a quarter of the pixels.
            val bmp = try { decodeScaled(fetch(url), 380) } catch (e: Exception) { Log.w(TAG, "thumb failed: ${e.javaClass.simpleName}"); null }
            main.post { if (bmp != null) thumbs.put(url, bmp); thumbWaiters.remove(url)?.forEach { it(bmp) } }
        }
    }

    /** Main thread. A full-screen image without the hero scrim, not kept in memory caches (the screensaver holds two). */
    fun wide(url: String, cb: (Bitmap?) -> Unit) {
        io.execute {
            val bmp = try { decodeScaled(fetch(url), 1920) } catch (e: Exception) { null }
            main.post { cb(bmp) }
        }
    }

    /**
     * Main thread. Runs [job] on the worker and calls back on the main thread; results are kept for this launch under
     * [key] and concurrent requests for the same key share one run, so screens never repeat a request.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> work(key: String, job: () -> T, cb: (T?) -> Unit) {
        results.get(key)?.let { return cb(it as T) }
        workWaiters[key]?.let { it.add { v -> cb(v as T?) }; return }
        workWaiters[key] = arrayListOf({ v -> cb(v as T?) })
        io.execute {
            val v: Any? = try { job() } catch (e: Exception) { Log.w(TAG, "$key failed: ${e.javaClass.simpleName} ${e.message}"); null }
            main.post { if (v != null) results.put(key, v); workWaiters.remove(key)?.forEach { it(v) } }
        }
    }
    fun forget(key: String) { results.remove(key) }

    /** Main thread. Runs [job] once on the worker (no memo: e.g. testing a credential) and calls back on the main thread. */
    fun <T : Any> run(job: () -> T, cb: (T?) -> Unit) {
        io.execute { val v: T? = try { job() } catch (e: Exception) { null }; main.post { cb(v) } }
    }

    /** Main thread. The decoded full-screen image for [url] if it is in memory, else null (no loading). */
    fun cachedImage(url: String): Bitmap? = images.get(url)

    /** Every content item loaded for the top-row apps this launch. */
    fun loadedItems(): List<ContentItem> = lists.values.flatten()

    fun imageCacheBytes(): Long = imgDir.listFiles()?.sumOf { it.length() } ?: 0L
    fun listCacheBytes(): Long = listDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Main thread. Empties the image caches (memory and disk). */
    fun clearImages() {
        images.evictAll(); thumbs.evictAll(); logos.evictAll(); logoUrls.clear()
        io.execute { imgDir.listFiles()?.forEach { it.delete() } }
    }

    /** Main thread. The launcher went to the background: memory caches are emptied (disk copies stay). */
    fun trimMemory() { thumbs.evictAll(); images.evictAll(); logos.evictAll() }

    /** Main thread. Picks titles again from the saved pools (after a mix setting changed). */
    fun resetPicks() { lists.clear() }

    private val epochs = java.util.concurrent.ConcurrentHashMap<String, Int>()            // source key -> bumped on invalidate; late loads of an older epoch are dropped

    /**
     * Main thread. A provider key changed ([changed]), or every source ([all]: region or language changes): forgets only
     * the affected sources' lists (memory and disk), retry timers and in-flight loads, so their next request fetches
     * again with the new key. Others keep everything. Returns the keys of the sources that were reset.
     */
    fun invalidate(changed: Set<Credentials.Key>, all: Boolean = false): Set<String> {
        val hit = sources.filter { all || it.uses.any { k -> k in changed } }.map { it.key }.toSet()
        for (k in hit) {
            epochs[k] = (epochs[k] ?: 0) + 1
            val prefix = "$k-"
            lists.keys.removeAll { it.startsWith(prefix) }
            failedAt.keys.removeAll { it.startsWith(prefix) }
            listWaiters.keys.filter { it.startsWith(prefix) }.forEach { w -> listWaiters.remove(w)?.forEach { it(emptyList()) } }
            io.execute { listDir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }?.forEach { it.delete() } }
        }
        if (all || Credentials.Key.TMDB in changed) { results.evictAll(); logoUrls.clear() }    // catalogue answers and logo lookups use TMDB
        return hit
    }

    /** Main thread. Drops cached lists and API responses so everything is fetched fresh. */
    fun clearLists() {
        lists.clear(); failedAt.clear(); results.evictAll()
        io.execute { listDir.listFiles()?.forEach { it.delete() } }
    }

    private fun fetch(url: String): File {
        val f = File(imgDir, sha1(url))
        if (!f.exists()) { val tmp = File(imgDir, f.name + ".tmp"); tmp.writeBytes(net.bytes(url)); tmp.renameTo(f) }
        else f.setLastModified(System.currentTimeMillis())
        return f
    }

    private fun decodeScaled(f: File, maxW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })?.also { it.prepareToDraw() }
    }

    /**
     * Main thread. The item's transparent title logo, or null (then the title is set in type). Resolved and downloaded
     * at most once per launch per title (URL lookups are cached on disk for 30 days, files forever), decoded once.
     */
    fun logo(item: ContentItem, cb: (Bitmap?) -> Unit) {
        logoUrls[item.id]?.let { u -> if (u.isEmpty()) return cb(null); logos.get(u)?.let { return cb(it) } }
        logoWaiters[item.id]?.let { it.add(cb); return }
        logoWaiters[item.id] = arrayListOf(cb)
        io.execute {
            var url = ""
            val bmp = try {
                url = item.logoUrl.ifEmpty { sources.firstNotNullOfOrNull { s -> if (s.ready) try { s.logo(net, item) } catch (e: Exception) { null } else null } ?: "" }
                if (url.isEmpty()) null else {
                    val f = File(imgDir, "logo-" + sha1(url))
                    if (!f.exists()) { val tmp = File(imgDir, f.name + ".tmp"); tmp.writeBytes(net.bytes(url)); tmp.renameTo(f) }
                else f.setLastModified(System.currentTimeMillis())          // recency for trimImages
                    decodeLogo(f)
                }
            } catch (e: Exception) { Log.w(TAG, "logo failed for ${item.id}: ${e.javaClass.simpleName}"); null }
            main.post {
                logoUrls[item.id] = if (bmp == null) "" else url
                if (bmp != null) logos.put(url, bmp)
                logoWaiters.remove(item.id)?.forEach { it(bmp) }
            }
        }
    }

    /** Logos keep their transparency (ARGB_8888), at most ~1000 px wide; the hero shows them up to 760 px. */
    private fun decodeLogo(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= 1000) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.also { it.prepareToDraw() }
    }

    /** Warms the lists (and each first image) for [pkgs] one after another, so switching apps is instant. */
    fun warm(pkgs: List<String>) {
        val todo = pkgs.filter { sourceFor(it) != null }.toMutableList()
        fun next() {
            val p = todo.removeFirstOrNull() ?: return
            items(p) { l -> l.firstOrNull()?.let { prefetch(it.imageUrl) }; next() }
        }
        next()
    }

    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 1920) s *= 2
        // Mutable so the hero scrim is painted into the image once here, on the worker, instead of as overlay passes every frame.
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true })
            ?.also { backdropFilter?.invoke(it); it.prepareToDraw() }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The image cache grows with every title shown, and the selection changes every launch, so it is bounded:
     * above [IMG_CAP] the least recently used files are deleted down to [IMG_KEEP].
     */
    private fun trimImages() {
        val files = imgDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= IMG_CAP) return
        val before = total
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= IMG_KEEP) break
            if (f.lastModified() >= launchedAt) continue   // used this launch (re-read, not the stale sort key)
            val len = f.length()
            if (f.delete()) total -= len
        }
        Log.i(TAG, "image cache trimmed ${before / 1024} KB -> ${total / 1024} KB")
    }

    fun stop() { io.shutdownNow() }

    private companion object {
        const val TAG = "EppleContent"
        const val TTL_MS = 12 * 60 * 60 * 1000L
        const val RETRY_MS = 5 * 60 * 1000L
        const val SHOW = 14          // titles an app shows per launch
        const val IMG_CAP = 40L * 1024 * 1024
        const val IMG_KEEP = 30L * 1024 * 1024
    }
}
