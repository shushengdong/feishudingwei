package com.research.location

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root preparation engine — fully automated.
 *
 * Detects every stage of the rooting pipeline and provides
 * one-click actions for each step: DevOptions → OEM Unlock →
 * USB Debug → BL Unlock → Magisk → LSPosed → Module Active.
 */
object RootManager {

    // ========== PHASES ==========

    enum class RootPhase {
        NONE,                   // No developer options yet
        DEVELOPER_ENABLED,      // Dev options visible
        OEM_UNLOCK_ALLOWED,     // OEM unlocking toggled on
        BOOTLOADER_UNLOCKED,    // Bootloader unlocked, can flash Magisk
        READY                   // Magisk + LSPosed installed, module ready
    }

    enum class StepStatus { PENDING, IN_PROGRESS, COMPLETED }

    data class RootStep(
        val title: String,
        val description: String,
        val status: StepStatus,
        val actionLabel: String,
        val action: ((Context) -> Unit)?
    )

    data class RootChecks(
        val devOptionsEnabled: Boolean,
        val oemUnlockAllowed: Boolean,
        val usbDebugEnabled: Boolean,
        val xiaomiAccountBound: Boolean,
        val isXiaomi: Boolean,
        val bootloaderUnlocked: Boolean,
        val magiskInstalled: Boolean,
        val magiskAppInstalled: Boolean,
        val lsposedInstalled: Boolean,
        val hasSuBinary: Boolean,
        val moduleActive: Boolean
    )

    data class RootStatus(
        val phase: RootPhase,
        val currentStep: Int,
        val totalSteps: Int,
        val currentStepDescription: String,
        val checks: RootChecks,
        val steps: List<RootStep>
    )

    // ========== MAIN ENTRY ==========

    fun getDeviceRootStatus(ctx: Context): RootStatus {
        val checks = runAllChecks(ctx)
        val phase = determinePhase(checks)
        val steps = buildSteps(ctx, checks, phase)
        val currentStep = steps.indexOfFirst { it.status != StepStatus.COMPLETED }
            .let { if (it == -1) steps.size else it + 1 }

        return RootStatus(
            phase = phase,
            currentStep = currentStep,
            totalSteps = steps.size,
            currentStepDescription = steps.firstOrNull {
                it.status != StepStatus.COMPLETED
            }?.title ?: "全部完成",
            checks = checks,
            steps = steps
        )
    }

    // ========== CHECKS ==========

    private fun runAllChecks(ctx: Context): RootChecks {
        val devOpts = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) { false }

        val oemUnlock = try {
            Settings.Global.getInt(ctx.contentResolver, "oem_unlock_enabled", 0) == 1
        } catch (_: Exception) { getProp("sys.oem_unlock_allowed") == "1" }

