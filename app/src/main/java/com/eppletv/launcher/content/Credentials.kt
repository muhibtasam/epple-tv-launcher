package com.eppletv.launcher.content

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.eppletv.launcher.BuildConfig
import java.net.UnknownHostException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API credentials for the content providers. Keys entered on the TV are encrypted with an AES-GCM key that lives in
 * the Android Keystore (it never leaves the secure hardware); keys from the private build (local.properties) are the
 * fallback. Keys are never logged and are only ever shown masked (last four characters).
 */
object Credentials {
    enum class Key(val label: String, val purpose: String, internal val built: String) {
        TMDB("TMDB", "Movies, TV Shows, Search, details and artwork", BuildConfig.TMDB_KEY),
        WATCHMODE("Watchmode", "New releases on your services and direct title links", BuildConfig.WATCHMODE_KEY),
        TRAKT("Trakt", "What's trending worldwide", BuildConfig.TRAKT_ID),
        YOUTUBE("YouTube", "Trending videos and the Tapmad channel", BuildConfig.YOUTUBE_KEY),
    }

    private var store: SecureStore? = null
    fun init(ctx: Context) { if (store == null) store = SecureStore(ctx.applicationContext) }

    fun get(k: Key): String = store?.get(k.name) ?: k.built
    fun isSaved(k: Key) = store?.has(k.name) == true
    fun hasAny(k: Key) = get(k).isNotEmpty()
    fun set(k: Key, value: String) = store?.put(k.name, value.trim())
    fun clear(k: Key) = store?.remove(k.name)
    fun connected() = Key.values().count { hasAny(it) }

    /** "••••1a2b", or empty when there is no key. Never more than the last four characters. */
    fun masked(k: Key): String = get(k).let { if (it.isEmpty()) "" else "••••" + it.takeLast(4) }
    fun source(k: Key): String = when { isSaved(k) -> "Saved on this TV"; k.built.isNotEmpty() -> "Built in"; else -> "Not set" }

    class Check(val ok: Boolean, val message: String)

    /**
     * One lightweight request that proves [value] works (worker thread; not cached; nothing is logged but host and path).
     * A network failure is reported separately from a rejected key.
     */
    fun validate(net: Net, k: Key, value: String): Check {
        val v = value.trim()
        if (v.isEmpty()) return Check(false, "No key entered")
        return try {
            when (k) {
                Key.TMDB -> if (v.length > 40) net.bytes("https://api.themoviedb.org/3/configuration", mapOf("Authorization" to "Bearer $v"))
                            else net.bytes("https://api.themoviedb.org/3/configuration?api_key=$v")
                Key.WATCHMODE -> net.bytes("https://api.watchmode.com/v1/status/?apiKey=$v")
                Key.TRAKT -> net.bytes("https://api.trakt.tv/genres/movies", mapOf("trakt-api-version" to "2", "trakt-api-key" to v, "Content-Type" to "application/json"))
                Key.YOUTUBE -> net.bytes("https://www.googleapis.com/youtube/v3/i18nRegions?part=snippet&hl=en&key=$v")
            }
            Check(true, "Connected")
        } catch (e: Net.HttpError) {
            Check(false, if (e.code in setOf(400, 401, 403)) "Key not accepted" else "Server error ${e.code}")
        } catch (e: UnknownHostException) {
            Check(false, "Can't reach ${k.label}. Check the network")
        } catch (e: Exception) {
            Check(false, "Connection failed")
        }
    }
}

/** Small encrypted key-value store: AES-256-GCM with a non-exportable Android Keystore key. */
private class SecureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "epple_credentials"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    fun has(name: String) = prefs.contains(name)

    fun get(name: String): String? {
        val enc = prefs.getString(name, null) ?: return null
        return try {
            val raw = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) { null }
    }

    fun put(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString(name, Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
    }

    fun remove(name: String) = prefs.edit().remove(name).apply()
}
