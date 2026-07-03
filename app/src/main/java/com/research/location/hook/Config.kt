package com.research.location.hook

/**
 * Complete Xposed module configuration.
 *
 * Extensibility: new per-app parameters are added to AppOverride,
 * new hook modules add their own config section.
 */
data class MockConfig(
    val version: Int = 3,
    val enabled: Boolean = true,
    /** Packages to inject into (e.g. ["com.ss.android.lark"]) */
    val targetPackages: List<String> = emptyList(),
    /** Per-package fine-grained control (optional) */
    val packageOverrides: Map<String, PackageOverride> = emptyMap(),
    val location: LocationConfig = LocationConfig(),
    val behavior: BehaviorConfig = BehaviorConfig(),
    val wifi: WifiConfig = WifiConfig(),
    val cell: CellConfig = CellConfig(),
    val gnss: GnssConfig = GnssConfig(),
    val sensor: SensorConfig = SensorConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val system: SystemConfig = SystemConfig(),
    val selfHide: SelfHideConfig = SelfHideConfig()
)

/**
 * Per-package override for any config section.
 * Any non-null field overrides the global default.
 */
data class PackageOverride(
    val location: LocationConfig? = null,
    val behavior: BehaviorConfig? = null,
    val wifi: WifiConfig? = null,
    val cell: CellConfig? = null,
    val gnss: GnssConfig? = null,
    val sensor: SensorConfig? = null
)

data class LocationConfig(
    val lat: Double = 39.9042,
    val lng: Double = 116.4074,
    val label: String = "",
    val city: String = "",
    val altitude: Double = 50.0
)

data class BehaviorConfig(
    val updateIntervalMs: Long = 2000,
    val mode: String = "walking",     // "static" | "walking" | "transition"
    val gpsJitterMeters: Double = 15.0,
    val speedRangeMs: List<Double> = listOf(0.0, 1.5),
    val accuracyRangeM: List<Double> = listOf(3.0, 25.0),
    val transitionEnabled: Boolean = true,
    val transitionDurationMs: Long = 600_000
)

data class WifiConfig(
    val enabled: Boolean = true,
    val primarySsid: String = "Office-WiFi-5G",
    val primaryBssid: String = "",
    val apCount: Int = 6,
    val rssiRange: List<Int> = listOf(-85, -30),
    val channels: List<Int> = listOf(1, 6, 11, 36, 149),
    val ouiDistribution: Map<String, Double> = mapOf(
        "huawei" to 0.30,
        "tplink" to 0.25,
        "xiaomi" to 0.20,
        "tenda" to 0.15,
        "other" to 0.10
    )
)

data class CellConfig(
    val enabled: Boolean = true,
    val mcc: String = "460",
    val mnc: String = "00",
    val operatorName: String = "中国移动",
    val networkType: String = "LTE",
    val lac: Int = 43012,
    val neighborCount: Int = 4,
    val rssiRange: List<Int> = listOf(-100, -65),
    val density: String = "urban"     // "urban" | "suburban" | "rural"
)

data class GnssConfig(
    val enabled: Boolean = true,
    val satelliteCount: List<Int> = listOf(8, 15),
    val constellationMix: Map<String, Int> = mapOf(
        "GPS" to 35, "BeiDou" to 35, "GLONASS" to 20, "Galileo" to 10
    ),
    val cn0Range: List<Double> = listOf(25.0, 45.0),
    val updateIntervalMs: Long = 1000
)

data class SensorConfig(
    val enabled: Boolean = true,
    val accelerometer: Boolean = true,
    val gyroscope: Boolean = true,
    val magnetometer: Boolean = true,
    val barometer: Boolean = true,
    val gravity: Boolean = true,
    val linearAcceleration: Boolean = true,
    val rotationVector: Boolean = true,
    val light: Boolean = false,
    val proximity: Boolean = false
)

data class NetworkConfig(
    val hideVpn: Boolean = true,
    val fakeWifiConnection: Boolean = true
)

data class SystemConfig(
    val hideDeveloperOptions: Boolean = true,
    val hideAdbEnabled: Boolean = true,
    val hideMockLocationApp: Boolean = true,
    val fakeBuildTags: Boolean = true
)

data class SelfHideConfig(
    val hideAppFromPackageList: Boolean = true,
    val hideRootFiles: Boolean = true,
    val interceptRootCommands: Boolean = true,
    val hiddenPackageNames: List<String> = listOf(
        "com.research.location",
        "org.lsposed.manager",
        "com.topjohnwu.magisk",
        "de.robv.android.xposed.installer"
    )
)
