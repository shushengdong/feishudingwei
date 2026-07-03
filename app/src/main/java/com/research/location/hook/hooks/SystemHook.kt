package com.research.location.hook.hooks

import android.os.Build
import android.provider.Settings
import android.os.Debug

/**
 * Priority 8: Hook system settings APIs to hide dev options, ADB, mock location.
 */
class SystemHook : BaseHook("System", priority = 8) {

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        // Hook Settings.Secure.getString for "mock_location"
        if (config.system.hideMockLocationApp) {
            try {
                hookAfter(
                    Settings.Secure::class.java, "getString",
                    android.content.ContentResolver::class.java, String::class.java
                ) { param ->
                    val key = param.args[1] as? String ?: return@hookAfter
                    if (key == "mock_location") {
                        param.result = null  // No app selected
                    }
                }
            } catch (_: Exception) {}
        }

        // Hook Settings.Secure.getInt for mock_location
        if (config.system.hideMockLocationApp) {
            try {
                hookAfter(
                    Settings.Secure::class.java, "getInt",
                    android.content.ContentResolver::class.java, String::class.java,
                    Int::class.javaPrimitiveType!!
                ) { param ->
                    val key = param.args[1] as? String ?: return@hookAfter
                    if (key == "mock_location") {
                        param.result = 0
                    }
                }
            } catch (_: Exception) {}
        }

        // Hook Settings.Global.getInt for development_settings_enabled
        if (config.system.hideDeveloperOptions) {
            try {
                hookAfter(
                    Settings.Global::class.java, "getInt",
                    android.content.ContentResolver::class.java, String::class.java,
                    Int::class.javaPrimitiveType!!
                ) { param ->
                    val key = param.args[1] as? String ?: return@hookAfter
                    when (key) {
                        "development_settings_enabled" -> param.result = 0
                        "adb_enabled" -> param.result = 0
                    }
                }
            } catch (_: Exception) {}
        }

        // Hook Settings.Global.getString for debug_app
        try {
            hookAfter(
                Settings.Global::class.java, "getString",
                android.content.ContentResolver::class.java, String::class.java
            ) { param ->
                val key = param.args[1] as? String ?: return@hookAfter
                if (key == "debug_app") {
                    param.result = null
                }
            }
        } catch (_: Exception) {}

        // Hook Debug.isDebuggerConnected()
        try {
            hookAfter(Debug::class.java, "isDebuggerConnected") { param ->
                param.result = false
            }
        } catch (_: Exception) {}

        // Hook Build.TAGS (via SystemProperties reflection)
        if (config.system.fakeBuildTags) {
            try {
                val sysPropClass = Class.forName("android.os.SystemProperties")
                hookAfter(
                    sysPropClass, "get",
                    String::class.java, String::class.java
                ) { param ->
                    val key = param.args[0] as? String ?: return@hookAfter
                    when (key) {
                        "ro.build.tags" -> {
                            val current = param.result as? String ?: "release-keys"
                            if (current != "release-keys") param.result = "release-keys"
                        }
                        "ro.build.type" -> {
                            val current = param.result as? String ?: "user"
                            if (current != "user") param.result = "user"
                        }
                        "ro.debuggable" -> param.result = "0"
                        "ro.secure" -> param.result = "1"
                    }
                }
            } catch (_: Exception) {}
        }

        // Hook Build.TAGS static field (read by target app)
        if (config.system.fakeBuildTags) {
            try {
                hookAfter(
                    Build::class.java, "getSerial"
                ) { _ -> /* keep real serial */ }

                // Set static field directly
                try {
                    val tagsField = Build::class.java.getDeclaredField("TAGS")
                    tagsField.isAccessible = true
                    val modifiers = java.lang.reflect.Field::class.java
                        .getDeclaredField("modifiers")
                    modifiers.isAccessible = true
                    modifiers.set(tagsField, tagsField.modifiers and
                            java.lang.reflect.Modifier.FINAL.inv())
                    tagsField.set(null, "release-keys")
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }

        log("Installed — System settings hiding active")
    }
}
