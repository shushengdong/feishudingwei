package com.research.location.hook.hooks

import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.research.location.hook.data.WifiDataBuilder

/**
 * Priority 3: Hook WifiManager and ConnectivityManager to return fake WiFi data.
 */
class WifiHook : BaseHook("Wifi", priority = 3) {

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)
        if (!config.wifi.enabled) return

        // Hook-1: getScanResults() → fake AP list
        hookReplace(
            WifiManager::class.java, "getScanResults"
        ) { _ ->
            val e = engine ?: return@hookReplace emptyList<android.net.wifi.ScanResult>()
            val frame = e.currentFrame
            val wcfg = config.wifi

            WifiDataBuilder.buildScanResults(
                lat = frame.jitteredLat,
                lng = frame.jitteredLng,
                apCount = wcfg.apCount,
                primarySsid = wcfg.primarySsid,
                primaryBssid = wcfg.primaryBssid.ifEmpty {
                    WifiDataBuilder.generateBssid(
                        frame.jitteredLat, frame.jitteredLng, 0,
                        wcfg.ouiDistribution,
                        frame.frameIndex.toLong()
                    )
                },
                rssiRange = wcfg.rssiRange[0]..wcfg.rssiRange[1],
                channels = wcfg.channels,
                ouiDistribution = wcfg.ouiDistribution,
                seed = frame.frameSeed
            )
        }

        // Hook-2: getConnectionInfo() → fake connected WiFi
        hookReplace(
            WifiManager::class.java, "getConnectionInfo"
        ) { _ ->
            val wcfg = config.wifi
            val bssid = wcfg.primaryBssid.ifEmpty {
                val e = engine
                if (e != null) {
                    val frame = e.currentFrame
                    WifiDataBuilder.generateBssid(
                        frame.jitteredLat, frame.jitteredLng, 0,
                        wcfg.ouiDistribution, frame.frameIndex.toLong()
                    )
                } else "14:CF:92:AB:CD:EF"
            }
            WifiDataBuilder.buildWifiInfo(wcfg.primarySsid, bssid, -45)
        }

        // Hook-3: isWifiEnabled() → true
        hookAfter(WifiManager::class.java, "isWifiEnabled") { param ->
            param.result = true
        }

        // Hook-4: getWifiState() → WIFI_STATE_ENABLED
        hookAfter(WifiManager::class.java, "getWifiState") { param ->
            param.result = WifiManager.WIFI_STATE_ENABLED
        }

        // Hook-5: startScan() → swallow
        hookBefore(WifiManager::class.java, "startScan") { param ->
            param.result = true  // pretend scan started successfully
        }

        // Hook-6: getConfiguredNetworks() → non-empty stub
        hookReplace(WifiManager::class.java, "getConfiguredNetworks") { _ ->
            listOf<android.net.wifi.WifiConfiguration>()
        }

        // Hook-7: getDhcpInfo() → fake DHCP
        hookReplace(WifiManager::class.java, "getDhcpInfo") { _ ->
            android.net.DhcpInfo().apply {
                ipAddress = ipToInt("192.168.1.105")
                gateway = ipToInt("192.168.1.1")
                dns1 = ipToInt("114.114.114.114")
                dns2 = ipToInt("8.8.8.8")
            }
        }

        // ConnectivityManager hooks
        if (config.wifi.fakeWifiConnection) {
            try {
                hookReplace(
                    ConnectivityManager::class.java, "getActiveNetworkInfo"
                ) { _ ->
                    try {
                        val ctor = NetworkInfo::class.java.getDeclaredConstructor(
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            String::class.java, String::class.java
                        )
                        ctor.isAccessible = true
                        ctor.newInstance(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "")
                            .apply {
                                isConnected = true
                                isAvailable = true
                            }
                    } catch (_: Exception) { null }
                }
            } catch (_: Exception) {}
        }

        log("Installed — WiFi spoofing active")
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.split(".").map { it.toIntOrNull() ?: 0 }
        return ((parts[0] and 0xFF) shl 24) or
                ((parts[1] and 0xFF) shl 16) or
                ((parts[2] and 0xFF) shl 8) or
                (parts[3] and 0xFF)
    }
}
