package de.robv.android.xposed

import java.lang.reflect.Member

/** Stub — replaced by LSPosed at runtime */
open class XC_MethodHook {

    open class MethodHookParam {
        @JvmField var method: Member? = null
        @JvmField var thisObject: Any? = null
        @JvmField var args: Array<Any>? = null
        private var result: Any? = null
        private var returnEarly = false

        fun getResult(): Any? = result
        fun setResult(result: Any?) { this.result = result; returnEarly = true }
    }

    @Throws(Throwable::class)
    protected open fun beforeHookedMethod(param: MethodHookParam) {}

    @Throws(Throwable::class)
    protected open fun afterHookedMethod(param: MethodHookParam) {}
}
