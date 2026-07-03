package com.research.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.provider.Settings
import kotlin.math.*
import kotlin.random.Random

/**
 * 反检测引擎：针对不同App定制伪装策略
 *
 * 检测原理分析：
 * 1. 钉钉/企微：检查 Settings.Secure.ALLOW_MOCK_LOCATION、多源定位一致性、已知Mock应用列表
 * 2. 微信：    检查 WiFi列表与GPS一致性、基站信息、Xposed/root检测
 * 3. 飞书：    检查 Mock Location 开关、位置跳变检测
 * 4. 通用：    检查开发者选项、ADB调试、位置提供者数量
 */
object AntiDetection {

    /** 已知打卡App包名及配置 */
    // 钉钉
    const val PKG_DINGTALK = "com.alibaba.android.rimet"

    // 企业微信
    const val PKG_WECOM = "com.tencent.wework"

    // 微信
    const val PKG_WECHAT = "com.tencent.mm"

    // 飞书
    const val PKG_FEISHU = "com.ss.android.lark"
    const val PKG_LARK = "com.larksuite.suite"

    // 飞书极速版
    const val PKG_FEISHU_LITE = "com.ss.android.lark.lite"

    // 钉钉极速版
    const val PKG_DINGTALK_LITE = "com.alibaba.android.rimet.lite"

    // 企业微信海外版
    const val PKG_WECOM_INTL = "com.tencent.wework.intl"

    data class AppProfile(
        val packageName: String,
        val displayName: String,
        /** 是否需要伪装WiFi */
        val spoofWifi: Boolean = true,
        /** 是否需要伪装基站 */
        val spoofCell: Boolean = true,
        /** GPS抖动幅度(米) */
        val gpsJitter: Float = 15f,
        /** 是否需要模拟行走模式 */
        val walkingMode: Boolean = true,
        /** 网络定位精度偏低(模拟真实基站/WiFi定位) */
        val lowerNetworkAccuracy: Boolean = true,
        /** 定位更新间隔(毫秒) */
        val updateInterval: Long = 2000L,
        /** 额外防护措施 */
        val extraMeasures: List<String> = emptyList()
    )

    val KNOWN_PROFILES = mapOf(
        PKG_DINGTALK to AppProfile(
            packageName = PKG_DINGTALK,
            displayName = "钉钉",
            spoofWifi = true,
            spoofCell = true,
            gpsJitter = 12f,
            walkingMode = true,
            lowerNetworkAccuracy = true,
            updateInterval = 1800L,
            extraMeasures = listOf(
                "隐藏MockLocation标志",
                "模拟WiFi扫描结果",
                "基站LAC/CID伪装",
                "GPS精度动态变化(3-25m)",
                "海拔高度自然波动",
                "速度模拟0-1.5m/s步行"
            )
        ),
        PKG_WECOM to AppProfile(
            packageName = PKG_WECOM,
            displayName = "企业微信",
            spoofWifi = true,
            spoofCell = true,
            gpsJitter = 10f,
            walkingMode = true,
            lowerNetworkAccuracy = true,
            updateInterval = 2000L,
            extraMeasures = listOf(
                "隐藏MockLocation标志",
                "WiFi BSSID与GPS位置匹配",
                "基站信息一致性",
                "多源定位时间戳对齐"
            )
        ),
        PKG_WECHAT to AppProfile(
            packageName = PKG_WECHAT,
            displayName = "微信",
            spoofWifi = true,
            spoofCell = true,
            gpsJitter = 20f,
            walkingMode = true,
            lowerNetworkAccuracy = true,
            updateInterval = 2500L,
            extraMeasures = listOf(
                "WiFi列表伪装(至少3个热点)",
                "基站信号强度模拟",
                "GPS与网络定位误差模拟",
                "定位时间戳自然分布"
            )
        ),
        PKG_FEISHU to AppProfile(
            packageName = PKG_FEISHU,
            displayName = "飞书",
            spoofWifi = true,
            spoofCell = true,
            gpsJitter = 15f,
            walkingMode = true,
            lowerNetworkAccuracy = true,
            updateInterval = 2000L,
            extraMeasures = listOf(
                "位置平滑过渡",
                "WiFi MAC随机化",
                "传感器方向模拟"
            )
        ),
        PKG_LARK to AppProfile(
            packageName = PKG_LARK,
            displayName = "Lark",
            spoofWifi = true,
            spoofCell = true,
            gpsJitter = 15f,
            walkingMode = true,
            lowerNetworkAccuracy = true,
            updateInterval = 2000L
        )
    )

