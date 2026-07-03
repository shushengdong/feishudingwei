package com.research.location.hook.hooks

import android.location.Location

/**
 * Priority 1: Hook Location.isFromMockProvider() → always return false.
 *
 * This is the ultimate safety net. Even if the target app gets a Location
 * object from anywhere (Fused, Passive, other apps), isFromMockProvider()
 * will always return false in the target app's process.
 */
class MockFlagHook : BaseHook("MockFlag", priority = 1) {

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        hookAfter(
            Location::class.java, "isFromMockProvider",
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )

        log("Installed — all Location.isFromMockProvider() → false")
    }
}
