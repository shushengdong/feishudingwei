package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Stub — replaced by LSPosed at runtime */
public class XposedHelpers {
    public static Member findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try { return Class.forName(className, false, classLoader); }
        catch (ClassNotFoundException e) { return null; }
    }
}
