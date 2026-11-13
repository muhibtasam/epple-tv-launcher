package com.eppletv.launcher.pair

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The TV's private IPv4 address on Wi-Fi or Ethernet, which the setup server binds to.
 * VPN, cellular and public addresses are never used, so the server is not reachable from the
 * internet even when a VPN is active. Returns null when the TV is not on a local network.
 */
object LanAddress {
    fun find(ctx: Context): Inet4Address? = fromConnectivity(ctx) ?: fromInterfaces()

    private fun fromConnectivity(ctx: Context): Inet4Address? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val networks = (listOfNotNull(cm.activeNetwork) + cm.allNetworks).distinct()
            networks.firstNotNullOfOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstNotNullOfOrNull null
                val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!local || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
                cm.getLinkProperties(n)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull { isPrivate(it) }
            }
        } catch (_: SecurityException) { null }
    }

    private fun fromInterfaces(): Inet4Address? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { isPrivate(it) }
    } catch (_: Exception) { null }

    /** 10/8, 172.16/12 and 192.168/16. */
    fun isPrivate(a: Inet4Address) = a.isSiteLocalAddress
}
