package com.eppletv.launcher.system

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * This app's ADB key: 2048-bit RSA, generated in and never leaving the Android Keystore. [allowed] records that the TV
 * has accepted it once, so features that are only conveniences (the Wi-Fi name in Control Center) never make the TV
 * show its "Allow debugging?" prompt on their own.
 */
object AdbKey : Adb.Signer {
    private const val ALIAS = "epple_adb"
    private val ks by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private fun ensure() {
        if (ks.containsAlias(ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
        }.generateKeyPair()
    }
    override val publicKey: RSAPublicKey get() { ensure(); return ks.getCertificate(ALIAS).publicKey as RSAPublicKey }
    override fun signRaw(data: ByteArray): ByteArray {
        ensure()
        return Signature.getInstance("NONEwithRSA").run { initSign(ks.getKey(ALIAS, null) as PrivateKey); update(data); sign() }
    }

    fun allowed(ctx: Context) = ctx.getSharedPreferences("launcher_mode", Context.MODE_PRIVATE).edit().putBoolean("adb_ok", true).apply()
    fun isAllowed(ctx: Context) = ctx.getSharedPreferences("launcher_mode", Context.MODE_PRIVATE).let { it.getBoolean("adb_ok", false) || it.contains("original") }
}
