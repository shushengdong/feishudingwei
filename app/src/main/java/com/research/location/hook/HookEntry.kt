package com.research.location.hook

import com.research.location.hook.hooks.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    private val hookRegistry: List<BaseHook> = listOf(
        MockFlagHook(),
        LocationHook(),
        WifiHook(),
        CellHook(),
        GnssHook(),
        SensorHook(),
        GeocoderHook(),
        NetworkHook(),
        SystemHook(),
        SelfHideHook()
    ).sortedBy { it.priority }

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        val config = ConfigLoader.load() ?: return
        if (lpp.packageName !in config.targetPackages) return

        XposedBridge.log("[LocationMod] Activating for ${lpp.packageName}")

        try {
            val resolved = ConfigLoader.resolveForPackage(config, lpp.packageName)
            val engine = CoordinatesEngine.initialize(resolved)

            var successCount = 0
            for (hook in hookRegistry) {
                try {
                    hook.install(lpp, engine, resolved)
                    successCount++
                } catch (e: Exception) {
                    XposedBridge.log("[LocationMod] Hook ${hook.name} failed: ${e.message}")
                }
            }

            XposedBridge.log("[LocationMod] $successCount/${hookRegistry.size} hooks installed")
            writeHookStatus(lpp.packageName, successCount, hookRegistry.size)

        } catch (e: Exception) {
            XposedBridge.log("[LocationMod] Fatal: ${e.message}")
        }
    }

    private fun writeHookStatus(pkg: String, active: Int, total: Int) {
        try {
            val dir = java.io.File("/sdcard/location_mod")
            dir.mkdirs()
            val f = java.io.File(dir, "hook_status.json")
            f.writeText("""{"active":true,"package":"$pkg","hooks":$active,"total":$total,"time":${System.currentTimeMillis()}}""")
        } catch (_: Exception) {}
    }
}
