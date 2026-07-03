package de.robv.android.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Stub — replaced by LSPosed at runtime */
interface IXposedHookLoadPackage {
    @Throws(Throwable::class)
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam)
}
