package com.research.location.hook

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Loads and hot-reloads configuration from /sdcard/location_mod/config.json.
 * Runs inside the Xposed module (target app process).
 */
object ConfigLoader {
    private const val CONFIG_PATH = "/sdcard/location_mod/config.json"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var cachedConfig: MockConfig? = null

    @Volatile
    private var lastModified: Long = 0

    /**
     * Load config from disk. Returns null if not found or disabled.
     * Caches result and only re-reads if file mtime changed.
     */
    fun load(): MockConfig? {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return null

        val currentModified = file.lastModified()
        if (cachedConfig != null && currentModified == lastModified) {
            return cachedConfig
        }

        return try {
            val json = file.readText()
            val config = gson.fromJson(json, MockConfig::class.java)
            if (!config.enabled) return null
            cachedConfig = config
            lastModified = currentModified
            config
        } catch (e: Exception) {
            // Corrupt config, keep previous cache if any
            android.util.Log.e("LocationMod", "Config parse error: ${e.message}")
            cachedConfig
        }
    }

    /**
     * Force reload (e.g., after config write from management app).
     */
    fun forceReload(): MockConfig? {
        cachedConfig = null
        lastModified = 0
        return load()
    }

    /**
     * Resolve effective config for a specific package.
     * Merges global config with package override.
     */
    fun resolveForPackage(config: MockConfig, pkgName: String): ResolvedConfig {
        val override = config.packageOverrides[pkgName]
        return ResolvedConfig(
            location = override?.location ?: config.location,
            behavior = override?.behavior ?: config.behavior,
            wifi = override?.wifi ?: config.wifi,
            cell = override?.cell ?: config.cell,
            gnss = override?.gnss ?: config.gnss,
            sensor = override?.sensor ?: config.sensor
        )
    }

    data class ResolvedConfig(
        val location: LocationConfig,
        val behavior: BehaviorConfig,
        val wifi: WifiConfig,
        val cell: CellConfig,
        val gnss: GnssConfig,
        val sensor: SensorConfig
    )
}
