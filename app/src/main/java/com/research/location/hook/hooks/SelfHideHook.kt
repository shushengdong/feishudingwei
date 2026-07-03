package com.research.location.hook.hooks

import android.content.pm.PackageManager
import java.io.File

/**
 * Priority 9: Hook PackageManager, Runtime.exec, File APIs to hide
 * root/Magisk/Xposed/our-app from the target app's detection.
 */
class SelfHideHook : BaseHook("SelfHide", priority = 9) {

    private var hiddenPkgs: List<String> = emptyList()

    // File paths that should appear as non-existent
    private val hiddenPaths = setOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/data/com.topjohnwu.magisk",
        "/system/framework/XposedBridge.jar",
        "/data/data/de.robv.android.xposed.installer",
        "/data/data/org.lsposed.manager"
    )

    // Commands that indicate root detection
    private val rootCommands = listOf(
        "su", "magisk", "xposed", "supersu", "daemonsu"
    )

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        // Load hidden package list from global config
        // (we access the full config via a workaround)
        hiddenPkgs = listOf(
            "com.research.location",
            "org.lsposed.manager",
            "com.topjohnwu.magisk",
            "de.robv.android.xposed.installer"
        )

        // === PackageManager Hooks ===

        if (config.selfHide.hideAppFromPackageList) {
            installPackageManagerHooks()
        }

        // === File system Hooks ===

        if (config.selfHide.hideRootFiles) {
            installFileHooks()
        }

        // === Runtime.exec Hooks ===

        if (config.selfHide.interceptRootCommands) {
            installRuntimeHooks()
        }

        // === ClassLoader Hooks ===

        installClassLoaderHooks()

        log("Installed — Self-hiding active ($hiddenPkgs)")
    }

    private fun installPackageManagerHooks() {
        // getInstalledApplications
        hookAfter(
            PackageManager::class.java, "getInstalledApplications",
            Int::class.javaPrimitiveType!!
        ) { param ->
            filterPackageList(param)
        }

        // getInstalledPackages
        hookAfter(
            PackageManager::class.java, "getInstalledPackages",
            Int::class.javaPrimitiveType!!
        ) { param ->
            filterPackageList(param)
        }

        // getPackageInfo(String, int)
        hookAfter(
            PackageManager::class.java, "getPackageInfo",
            String::class.java, Int::class.javaPrimitiveType!!
        ) { param ->
            val pkg = param.args[0] as? String ?: return@hookAfter
            if (pkg in hiddenPkgs) {
                throw PackageManager.NameNotFoundException("Package $pkg not found")
            }
        }

        // getApplicationInfo(String, int)
        hookAfter(
            PackageManager::class.java, "getApplicationInfo",
            String::class.java, Int::class.javaPrimitiveType!!
        ) { param ->
            val pkg = param.args[0] as? String ?: return@hookAfter
            if (pkg in hiddenPkgs) {
                throw PackageManager.NameNotFoundException("App $pkg not found")
            }
        }
    }

    private fun filterPackageList(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        val list = param.result as? MutableList<*> ?: return
        try {
            val filtered = list.filter { item ->
                try {
                    val pkgField = item!!::class.java.getDeclaredField("packageName")
                    pkgField.isAccessible = true
                    val pkg = pkgField.get(item) as? String ?: return@filter true
                    pkg !in hiddenPkgs
                } catch (_: Exception) { true }
            }
            // Replace list content
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Any?>).clear()
            (list as MutableList<Any?>).addAll(filtered)
        } catch (_: Exception) {}
    }

    private fun installFileHooks() {
        // Hook File.exists()
        hookAfter(
            File::class.java, "exists"
        ) { param ->
            val file = param.thisObject as File
            val path = file.absolutePath
            val result = param.result as? Boolean ?: return@hookAfter
            if (result == true && path in hiddenPaths) {
                param.result = false
            }
            // Also check if path contains hidden patterns
            if (result == true) {
                for (hidden in hiddenPaths) {
                    if (path.contains(hidden)) {
                        param.result = false
                        return@hookAfter
                    }
                }
            }
        }

        // Hook File.listFiles() — filter hidden files from listing
        try {
            hookAfter(File::class.java, "listFiles") { param ->
                val files = param.result as? Array<File> ?: return@hookAfter
                val filtered = files.filterNot { f -> f.absolutePath in hiddenPaths }
                if (filtered.size != files.size) {
                    param.result = filtered.toTypedArray()
                }
            }
        } catch (_: Exception) {}
    }

    private fun installRuntimeHooks() {
        // Hook Runtime.exec(String)
        try {
            hookBefore(
                Runtime::class.java, "exec",
                String::class.java
            ) { param ->
                val cmd = param.args[0] as? String ?: return@hookBefore
                if (isRootDetectionCommand(cmd)) {
                    log("Blocked exec: ${cmd.take(80)}")
                    // Replace with a command that returns empty
                    param.args[0] = "echo"
                }
            }
        } catch (_: Exception) {}

        // Hook Runtime.exec(String[])
        try {
            hookBefore(
                Runtime::class.java, "exec",
                Array<String>::class.java
            ) { param ->
                val cmd = (param.args[0] as? Array<String>)?.joinToString(" ")
                    ?: return@hookBefore
                if (isRootDetectionCommand(cmd)) {
                    log("Blocked exec[]: ${cmd.take(80)}")
                    param.args[0] = arrayOf("echo")
                }
            }
        } catch (_: Exception) {}

        // Hook ProcessBuilder.start()
        try {
            hookBefore(
                ProcessBuilder::class.java, "start"
            ) { param ->
                val pb = param.thisObject as ProcessBuilder
                val cmd = pb.command().joinToString(" ")
                if (isRootDetectionCommand(cmd)) {
                    log("Blocked ProcessBuilder: ${cmd.take(80)}")
                    pb.command(listOf("echo"))
                }
            }
        } catch (_: Exception) {}
    }

    private fun installClassLoaderHooks() {
        // Hook ClassLoader.loadClass to prevent loading Xposed classes
        try {
            hookBefore(
                ClassLoader::class.java, "loadClass",
                String::class.java
            ) { param ->
                val className = param.args[0] as? String ?: return@hookBefore
                if (className.startsWith("de.robv.android.xposed.")) {
                    throw ClassNotFoundException(className)
                }
            }
        } catch (_: Exception) {}
    }

    private fun isRootDetectionCommand(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return rootCommands.any { lower.contains(it) } &&
                (lower.contains("which") ||
                 lower.contains("ls ") ||
                 lower.contains("cat ") ||
                 lower.contains("grep") ||
                 lower.contains("find"))
    }
}
