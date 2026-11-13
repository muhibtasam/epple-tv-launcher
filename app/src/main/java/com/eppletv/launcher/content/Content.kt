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
) {
    fun intent(): Intent? = linkUri?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(linkPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("meta", meta).put("image", imageUrl)
        .put("uri", linkUri ?: JSONObject.NULL).put("pkg", linkPkg ?: JSONObject.NULL).put("kind", kind).put("summary", summary)
        .put("w", weight).put("o", origin).put("logo", logoUrl)
    companion object {
        fun of(o: JSONObject) = ContentItem(o.getString("id"), o.getString("title"), o.getString("meta"), o.getString("image"),
            if (o.isNull("uri")) null else o.getString("uri"), if (o.isNull("pkg")) null else o.getString("pkg"),
            o.optString("kind"), o.optString("summary"), o.optDouble("w", 0.0), o.optString("o"), o.optString("logo"))
    }
}

/** A provider of content for one or more apps. Add a new provider by implementing this and listing it in [Sources]. */
interface ContentSource {
    val key: String                       // cache key, unique per source
    val ready: Boolean                    // false when the source lacks what it needs (e.g. an API key)
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

    fun sourceFor(pkg: String): ContentSource? = sources.firstOrNull { it.ready && it.handles(pkg) }

    /** Main thread. Calls back at once when cached in memory; otherwise from disk or network. Empty list = show the fallback. */
    fun items(pkg: String, cb: (List<ContentItem>) -> Unit) {
        val src = sourceFor(pkg) ?: return cb(emptyList())
        val key = "${src.key}-$pkg"
        lists[key]?.let { return cb(it) }
        if (System.currentTimeMillis() - (failedAt[key] ?: 0L) < RETRY_MS) return cb(emptyList())
        listWaiters[key]?.let { it.add(cb); return }
        listWaiters[key] = arrayListOf(cb)
        io.execute {
            val file = File(listDir, "$key.json")
            var out = readList(file, fresh = true)
            if (out == null) {
                out = try {
                    src.load(net, pkg).also { l -> if (l.isNotEmpty()) file.writeText(JSONArray(l.map { it.json() }).toString()) }
                } catch (e: Exception) {
                    Log.w(TAG, "load ${src.key} failed: $e"); readList(file, fresh = false)   // stale copy beats nothing
                }
            }
            val result = out ?: emptyList()
            main.post {
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
        val groups = keyed.groupBy { it.origin }.values.map { ArrayDeque(it) }.shuffled(rnd)
        val out = ArrayList<ContentItem>(n)
        while (out.size < n) for (g in groups) { if (out.size < n) g.removeFirstOrNull()?.let { out.add(it) } }
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
