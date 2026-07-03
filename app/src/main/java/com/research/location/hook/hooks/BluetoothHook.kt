package com.research.location.hook.hooks

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback

/**
 * Hook Bluetooth to block real BLE scans.
 *
 * Real BLE scans could leak the device's actual location
 * (beacon fingerprints sent to AMap/Gaode servers).
 * Blocking scans is safer than injecting fake beacons
 * (which requires constructing hidden API objects).
 *
 * Most apps gracefully degrade to WiFi+Cell+GPS when BLE is unavailable.
 */
class BluetoothHook : BaseHook("Bluetooth", priority = 12) {

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        // Hook-1: BluetoothLeScanner.startScan(ScanCallback) → swallow
        try {
            hookBefore(
                BluetoothLeScanner::class.java, "startScan",
                ScanCallback::class.java
            ) { _ ->
                // Block real scan completely
                // Apps will receive no results (which is fine for location spoofing)
            }
        } catch (_: Exception) {
            log("BLE scan hook skipped (API unavailable)")
        }

        // Hook-2: BluetoothLeScanner.startScan(List<ScanFilter>, ScanSettings, ScanCallback)
        try {
            hookBefore(
                BluetoothLeScanner::class.java, "startScan",
                java.util.List::class.java,
                android.bluetooth.le.ScanSettings::class.java,
                ScanCallback::class.java
            ) { _ -> /* block */ }
        } catch (_: Exception) {}

        // Hook-3: BluetoothLeScanner.startScan(List<ScanFilter>, ScanSettings, PendingIntent)
        try {
            hookBefore(
                BluetoothLeScanner::class.java, "startScan",
                java.util.List::class.java,
                android.bluetooth.le.ScanSettings::class.java,
                android.app.PendingIntent::class.java
            ) { _ -> /* block */ }
        } catch (_: Exception) {}

        log("Installed — BLE scan blocking active")
    }
}
