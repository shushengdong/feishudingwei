package com.research.location

import com.google.gson.GsonBuilder
import com.research.location.hook.MockConfig
import com.research.location.hook.LocationConfig
import com.research.location.hook.BehaviorConfig
import com.research.location.hook.WifiConfig
import com.research.location.hook.CellConfig
import com.research.location.hook.GnssConfig
import com.research.location.hook.SensorConfig
import com.research.location.hook.NetworkConfig
import com.research.location.hook.SystemConfig
import com.research.location.hook.SelfHideConfig
import com.research.location.model.SavedLocation
import java.io.File

/**
 * Writes the configuration JSON file that the Xposed module reads.
 *
 * This is the ONLY communication channel between the management app
 * and the Xposed module running inside the target app's process.
 */
object ConfigWriter {
    private const val CONFIG_DIR = "/sdcard/location_mod"
    private const val CONFIG_FILE = "$CONFIG_DIR/config.json"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Write config from a SavedLocation and target package.
     */
    fun writeConfig(
        location: SavedLocation,
        targetPackage: String,
        wifiSsid: String = "",
        wifiBssid: String = ""
    ): Boolean {
        return try {
            File(CONFIG_DIR).mkdirs()

            val config = MockConfig(
                version = 3,
                enabled = true,
                targetPackages = listOf(targetPackage),
                location = LocationConfig(
                    lat = location.lat,
                    lng = location.lng,
                    label = location.name,
                    city = extractCity(location.name),
                    altitude = estimateAltitude(location.lat)
                ),
                behavior = BehaviorConfig(
                    updateIntervalMs = 2000,
                    mode = "walking",
                    gpsJitterMeters = 15.0,
                    speedRangeMs = listOf(0.0, 1.5),
                    accuracyRangeM = listOf(3.0, 25.0)
                ),
                wifi = WifiConfig(
                    enabled = true,
                    primarySsid = wifiSsid.ifEmpty { "Office-WiFi-5G" },
                    primaryBssid = wifiBssid,
                    apCount = 6
                ),
                cell = CellConfig(
                    enabled = true,
                    mcc = location.mcc.ifEmpty { "460" },
                    mnc = location.mnc.ifEmpty { "00" },
                    operatorName = mncToOperator(location.mnc),
                    lac = location.lac.takeIf { it > 0 } ?: 43012
                ),
                gnss = GnssConfig(enabled = true),
                sensor = SensorConfig(enabled = true),
                network = NetworkConfig(hideVpn = true, fakeWifiConnection = true),
                system = SystemConfig(
                    hideDeveloperOptions = true,
                    hideAdbEnabled = true,
                    hideMockLocationApp = true,
                    fakeBuildTags = true
                ),
                selfHide = SelfHideConfig(
                    hideAppFromPackageList = true,
                    hideRootFiles = true,
                    interceptRootCommands = true
                )
            )

            val json = gson.toJson(config)
            File(CONFIG_FILE).writeText(json)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Read current config (for diagnostics).
     */
    fun readConfig(): MockConfig? {
        return try {
            val file = File(CONFIG_FILE)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), MockConfig::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check if config file exists and is valid.
     */
    fun isConfigValid(): Boolean {
        return try {
            val config = readConfig()
            config != null && config.enabled && config.targetPackages.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Disable the config (stop mocking without deleting the file).
     */
    fun disableConfig(): Boolean {
        return try {
            val config = readConfig() ?: return false
            val updated = config.copy(enabled = false)
            File(CONFIG_FILE).writeText(gson.toJson(updated))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Re-enable a previously disabled config */
    fun enableConfig(): Boolean {
        return try {
            val config = readConfig() ?: return false
            val updated = config.copy(enabled = true)
            File(CONFIG_FILE).writeText(gson.toJson(updated))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun configFilePath(): String = CONFIG_FILE

    private fun extractCity(name: String): String = when {
        name.contains("北京") -> "北京"
        name.contains("上海") -> "上海"
        name.contains("广州") -> "广州"
        name.contains("深圳") -> "深圳"
        name.contains("成都") -> "成都"
        name.contains("杭州") -> "杭州"
        name.contains("武汉") -> "武汉"
        name.contains("南京") -> "南京"
        else -> ""
    }

    private fun estimateAltitude(lat: Double): Double = when {
        lat > 35 -> 50.0   // Northern cities
        lat > 30 -> 10.0   // Central
        lat > 25 -> 20.0   // Southern
        else -> 5.0        // Coastal
    }

    private fun mncToOperator(mnc: String): String = when (mnc) {
        "00", "02", "07" -> "中国移动"
        "01", "06" -> "中国联通"
        "03", "05", "11" -> "中国电信"
        "15" -> "中国广电"
        else -> "中国移动"
    }

    // ========== HOOK STATUS ==========

    /**
     * Read hook activation status written by the Xposed module.
     * Returns null if no status file or hooks never activated.
     */
    fun readHookStatus(): HookStatus? {
        return try {
            val f = java.io.File("$CONFIG_DIR/hook_status.json")
            if (!f.exists()) return null
            val raw = f.readText()
            // Minimal JSON parse (avoid Gson for this tiny file)
            val active = raw.contains(""""active":true""")
            val hooks = Regex(""""hooks":(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val total = Regex(""""total":(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 10
            val time = Regex(""""time":(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            HookStatus(active, hooks, total, time)
        } catch (_: Exception) { null }
    }

    data class HookStatus(
        val active: Boolean,
        val hooksLoaded: Int,
        val totalHooks: Int,
        val timestamp: Long
    ) {
        fun isRecent(): Boolean = active && (System.currentTimeMillis() - timestamp) < 120_000 // 2 min
    }
}