        val usbDbg = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) { false }

        val isXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

        val xiaomiBound = if (isXiaomi) {
            try {
                android.accounts.AccountManager.get(ctx).getAccountsByType("com.xiaomi").isNotEmpty()
            } catch (_: Exception) { false }
        } else true

        val blUnlocked = checkBootloader()

        val magiskInstalled = File("/data/adb/magisk.db").exists()
        val magiskApp = try { ctx.packageManager.getPackageInfo("com.topjohnwu.magisk", 0); true }
            catch (_: Exception) { false }
        val lsposed = try { ctx.packageManager.getPackageInfo("org.lsposed.manager", 0); true }
            catch (_: Exception) { false }
        val hasSu = File("/system/bin/su").exists() || File("/system/xbin/su").exists() ||
                File("/sbin/su").exists() || suExec("id")?.contains("uid=0") == true
        val moduleActive = ConfigWriter.isConfigValid()

        return RootChecks(
            devOptionsEnabled = devOpts,
            oemUnlockAllowed = oemUnlock,
            usbDebugEnabled = usbDbg,
            xiaomiAccountBound = xiaomiBound,
            isXiaomi = isXiaomi,
            bootloaderUnlocked = blUnlocked,
            magiskInstalled = magiskInstalled,
            magiskAppInstalled = magiskApp,
            lsposedInstalled = lsposed,
            hasSuBinary = hasSu,
            moduleActive = moduleActive
        )
    }

    private fun determinePhase(c: RootChecks): RootPhase = when {
        c.magiskInstalled && c.lsposedInstalled -> RootPhase.READY
        c.bootloaderUnlocked -> RootPhase.BOOTLOADER_UNLOCKED
        c.oemUnlockAllowed -> RootPhase.OEM_UNLOCK_ALLOWED
        c.devOptionsEnabled -> RootPhase.DEVELOPER_ENABLED
        else -> RootPhase.NONE
    }

    // ========== STEPS ==========

    private fun buildSteps(ctx: Context, c: RootChecks, phase: RootPhase): List<RootStep> {
        return listOf(
            RootStep(
                title = "① 开启开发者选项",
                description = if (c.isXiaomi) "设置→我的设备→全部参数→连点7次MIUI版本"
                              else "设置→关于手机→连点7次版本号",
                status = if (c.devOptionsEnabled) StepStatus.COMPLETED else
                    if (phase == RootPhase.NONE) StepStatus.IN_PROGRESS else StepStatus.COMPLETED,
                actionLabel = "打开设置",
                action = { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            ),
            RootStep(
                title = "② OEM解锁 + USB调试",
                description = "开发者选项→开启OEM解锁+USB调试",
                status = if (c.oemUnlockAllowed && c.usbDebugEnabled) StepStatus.COMPLETED
                    else if (c.devOptionsEnabled) StepStatus.IN_PROGRESS else StepStatus.PENDING,
                actionLabel = "打开开发者选项",
                action = { it.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            ),
            RootStep(
                title = if (c.isXiaomi) "③ 绑定小米账号" else "③ 账号绑定(跳过)",
                description = if (c.isXiaomi) "开发者选项→Mi Unlock状态→绑定账号和设备(需等待7天)" else "非小米设备自动跳过",
                status = if (c.xiaomiAccountBound) StepStatus.COMPLETED
                    else if (c.oemUnlockAllowed && c.isXiaomi) StepStatus.IN_PROGRESS
                    else if (!c.isXiaomi) StepStatus.COMPLETED else StepStatus.PENDING,
                actionLabel = "打开账号设置",
                action = { it.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
            ),
            RootStep(
                title = "④ 解锁Bootloader",
                description = if (c.isXiaomi) "PC安装MiUnlock工具→手机关机→音量下+电源进fastboot→USB连PC解锁(会清空数据!)"
                              else "PC安装对应品牌解锁工具→fastboot oem unlock",
                status = if (c.bootloaderUnlocked) StepStatus.COMPLETED
                    else if (c.xiaomiAccountBound || !c.isXiaomi) StepStatus.IN_PROGRESS
                    else StepStatus.PENDING,
                actionLabel = "设备信息",
                action = {
                    val info = "设备: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                               "Android: ${Build.VERSION.RELEASE}\n" +
                               "CPU: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}\n\n" +
                               "BL状态: ${getBootloaderStatus()}"
                    android.widget.Toast.makeText(it, info, android.widget.Toast.LENGTH_LONG).show()
                }
            ),
            RootStep(
                title = "⑤ 安装Magisk",
                description = "下载Magisk APK→安装→打开→修补boot.img→fastboot flash boot→重启",
                status = if (c.magiskInstalled) StepStatus.COMPLETED
                    else if (c.bootloaderUnlocked) StepStatus.IN_PROGRESS else StepStatus.PENDING,
                actionLabel = "下载Magisk",
                action = { downloadMagiskApk(it) }
            ),
            RootStep(
                title = "⑥ 安装LSPosed",
                description = "Magisk→模块→从本地安装→选择LSPosed.zip→重启→安装LSPosed Manager",
                status = if (c.lsposedInstalled) StepStatus.COMPLETED
                    else if (c.magiskInstalled) StepStatus.IN_PROGRESS else StepStatus.PENDING,
                actionLabel = "打开Magisk",
                action = {
                    try { it.startActivity(it.packageManager.getLaunchIntentForPackage("com.topjohnwu.magisk")) }
                    catch (_: Exception) {}
                }
            ),
            RootStep(
                title = "⑦ 激活Xposed模块",
                description = "LSPosed Manager→模块→启用本App→作用域勾选飞书→重启飞书",
                status = if (c.moduleActive) StepStatus.COMPLETED
                    else if (c.lsposedInstalled) StepStatus.IN_PROGRESS else StepStatus.PENDING,
                actionLabel = "打开LSPosed",
                action = {
                    try { it.startActivity(it.packageManager.getLaunchIntentForPackage("org.lsposed.manager")) }
                    catch (_: Exception) {}
                }
            ),
            RootStep(
                title = "⑧ 写入定位配置",
                description = "在本App中选择位置→选择飞书→写入配置→启动飞书打卡",
                status = if (c.moduleActive) StepStatus.COMPLETED else StepStatus.PENDING,
                actionLabel = "选择位置",
                action = null  // User interacts with main UI
            )
        )
    }

    // ========== BOOTLOADER ==========

    private fun checkBootloader(): Boolean {
        if (getProp("ro.boot.verifiedbootstate") == "orange") return true
        if (getProp("ro.boot.flash.locked") == "0") return true
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
        if (suPaths.any { File(it).exists() }) return true
        try {
            if (File("/proc/self/attr/current").readText().trim() == "permissive") return true
        } catch (_: Exception) {}
        return false
    }

    fun getBootloaderStatus(): String {
        val state = getProp("ro.boot.verifiedbootstate") ?: "unknown"
        val locked = getProp("ro.boot.flash.locked") ?: "unknown"
        val unlocked = checkBootloader()
        return when {
            unlocked -> "已解锁 (verifiedboot=$state, flash.locked=$locked)"
            state == "green" -> "已锁定"
            else -> "未检测到 (需解锁后重试)"
        }
    }

    // ========== MAGISK DOWNLOAD ==========

    /**
     * Download latest Magisk APK using system DownloadManager.
     * Returns download ID for tracking completion.
     */
    fun downloadMagiskApk(ctx: Context): Long {
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val url = "https://github.com/topjohnwu/Magisk/releases/latest/download/Magisk-v28.1.apk"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Magisk")
                setDescription("Root管理工具")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Magisk-v28.1.apk")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
            }
            dm.enqueue(request)
        } catch (e: Exception) {
            -1L
        }
    }

    fun downloadLsposedZip(ctx: Context): Long {
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val url = "https://github.com/LSPosed/LSPosed/releases/latest/download/LSPosed-v1.9.2-7024-zygisk-release.zip"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("LSPosed (Zygisk)")
                setDescription("Xposed框架模块")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "LSPosed-v1.9.2-7024-zygisk-release.zip")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            dm.enqueue(request)
        } catch (e: Exception) {
            -1L
        }
    }

    // ========== IP LOCATION CHECK ==========

    /**
     * Check if current IP location matches GPS location.
     * Calls ip-api.com (free, no key needed, 45 req/min limit).
     * Returns null if network unavailable.
     */
    fun checkIpLocation(gpsCity: String, gpsLat: Double, gpsLng: Double): IpCheckResult {
        if (gpsCity.isBlank()) return IpCheckResult.UNKNOWN
        return try {
            val url = java.net.URL("http://ip-api.com/json?fields=city,countryCode,lat,lon")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Parse minimal JSON (avoid Gson dependency in static context)
            val ipCity = extractJsonValue(json, "city")
            val ipCountry = extractJsonValue(json, "countryCode")
            val ipLat = extractJsonValue(json, "lat").toDoubleOrNull() ?: 0.0
            val ipLng = extractJsonValue(json, "lon").toDoubleOrNull() ?: 0.0

            if (ipCity.isBlank() || ipCountry != "CN") {
                return IpCheckResult(error = "IP查询失败或非国内IP")
            }

            val match = ipCity.contains(gpsCity.take(2)) ||
                        gpsCity.contains(ipCity.take(2)) ||
                        distanceKm(ipLat, ipLng, gpsLat, gpsLng) < 50.0

            IpCheckResult(
                ipCity = ipCity,
                gpsCity = gpsCity,
                match = match,
                distanceKm = distanceKm(ipLat, ipLng, gpsLat, gpsLng),
                warning = if (!match) "⚠️ IP归属地($ipCity)与GPS目标($gpsCity)不一致! 建议连接${gpsCity}VPN" else null
            )
        } catch (_: Exception) {
            IpCheckResult(error = "网络不可用, 跳过IP检测")
        }
    }

    data class IpCheckResult(
        val ipCity: String = "",
        val gpsCity: String = "",
        val match: Boolean = false,
        val distanceKm: Double = 0.0,
        val warning: String? = null,
        val error: String? = null
    ) {
        companion object {
            val UNKNOWN = IpCheckResult(error = "未检测")
        }
    }

    private fun extractJsonValue(json: String, key: String): String {
        val pattern = """"$key"\s*:\s*"?([^",}\s]+)"?""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") ?: ""
    }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLng / 2).pow(2)
        return 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    // ========== SU ==========

    fun isRootAvailable(): Boolean {
        return suExec("id")?.contains("uid=0") == true
    }

    private fun suExec(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val out = reader.readText()
            p.waitFor()
            reader.close()
            if (out.isBlank()) null else out.trim()
        } catch (_: Exception) { null }
    }

    // ========== UTIL ==========

    private fun getProp(key: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            val v = r.readLine()
            p.waitFor()
            r.close()
            v
        } catch (_: Exception) { null }
    }
}
