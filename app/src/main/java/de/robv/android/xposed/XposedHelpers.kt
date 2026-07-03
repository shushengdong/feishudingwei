package de.robv.android.xposed

import java.lang.reflect.Member

/** Stub — replaced by LSPosed at runtime */
object XposedHelpers {
    @JvmStatic
    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg parameterTypesAndCallback: Any): Member? = null

    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader): Class<*> =
        Class.forName(className, false, classLoader)
}
