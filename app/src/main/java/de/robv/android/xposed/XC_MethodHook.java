package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Stub — replaced by LSPosed at runtime */
public class XC_MethodHook {
    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private boolean returnEarly;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.returnEarly = true; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
