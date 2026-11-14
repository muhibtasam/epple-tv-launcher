package com.eppletv.launcher.system

import android.content.Context
import java.io.IOException

/**
 * Control Center's TV actions that an app can't do by itself, through the TV's own adbd ([Adb]) with fixed commands.
 * Worker threads only.
 */
class TvControl(ctx: Context) {
    private val app = ctx.applicationContext

    /** Puts the TV in standby. Returns null on success, else why not. */
    fun standby(): String? = try {
        Adb.connect(AdbKey, waiting = {}).use { adb -> AdbKey.allowed(app); adb.shell("input keyevent KEYCODE_SLEEP"); null }
    } catch (e: IOException) { e.message ?: "Couldn't reach this TV's debugging service." }

    /**
     * The Wi-Fi network's name, or null. Only once the TV has allowed this app's key: a convenience never makes the TV
     * show its debugging prompt. (Android hides the name from apps without location access, which a launcher shouldn't ask for.)
     */
    fun wifiName(): String? {
        if (!AdbKey.isAllowed(app)) return null
        return try {
            Adb.connect(AdbKey, waiting = {}, promptMs = 2000).use { adb ->
                Regex("connected to \"([^\"]{1,64})\"").find(adb.shell("cmd wifi status"))?.groupValues?.get(1)
            }
        } catch (_: IOException) { null }
    }
}
