package de.robv.android.xposed.callbacks

/** Stub — replaced by LSPosed at runtime */
open class XC_LoadPackage {
    open class LoadPackageParam {
        @JvmField var packageName: String = ""
        @JvmField var classLoader: ClassLoader? = null
        @JvmField var isFirstApplication: Boolean = false
    }
}