    /** 通用反检测措施 - 适用于任何App */

    /**
     * 尝试关闭"允许模拟位置"的可见标志
     * 注意：这只影响通过 Settings.Secure 读取，App实际检测手段更多
     */
    fun tryHideMockLocationFlag(ctx: Context) {
        try {
            // Android 6.0+ 可以尝试写入
            Settings.Secure.putInt(
                ctx.contentResolver,
                Settings.Secure.ALLOW_MOCK_LOCATION,
                0
            )
        } catch (_: Exception) {
            // 权限不足，忽略
        }
    }

    /**
     * 检查设备上是否安装了已知的Mock/改机工具
     * 这些工具的存在会增加被检测的风险
     */
    fun detectSuspiciousApps(ctx: Context): List<String> {
        val suspicious = listOf(
            "com.lerist.fakelocation",        // Fake Location
            "com.fake.locations",
            "com.lexa.fakegps",               // Fake GPS
            "com.incorporateapps.fakegps",
            "org.microg.nlp",                 // microG
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "com.topjohnwu.magisk",
            "com.github.kr328.clash",         // VPN类
            "com.v2ray.ang"
        )
        val pm = ctx.packageManager
        return suspicious.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * 获取App对应的反检测配置，未匹配的返回通用配置
     */
    fun getProfile(packageName: String): AppProfile {
        return KNOWN_PROFILES[packageName] ?: AppProfile(
            packageName = packageName,
            displayName = packageName,
            spoofWifi = true,
            spoofCell = true,
            gpsJitter = 15f,
            walkingMode = true,
            lowerNetworkAccuracy = true,
            updateInterval = 2000L
        )
    }

    /**
     * 生成带自然抖动的GPS位置
     */
    fun jitterLocation(
        baseLat: Double,
        baseLng: Double,
        profile: AppProfile,
        step: Int
    ): Pair<Double, Double> {
        val jitterDeg = profile.gpsJitter / 111320.0 // 米→度
        val angle = Random.nextDouble() * 2 * PI

        // 模拟步行：缓慢偏移
        val walkOffset = if (profile.walkingMode) {
            val walkSpeed = 0.000001 * (step % 300) / 300.0 // 300步一个循环(~10分钟)
            Pair(
                cos(step * 0.1) * walkSpeed * 3,
                sin(step * 0.1) * walkSpeed * 2
            )
        } else {
            Pair(0.0, 0.0)
        }

        val driftLat = (Random.nextDouble() - 0.5) * jitterDeg * 2 + walkOffset.first
        val driftLng = (Random.nextDouble() - 0.5) * jitterDeg * 2 + walkOffset.second

        return Pair(baseLat + driftLat, baseLng + driftLng)
    }

    /**
     * 生成真实感的GPS精度值(米)
     */
    fun realisticAccuracy(profile: AppProfile): Float {
        return when {
            profile.lowerNetworkAccuracy -> 3f + Random.nextFloat() * 22f  // 3-25m GPS
            else -> 8f + Random.nextFloat() * 22f
        }
    }

    /**
     * 生成真实感的网络定位精度值(米)
     */
    fun networkAccuracy(): Float {
        return 15f + Random.nextFloat() * 65f  // 15-80m 网络定位
    }

    /**
     * 生成模拟的海拔高度(米)
     */
    fun simulatedAltitude(): Double {
        return 5.0 + Random.nextDouble() * 45.0 + sin(Random.nextDouble() * PI) * 2.0
    }

    /**
     * 生成真实感的卫星数量
     */
    fun simulatedSatelliteCount(): Int {
        // 城市环境: 6-12颗可见
        return 6 + Random.nextInt(7)
    }

    /**
     * 生成合理的移动速度(m/s)
     */
    fun simulatedSpeed(profile: AppProfile): Float {
        return if (profile.walkingMode) {
            Random.nextFloat() * 1.5f  // 0-1.5 m/s 步行
        } else {
            0f
        }
    }
}
