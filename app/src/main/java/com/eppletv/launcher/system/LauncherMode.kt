package com.eppletv.launcher.system

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Makes Epple TV the TV's launcher, and gives it back, at the user's request only (Settings > Launcher, or setup).
 *
 * Google TV keeps answering the Home button with its own home screen while that app is enabled, so "Epple TV" mode:
 *  1. records the current Home app;
 *  2. refuses if the Google TV home screen or setup app saved a Wi-Fi network (turning such an app off makes Android
 *     forget the network);
 *  3. sets Epple TV as the Home activity (`cmd package set-home-activity`);
 *  4. turns off (pm disable-user: still installed) Google TV's setup app, whose recovery screen would take Home, then the
 *     Google TV home screen. Nothing else is touched.
 * "TV default" turns those apps back on, home screen first, and restores the recorded Home activity.
 * Every step goes through the TV's own adbd ([Adb]); the list of turned-off apps is kept here and in the TV's global
 * settings, so it survives clearing this app's data. Worker threads only.
 */
class LauncherMode(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("launcher_mode", Context.MODE_PRIVATE)

    val on: Boolean get() = prefs.getBoolean("on", false)

    /** Runs the switch; [status] reports progress in plain words (any thread). Returns null on success, else why not. */
    fun useEpple(status: (String) -> Unit): String? = run(status) { adb ->
        status("Checking the current Home screen…")
        val holder = homeHolder(adb)
        val original = when {
            holder.isEmpty() || holder == PKG -> prefs.getString("original", null) ?: GOOGLE_HOME
            holder == GOOGLE_SETUP -> GOOGLE_HOME                    // Some TVs leave the role with the setup app's recovery screen
            else -> homeActivity(adb, holder) ?: GOOGLE_HOME
        }
        // Google TV's setup app only where it has a Home screen of its own (its recovery screen); never on older Android TV.
        val setupHome = homeActivity(adb, GOOGLE_SETUP) != null
        val targets = listOfNotNull(if (setupHome) GOOGLE_SETUP else null, pkgOf(GOOGLE_HOME), pkgOf(original)).distinct()
            .filter { it != PKG && installed(adb, it) && it !in PROTECTED }
        status("Checking saved Wi-Fi networks…")
        val owners = wifiOwners(adb) ?: return@run "Couldn't check which app saved the Wi-Fi, so nothing changed. Try again in a minute."
        owners.firstOrNull { it in targets }?.let {
            return@run "The ${if (it == GOOGLE_SETUP) "TV's first setup" else "Google TV home screen"} saved your Wi-Fi network; turning it off " +
                "would erase it. In Settings > Network, forget the network and connect again, then try once more."
        }
        prefs.edit().putString("original", original).apply()
        status("Setting Epple TV as the Home screen…")
        if (!adb.shell("cmd package set-home-activity $HOME").contains("Success")) return@run "Android didn't accept Epple TV as the Home screen."
        val disabled = LinkedHashSet(recorded())
        for (p in targets) {
            status("Turning off ${label(p)}…")
            if (adb.shell("pm disable-user --user 0 $p").contains("disabled-user")) { disabled += p; record(adb, disabled) }
        }
        // What Epple TV's rows read from the TV (apps' own rows, Continue Watching): allowed here, so there is no prompt.
        status("Allowing TV listings…")
        adb.shell("pm grant $PKG android.permission.READ_TV_LISTINGS")
        status("Allowing notifications…")
        adb.shell("cmd notification allow_listener $LISTENER")
        prefs.edit().putBoolean("on", true).apply()
        null
    }

    /** Gives the Home button back to the TV's own launcher. Returns null on success, else why not. */
    /** Settings > Notifications: lets Epple TV read other apps' notifications (Android's notification access). */
    fun allowNotifications(status: (String) -> Unit): String? = run(status) { adb ->
        status("Allowing notifications…")
        adb.shell("cmd notification allow_listener $LISTENER"); null
    }

    fun useDefault(status: (String) -> Unit): String? = run(status) { adb ->
        val original = prefs.getString("original", null) ?: GOOGLE_HOME
        val list = (recorded() + recordedOnTv(adb)).distinct()
        // The home screen first, the setup app last: alone, its recovery screen would take the Home button.
        val order = list.sortedBy { if (it == GOOGLE_SETUP) 2 else if (it == pkgOf(original)) 0 else 1 }
        val failed = ArrayList<String>()
        for (p in order) {
            status("Turning ${label(p)} back on…")
            if (!adb.shell("pm enable $p").contains("enabled")) failed += p
        }
        status("Restoring the TV's Home screen…")
        val ok = adb.shell("cmd package set-home-activity '$original'").contains("Success")
        record(adb, failed.toSet())
        prefs.edit().putBoolean("on", false).apply()
        when {
            failed.isNotEmpty() -> "Couldn't turn back on: ${failed.joinToString { label(it) }}."
            !ok -> "The apps are back on, but Android didn't restore the TV's Home screen. Choose it in the TV's settings."
            else -> null
        }
    }

    /** Called from the crash handler: Epple TV crashed (synchronous write, the process is about to die). */
    fun noteCrash() {
        val now = System.currentTimeMillis()
        val list = (prefs.getString("crashes", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 5 * 60_000 } + now
        prefs.edit().putString("crashes", list.joinToString(",")).commit()
    }

    /** Epple TV is the launcher and crashed 3 times in 5 minutes: the TV's own launcher should come back. */
    fun crashLoop(): Boolean {
        if (!on) return false
        val now = System.currentTimeMillis()
        return (prefs.getString("crashes", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.count { now - it < 5 * 60_000 } >= 3
    }

    fun clearCrashes() = prefs.edit().remove("crashes").apply()

    private fun run(status: (String) -> Unit, block: (Adb) -> String?): String? = try {
        status("Connecting to this TV…")
        Adb.connect(AdbKey, waiting = { status("On the TV, choose “Always allow from this computer”, then Allow.") }).use { adb -> AdbKey.allowed(app); block(adb) }
    } catch (e: IOException) {
        e.message ?: "Couldn't reach this TV's debugging service."
    } catch (e: Exception) {
        "Something went wrong (${e.javaClass.simpleName})."
    }

    private fun homeHolder(adb: Adb): String {
        val h = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim()
        return if (h.matches(Regex("[\\w.]+"))) h else ""
    }

    private fun homeActivity(adb: Adb, pkg: String): String? =
        adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lines().map { it.trim() }.firstOrNull { it.startsWith("$pkg/") && it.matches(COMPONENT) }

    private fun installed(adb: Adb, pkg: String) = adb.shell("pm path $pkg").contains("package:")

    /** Apps that saved a Wi-Fi network (the "cname" of each saved network), or null when the Wi-Fi service didn't say. */
    private fun wifiOwners(adb: Adb): Set<String>? {
        val out = adb.shell("dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'")
        if (!out.contains("cname=") && !out.contains("Wi-Fi is")) return null
        return Regex("cname=([\\w.]+)").findAll(out).map { it.groupValues[1] }.toSet()
    }

    private fun recorded(): Set<String> = prefs.getStringSet("disabled", emptySet()) ?: emptySet()
    private fun recordedOnTv(adb: Adb): List<String> =
        adb.shell("settings get global $SETTING").trim().split(',').filter { it.matches(PKG_NAME) && it != PKG }

    private fun record(adb: Adb, s: Set<String>) {
        prefs.edit().putStringSet("disabled", s).apply()
        if (s.isEmpty()) adb.shell("settings delete global $SETTING") else adb.shell("settings put global $SETTING ${s.joinToString(",")}")
    }

    private fun label(p: String) = when (p) { GOOGLE_SETUP -> "Google TV setup"; pkgOf(GOOGLE_HOME) -> "the Google TV home screen"; else -> p }

    companion object {
        // This build's own package: com.eppletv.launcher, or com.eppletv.launcher.debug for debug builds. Class names
        // stay in com.eppletv.launcher, so components are written in full (".HomeEntry" would resolve against .debug).
        val PKG: String = com.eppletv.launcher.BuildConfig.APPLICATION_ID
        val LISTENER = "$PKG/com.eppletv.launcher.system.EppleNotifications"
        val HOME = "$PKG/com.eppletv.launcher.HomeEntry"
        const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
        const val GOOGLE_SETUP = "com.google.android.tungsten.setupwraith"
        private const val SETTING = "epple_disabled_apps"
        private val PKG_NAME = Regex("[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+")
        private val COMPONENT = Regex("[\\w.]+/[\\w.$]+")
        /** Never turned off, whatever the TV reports as its Home app. */
        private val PROTECTED = setOf("android", "com.android.settings", "com.android.tv.settings", "com.android.systemui", "com.android.vending")
        fun pkgOf(component: String) = component.substringBefore('/')
    }
}
