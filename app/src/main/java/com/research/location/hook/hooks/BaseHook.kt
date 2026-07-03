package com.research.location.hook.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Abstract base for all hook modules.
 *
 * Extensibility: New hook modules extend this class and register themselves
 * in HookEntry.hookRegistry. Each module can be independently enabled/disabled
 * per target package.
 */
abstract class BaseHook(
    val name: String,
    val priority: Int = 50  // lower = registered first
) {
    protected var config: com.research.location.hook.ConfigLoader.ResolvedConfig? = null
    protected var lpp: XC_LoadPackage.LoadPackageParam? = null
    protected var engine: com.research.location.hook.CoordinatesEngine? = null

    /**
     * Install all hooks for this module.
     * Called once when target app process starts.
     */
    open fun install(
        lpp: XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        this.lpp = lpp
        this.engine = engine
        this.config = config
    }

    /** Convenience: find and hook a method */
    protected fun hook(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any,
        callback: XC_MethodHook
    ) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypes, callback)
        } catch (e: Exception) {
            log("Failed to hook $methodName: ${e.message}")
        }
    }

    /** Convenience: find and hook a method with before/after lambdas */
    protected fun hookBefore(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any,
        before: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        hook(clazz, methodName, *parameterTypes, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = before(param)
        })
    }

    protected fun hookAfter(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any,
        after: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        hook(clazz, methodName, *parameterTypes, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = after(param)
        })
    }

    /** Convenience: completely replace a method */
    protected fun hookReplace(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any,
        replacement: (XC_MethodHook.MethodHookParam) -> Any?
    ) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypes,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = replacement(param)
                    }
                })
        } catch (e: Exception) {
            log("Failed to replace $methodName: ${e.message}")
        }
    }

    /** Convenience: find class by name */
    protected fun findClass(className: String): Class<*>? {
        return try {
            XposedHelpers.findClass(className, lpp?.classLoader)
        } catch (_: Exception) { null }
    }

    protected fun log(msg: String) {
        XposedBridge.log("[LocationMod][$name] $msg")
    }
}
