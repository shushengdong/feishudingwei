package com.research.location.hook

import com.research.location.hook.hooks.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed module entry point.
 *
 * Loaded by LSPosed when the target app process starts.
 * Only activates for packages listed in config.targetPackages.
 *
 * Extensibility: Add new hooks by registering them in hookRegistry.
 * Per-package enable/disable is handled per hook module.
 */
class HookEntry : IXposedHookLoadPackage {

    /**
     * Registry of all hook modules.
     * New hooks are added here — no other code changes needed.
     */
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
        // Load config from shared file
        val config = ConfigLoader.load() ?: return

        // Only activate for configured target packages
        if (lpp.packageName !in config.targetPackages) return

        XposedBridge.log("[LocationMod] Activating for ${lpp.packageName}")

        try {
            val resolved = ConfigLoader.resolveForPackage(config, lpp.packageName)

            // Initialize CoordinatesEngine (single source of truth)
            val engine = CoordinatesEngine.initialize(resolved)

            // Install all hooks in priority order
            for (hook in hookRegistry) {
                try {
                    hook.install(lpp, engine, resolved)
                } catch (e: Exception) {
                    XposedBridge.log("[LocationMod] Hook ${hook.name} failed: ${e.message}")
                }
            }

            XposedBridge.log("[LocationMod] All hooks installed for ${lpp.packageName}")

        } catch (e: Exception) {
            XposedBridge.log("[LocationMod] Fatal: ${e.message}")
        }
    }
}
