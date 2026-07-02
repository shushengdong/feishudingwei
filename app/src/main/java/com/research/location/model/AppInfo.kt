package com.research.location.model

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable? = null
)

data class SavedLocation(
    val name: String,
    val lat: Double,
    val lng: Double,
    val targetAppPackage: String = "",   // 关联的目标App
    val targetAppName: String = "",       // App显示名
    val wifiSsid: String = "",            // 伪装WiFi SSID
    val wifiBssid: String = "",           // 伪装WiFi BSSID
    val mcc: String = "",                 // 移动国家码
    val mnc: String = "",                 // 移动网络码
    val lac: Int = 0,                     // 位置区码
    val cid: Int = 0                      // 基站ID
)

data class WifiConfig(
    val ssid: String = "",
    val bssid: String = "",
    val rssi: Int = -50,                  // 信号强度
    val frequency: Int = 2462             // 频率 MHz
)
