package com.research.location.hook.hooks

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Hook Bluetooth to simulate nearby BLE beacons consistent with mock location.
 *
 * High-precision location SDKs (including AMap/Gaode) use BLE beacon
 * fingerprints as an additional location data source. If Feishu sees
 * 0 BLE beacons in a dense urban area → suspicious.
 *
 * This hook:
 * - Blocks real BLE scans (privacy + prevents real location leak)
 * - Returns fake beacon list matching GPS city
 * - Simulates common beacon types (iBeacon, Eddystone, AltBeacon)
 */
class BluetoothHook : BaseHook("Bluetooth", priority = 12) {

    // Common beacon UUIDs found in Chinese cities
    private val beaconUuids = listOf(
        UUID.fromString("FDA50693-A4E2-4FB1-AFCF-C6EB07647825"), // iBeacon
        UUID.fromString("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"), // Eddystone
        UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"), // Eddystone service
        UUID.fromString("0000FEAB-0000-1000-8000-00805F9B34FB"), // AltBeacon
        UUID.fromString("01122334-4556-6778-899A-ABBCCDDEEFF0"), // Generic proximity
    )

    // Common beacon MAC prefixes in China
    private val macPrefixes = listOf(
        "00:1A:7D", "00:1B:10", "00:1E:4C", // Nordic-based beacons
        "AC:23:3F", "B8:27:EB", "DC:0D:30", // TI-based beacons
        "F0:C7:7F", "F8:1E:DF", "FC:D8:48", // Dialog-based beacons
    )

    private data class BeaconEntry(
        val mac: String,
        val uuid: UUID,
        val major: Int,
        val minor: Int,
        val rssi: Int,
        val txPower: Int
    )

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        // Hook-1: BluetoothLeScanner.startScan(ScanCallback)
        try {
            hookBefore(
                BluetoothLeScanner::class.java, "startScan",
                ScanCallback::class.java
            ) { param ->
                val callback = param.args[0] as? ScanCallback ?: return@hookBefore
                val e = engine ?: return@hookBefore

                // Inject fake scan results immediately
                Thread {
                    val results = buildFakeBeacons(e.currentFrame.frameSeed)
                    try {
                        // Simulate staggered discovery (real scans discover beacons gradually)
                        for (i in results.indices) {
                            Thread.sleep((200 + kotlin.random.Random.nextLong(300)).coerceAtMost(500))
                            callback.onScanResult(ScanSettingsCompat.CALLBACK_TYPE_ALL_MATCHES, results[i])
                        }
                        // Also invoke onBatchScanResults for batch mode
                        callback.onBatchScanResults(results)
                    } catch (_: Exception) {}
                }.start()

                // Block real scan
                param.result = null
            }
        } catch (_: Exception) {
            log("BLE scan hook skipped (API not available)")
        }

        // Hook-2: BluetoothAdapter.isEnabled() → return true
        try {
            hookAfter(
                BluetoothAdapter::class.java, "isEnabled"
            ) { param -> param.result = true }
        } catch (_: Exception) {}

        // Hook-3: BluetoothManager.getAdapter() → return non-null
        try {
            hookAfter(
                BluetoothManager::class.java, "getAdapter"
            ) { param ->
                if (param.result == null) {
                    // Return real adapter if available, otherwise don't modify
                }
            }
        } catch (_: Exception) {}

        log("Installed — Bluetooth beacon spoofing active")
    }

    private fun buildFakeBeacons(seed: Long): List<ScanResult> {
        val rng = kotlin.random.Random(seed)
        val beaconCount = 3 + rng.nextInt(6) // 3-8 beacons (urban density)

        return (0 until beaconCount).map { i ->
            buildSingleBeacon(seed xor (i + 1).toLong(), i)
        }
    }

    private fun buildSingleBeacon(seed: Long, idx: Int): ScanResult {
        val rng = kotlin.random.Random(seed)
        val mac = macPrefixes[rng.nextInt(macPrefixes.size)] +
                String.format(":%02X:%02X:%02X", rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
        val uuid = beaconUuids[rng.nextInt(beaconUuids.size)]
        val major = 1000 + rng.nextInt(9000)
        val minor = rng.nextInt(5000)
        val txPower = -65 - rng.nextInt(10)  // -65 to -75 dBm
        val rssi = txPower - 5 - rng.nextInt(25)  // slightly attenuated

        // Build iBeacon-style advertisement data
        val advData = buildIBeaconPacket(uuid, major, minor, txPower.toByte())

        return ScanResult(
            BluetoothDevice::class.java.getDeclaredConstructor(
                String::class.java
            ).apply { isAccessible = true }.newInstance(mac),
            ScanRecord::class.java.getDeclaredConstructor(
                ByteArray::class.java
            ).apply { isAccessible = true }.newInstance(advData),
            rssi,
            System.nanoTime()
        )
    }

    /** Build iBeacon advertisement packet */
    private fun buildIBeaconPacket(uuid: UUID, major: Int, minor: Int, txPower: Byte): ByteArray {
        val data = ByteArray(30)
        var pos = 0
        // Flags
        data[pos++] = 0x02  // length
        data[pos++] = 0x01  // AD type: Flags
        data[pos++] = 0x06  // LE General Discoverable + BR/EDR Not Supported
        // Manufacturer data: Apple iBeacon
        data[pos++] = 0x1A  // length (26 bytes)
        data[pos++] = 0xFF.toByte()  // AD type: Manufacturer Specific
        data[pos++] = 0x4C  // Apple company ID LSB
        data[pos++] = 0x00  // Apple company ID MSB
        data[pos++] = 0x02  // iBeacon type
        data[pos++] = 0x15  // iBeacon length
        // UUID (16 bytes)
        val uuidBytes = uuid.toBytes()
        for (b in uuidBytes) data[pos++] = b
        // Major (2 bytes big-endian)
        data[pos++] = ((major shr 8) and 0xFF).toByte()
        data[pos++] = (major and 0xFF).toByte()
        // Minor (2 bytes big-endian)
        data[pos++] = ((minor shr 8) and 0xFF).toByte()
        data[pos++] = (minor and 0xFF).toByte()
        // TX power
        data[pos++] = txPower
        return data
    }

    /** Stub class for ScanSettings callback type constant */
    private object ScanSettingsCompat {
        const val CALLBACK_TYPE_ALL_MATCHES = 1
    }

    /** Extension to get bytes from UUID */
    private fun UUID.toBytes(): ByteArray {
        val buf = ByteArray(16)
        val msb = mostSignificantBits
        val lsb = leastSignificantBits
        for (i in 0..7) buf[i] = ((msb shr (8 * (7 - i))) and 0xFF).toByte()
        for (i in 8..15) buf[i] = ((lsb shr (8 * (7 - i + 8))) and 0xFF).toByte()
        return buf
    }
}
