package com.research.location

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Environment diagnostics for the management app.
 * Checks root, LSPosed, config status, and provides health reports.
 */
object DiagnosticsEngine {

    data class DiagReport(
        val rootDetected: Boolean,
        val magiskInstalled: Boolean,
        val lsposedInstalled: Boolean,
        val xposedModuleActive: Boolean,
        val configExists: Boolean,
        val configValid: Boolean,
        val targetAppInstalled: Boolean,
        val developerOptionsEnabled: Boolean?,
        val adbEnabled: Boolean?,
        val suspiciousApps: List<String>,
        val overallScore: Int,  // 0-100
        val riskLevel: RiskLevel,
        val recommendations: List<String>
    )

    enum class RiskLevel { GREEN, YELLOW, RED }

    fun runDiagnostics(ctx: Context, targetPackage: String): DiagReport {
        val pm = ctx.packageManager

        // Check root indicators
        val rootFiles = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        val rootDetected = rootFiles.any { java.io.File(it).exists() }

        // Check Magisk
        val magiskInstalled = java.io.File("/data/adb/magisk.db").exists() ||
                isPackageInstalled(pm, "com.topjohnwu.magisk")

        // Check LSPosed
        val lsposedInstalled = isPackageInstalled(pm, "org.lsposed.manager")

        // Check Xposed module active (via config status)
        val configExists = java.io.File(ConfigWriter.configFilePath()).exists()
        val configValid = ConfigWriter.isConfigValid()

        // Check target app
        val targetAppInstalled = isPackageInstalled(pm, targetPackage)

        // Read developer settings (may fail without permission)
        val devOpts = readDevSettings(ctx)

        // Check suspicious apps
        val suspiciousApps = AntiDetection.detectSuspiciousApps(ctx)

        // Calculate score
        var score = 100
        val recs = mutableListOf<String>()

        if (!magiskInstalled) { score -= 30; recs.add("安装Magisk获取root权限") }
        if (!lsposedInstalled) { score -= 25; recs.add("安装LSPosed框架") }
        if (!configExists) { score -= 20; recs.add("写入配置文件") }
        if (!configValid) { score -= 15; recs.add("检查配置文件是否正确") }
        if (!targetAppInstalled) { score -= 10; recs.add("安装目标App: $targetPackage") }
        if (devOpts.first == true) { score -= 5; recs.add("建议关闭开发者选项") }
        if (devOpts.second == true) { score -= 5; recs.add("建议关闭USB调试") }
        if (suspiciousApps.isNotEmpty()) { score -= 5; recs.add("卸载已知改机工具: ${suspiciousApps.joinToString(", ")}") }

        val riskLevel = when {
            score >= 80 -> RiskLevel.GREEN
            score >= 50 -> RiskLevel.YELLOW
            else -> RiskLevel.RED
        }

        return DiagReport(
            rootDetected = rootDetected,
            magiskInstalled = magiskInstalled,
            lsposedInstalled = lsposedInstalled,
            xposedModuleActive = configValid,
            configExists = configExists,
            configValid = configValid,
            targetAppInstalled = targetAppInstalled,
            developerOptionsEnabled = devOpts.first,
            adbEnabled = devOpts.second,
            suspiciousApps = suspiciousApps,
            overallScore = score.coerceIn(0, 100),
            riskLevel = riskLevel,
            recommendations = recs
        )
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readDevSettings(ctx: Context): Pair<Boolean?, Boolean?> {
        return try {
            val devEnabled = android.provider.Settings.Global.getInt(
                ctx.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
            val adbEnabled = android.provider.Settings.Global.getInt(
                ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0
            ) == 1
            devEnabled to adbEnabled
        } catch (_: Exception) {
            null to null
        }
    }
}
