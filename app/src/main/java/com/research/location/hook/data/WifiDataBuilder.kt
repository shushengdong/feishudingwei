package com.research.location.hook.data

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import java.net.InetAddress

/**
 * Factory for constructing fake WifiInfo and ScanResult objects.
 * Used by WifiHook to return fake WiFi data to the target app.
 */
object WifiDataBuilder {

    // OUI prefixes for common router brands in Chinese cities
    private val OUI_MAP = mapOf(
        "huawei" to listOf("00:1E:10", "00:25:9E", "48:5D:36", "A4:DC:BE"),
        "tplink" to listOf("14:CF:92", "50:C7:BF", "B0:95:8E"),
        "xiaomi" to listOf("8C:53:C3", "28:6C:07", "64:09:80"),
        "tenda" to listOf("C8:3A:35", "00:B0:0C"),
        "other" to listOf("38:2C:4A", "84:1B:5E", "50:2B:73", "00:1A:70")
    )

    // Common SSID patterns
    private val SSID_TEMPLATES = listOf(
        "TP-LINK_%04X", "CMCC-%04X", "ChinaNet-%04X",
        "WiFi-%04X", "NETGEAR%02X", "Xiaomi_%04X",
        "HUAWEI-%04X", "Office-%dG", "Guest-%04X",
        "Tencent-WiFi", "ByteDance-Office", "Alibaba-Guest"
    )

    // Capabilities for scan results
    private val CAPABILITIES = listOf(
        "[WPA2-PSK-CCMP][ESS]",
        "[WPA-PSK-CCMP][ESS]",
        "[WPA2-PSK-CCMP][WPS][ESS]",
        "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"
    )

    /**
     * Build a single ScanResult.
     */
    fun buildScanResult(
        ssid: String,
        bssid: String,
        rssi: Int,
        frequency: Int,
        seed: Long
    ): ScanResult {
        val rng = kotlin.random.Random(seed)
        return ScanResult::class.java.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance().apply {
            SSID = ssid
            BSSID = bssid
            level = rssi
            this.frequency = frequency
            capabilities = CAPABILITIES[rng.nextInt(CAPABILITIES.size)]
            timestamp = System.currentTimeMillis() * 1000L  // microseconds
        }
    }

    /**
     * Build a list of ScanResults for a target GPS location.
     */
    fun buildScanResults(
        lat: Double,
        lng: Double,
        apCount: Int,
        primarySsid: String,
        primaryBssid: String,
        rssiRange: IntRange,
        channels: List<Int>,
        ouiDistribution: Map<String, Double>,
        seed: Long
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val rng = kotlin.random.Random(seed)

        // Primary AP (strong signal)
        val primaryRssi = rssiRange.last - rng.nextInt(15)
        val primaryCh = channels.firstOrNull() ?: 6
        results.add(buildScanResult(
            ssid = primarySsid,
            bssid = primaryBssid.ifEmpty { generateBssid(lat, lng, 0, ouiDistribution, rng.nextLong()) },
            rssi = primaryRssi,
            frequency = if (primaryCh <= 14) 2412 + (primaryCh - 1) * 5 else 5000 + primaryCh * 5,
            seed = seed xor 1
        ))

        // Secondary APs with decreasing signal strength
        for (i in 1 until apCount) {
            val ssid = SSID_TEMPLATES[rng.nextInt(SSID_TEMPLATES.size)]
                .let { template ->
                    if (template.contains("%")) {
                        template.format(rng.nextInt(9000) + 1000)
                    } else template
                }
            val bssid = generateBssid(lat, lng, i, ouiDistribution, rng.nextLong())
            val rssi = primaryRssi - 10 - rng.nextInt(50)  // weaker signal
            val ch = channels[rng.nextInt(channels.size)]
            val freq = if (ch <= 14) 2412 + (ch - 1) * 5 else 5000 + ch * 5

            results.add(buildScanResult(ssid, bssid, rssi, freq, seed xor (i + 1).toLong()))
        }

        return results
    }

    /**
     * Generate deterministic BSSID from location + index.
     */
    fun generateBssid(
        lat: Double, lng: Double, idx: Int,
        ouiDistribution: Map<String, Double>,
        seed: Long
    ): String {
        val rng = kotlin.random.Random(seed)

        // Pick OUI based on distribution
        val roll = rng.nextDouble()
        var cumulative = 0.0
        var selectedBrand = "other"
        for ((brand, prob) in ouiDistribution) {
            cumulative += prob
            if (roll <= cumulative) { selectedBrand = brand; break }
        }

        val ouis = OUI_MAP[selectedBrand] ?: OUI_MAP["other"]!!
        val oui = ouis[rng.nextInt(ouis.size)]

        // Generate NIC (last 3 bytes) deterministically from location
        val hash = ((lat * 1_000_000).toLong() xor (lng * 1_000_000).toLong() + idx * 0xABCDEF)
        val nic = (0..2).map { i ->
            val b = ((hash shr (i * 8)) and 0xFF).toInt()
            String.format("%02X", b)
        }.joinToString(":")

        return "$oui:$nic"
    }

    /**
     * Build a fake WifiInfo for getConnectionInfo().
     */
    fun buildWifiInfo(
        ssid: String,
        bssid: String,
        rssi: Int,
        ipAddress: String = "192.168.1.105",
        linkSpeed: Int = 144  // Mbps
    ): WifiInfo {
        return WifiInfo::class.java.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance().apply {
            try {
                // Use reflection to set fields since WifiInfo setters are @hide or @SystemApi
                val ssidField = WifiInfo::class.java.getDeclaredField("mSSID")
                ssidField.isAccessible = true
                ssidField.set(this, "\"$ssid\"")

                val bssidField = WifiInfo::class.java.getDeclaredField("mBSSID")
                bssidField.isAccessible = true
                bssidField.set(this, bssid)

                val rssiField = WifiInfo::class.java.getDeclaredField("mRssi")
                rssiField.isAccessible = true
                rssiField.set(this, rssi)

                val ipField = WifiInfo::class.java.getDeclaredField("mIpAddress")
                ipField.isAccessible = true
                ipField.set(this, InetAddress.getByName(ipAddress).let {
                    // Convert to int (network byte order)
                    it.address.let { bytes ->
                        ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                    }
                })

                val speedField = WifiInfo::class.java.getDeclaredField("mLinkSpeed")
                speedField.isAccessible = true
                speedField.set(this, linkSpeed)
            } catch (_: Exception) {
                // best effort
            }
        }
    }
}
