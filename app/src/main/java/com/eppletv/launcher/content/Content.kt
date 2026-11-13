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
) {
    fun intent(): Intent? = linkUri?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(linkPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("meta", meta).put("image", imageUrl)
        .put("uri", linkUri ?: JSONObject.NULL).put("pkg", linkPkg ?: JSONObject.NULL).put("kind", kind).put("summary", summary)
    companion object {
        fun of(o: JSONObject) = ContentItem(o.getString("id"), o.getString("title"), o.getString("meta"), o.getString("image"),
            if (o.isNull("uri")) null else o.getString("uri"), if (o.isNull("pkg")) null else o.getString("pkg"),
            o.optString("kind"), o.optString("summary"))
    }
}

/** A provider of content for one or more apps. Add a new provider by implementing this and listing it in [Sources]. */
interface ContentSource {
    val key: String                       // cache key, unique per source
    val ready: Boolean                    // false when the source lacks what it needs (e.g. an API key)
    fun handles(pkg: String): Boolean
    fun load(net: Net, pkg: String): List<ContentItem>   // worker thread; throws on failure
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
    private val lists = HashMap<String, List<ContentItem>>()                       // source key -> items (main thread)
    private val listWaiters = HashMap<String, ArrayList<(List<ContentItem>) -> Unit>>()
    private val failedAt = HashMap<String, Long>()
    private val images = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val imageWaiters = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()

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
                if (result.isEmpty()) failedAt[key] = System.currentTimeMillis() else lists[key] = result
                listWaiters.remove(key)?.forEach { it(result) }
            }
        }
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
                decode(f)
            } catch (e: Exception) { Log.w(TAG, "image failed: $e"); null }
            main.post {
                if (bmp != null) images.put(url, bmp)
                imageWaiters.remove(url)?.forEach { it(bmp) }
            }
        }
    }

    fun prefetch(url: String) = image(url) {}

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

    fun stop() { io.shutdownNow() }

    private companion object {
        const val TAG = "EppleContent"
        const val TTL_MS = 12 * 60 * 60 * 1000L
        const val RETRY_MS = 5 * 60 * 1000L
    }
}
