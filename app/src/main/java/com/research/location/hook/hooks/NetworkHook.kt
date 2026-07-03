package com.research.location.hook.hooks

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import java.net.NetworkInterface

/**
 * Priority 7: Hook network APIs to hide VPN and fake WiFi/Mobile state.
 */
class NetworkHook : BaseHook("Network", priority = 7) {

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)
        if (!config.network.hideVpn) return

        // Hook-1: NetworkCapabilities.hasTransport(TRANSPORT_VPN) → false
        try {
            hookAfter(
                NetworkCapabilities::class.java, "hasTransport",
                Int::class.javaPrimitiveType!!
            ) { param ->
                val transport = param.args[0] as Int
                if (transport == NetworkCapabilities.TRANSPORT_VPN) {
                    param.result = false
                }
            }
        } catch (_: Exception) {}

        // Hook-2: ConnectivityManager.getNetworkCapabilities(Network)
        try {
            hookAfter(
                ConnectivityManager::class.java, "getNetworkCapabilities",
                Network::class.java
            ) { param ->
                // Don't modify - just ensure no VPN capabilities
                val caps = param.result as? NetworkCapabilities ?: return@hookAfter
                try {
                    val field = NetworkCapabilities::class.java
                        .getDeclaredField("mTransportTypes")
                    field.isAccessible = true
                    val transports = field.get(caps) as? Long ?: return@hookAfter
                    // Clear VPN transport bit (index 4)
                    val vpnBit = 1L shl NetworkCapabilities.TRANSPORT_VPN
                    field.set(caps, transports and vpnBit.inv())
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Hook-3: NetworkInfo.getType() — if VPN, return TYPE_WIFI
        try {
            hookAfter(NetworkInfo::class.java, "getType") { param ->
                val type = param.result as? Int ?: return@hookAfter
                if (type == ConnectivityManager.TYPE_VPN) {
                    param.result = ConnectivityManager.TYPE_WIFI
                }
            }
        } catch (_: Exception) {}

        // Hook-4: NetworkInfo.getTypeName() — if VPN, return "WIFI"
        try {
            hookAfter(NetworkInfo::class.java, "getTypeName") { param ->
                val name = param.result as? String ?: return@hookAfter
                if (name == "VPN") {
                    param.result = "WIFI"
                }
            }
        } catch (_: Exception) {}

        // Hook-5: ConnectivityManager.getAllNetworks() — filter VPN networks
        try {
            hookAfter(ConnectivityManager::class.java, "getAllNetworks") { param ->
                val networks = param.result as? Array<Network> ?: return@hookAfter
                // Can't easily determine which Network is VPN from here,
                // but getNetworkCapabilities will be filtered by Hook-2
            }
        } catch (_: Exception) {}

        // Hook-6: NetworkInterface.getNetworkInterfaces() — filter VPN ifaces
        try {
            hookAfter(
                NetworkInterface::class.java, "getNetworkInterfaces"
            ) { param ->
                val ifaces = param.result as? java.util.Enumeration<NetworkInterface>
                    ?: return@hookAfter
                val filtered = java.util.Collections.list(ifaces).filter { iface ->
                    val name = iface.name.lowercase()
                    name != "tun0" && name != "ppp0" && !name.startsWith("tun")
                }
                param.result = java.util.Collections.enumeration(filtered)
            }
        } catch (_: Exception) {}

        log("Installed — VPN hiding active")
    }
}
