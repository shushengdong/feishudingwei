package com.research.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.research.location.model.WifiConfig
import kotlin.math.*
import kotlin.random.Random

class MockService : Service() {
    private var mockThread: Thread? = null
    private var running = false
    private var baseLat = 0.0
    private var baseLng = 0.0
    private var targetPackage = ""
    private var profile = AntiDetection.AppProfile("", "")
    private var step = 0
    private var wifiConfig = WifiConfig()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        baseLat = intent?.getDoubleExtra("lat", 0.0) ?: return START_NOT_STICKY
        baseLng = intent?.getDoubleExtra("lng", 0.0) ?: return START_NOT_STICKY
        targetPackage = intent?.getStringExtra("targetPackage") ?: ""
        val wifiJson = intent?.getStringExtra("wifiConfig")
        if (!wifiJson.isNullOrBlank()) {
            try { wifiConfig = com.google.gson.Gson().fromJson(wifiJson, WifiConfig::class.java) } catch (_: Exception) {}
        }
        profile = AntiDetection.getProfile(targetPackage)
        step = 0

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置模拟中")
            .setContentText("目标: ${profile.displayName} (${java.text.DecimalFormat("#.000000").format(baseLat)}, ${java.text.DecimalFormat("#.000000").format(baseLng)})")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        isRunning = true
        startMock()
        return START_STICKY
    }

    private fun startMock() {
        running = true
        mockThread = Thread { runMock() }.apply { start() }
    }

    private fun setupProviders(lm: LocationManager) {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
            try {
                lm.addTestProvider(p, false, false, false, false, true, true, true,
                    android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
            } catch (_: Exception) {}
        }
    }

    private fun runMock() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        
        // 尝试隐藏Mock标志
        AntiDetection.tryHideMockLocationFlag(this)

        var stepCount = 0
        while (running) {
            // GPS位置生成（带自然抖动和行走模式）
            val (jitterLat, jitterLng) = AntiDetection.jitterLocation(baseLat, baseLng, profile, stepCount)
            val gpsAcc = AntiDetection.realisticAccuracy(profile)
            val speed = AntiDetection.simulatedSpeed(profile)
            val altitude = AntiDetection.simulatedAltitude()
            val svCount = AntiDetection.simulatedSatelliteCount()

            val gpsLoc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = jitterLat
                longitude = jitterLng
                accuracy = gpsAcc
                this.altitude = altitude
                this.speed = speed
                bearing = (stepCount * 3 % 360).toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = System.nanoTime()
            }

            try {
                lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, gpsLoc)
                lm.setTestProviderStatus(LocationManager.GPS_PROVIDER,
                    android.location.LocationProvider.AVAILABLE, null, System.currentTimeMillis())
            } catch (_: Exception) {}

            // 网络定位（精度更低）
            val netAcc = AntiDetection.networkAccuracy()
            val netLoc = Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = jitterLat + (Random.nextDouble() - 0.5) * 0.0003
                longitude = jitterLng + (Random.nextDouble() - 0.5) * 0.0003
                accuracy = netAcc
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = System.nanoTime()
            }
            try { lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, netLoc) } catch (_: Exception) {}

            // WiFi伪装
            if (profile.spoofWifi) spoofWifiScanResults(jitterLat, jitterLng)
            
            // 基站伪装
            if (profile.spoofCell) spoofCellInfo()

            stepCount++
            try { Thread.sleep(profile.updateInterval + Random.nextLong(500)) } catch (_: InterruptedException) { break }
        }
    }

    /**
     * WiFi扫描结果伪装
     * 通过反射注入自定义ScanResult列表
     * 注意：Android 9+对隐藏API有限制，但反射仍可能有效
     */
    @SuppressLint("MissingPermission")
    private fun spoofWifiScanResults(lat: Double, lng: Double) {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            
            // 生成3-5个伪装WiFi热点
            val fakeResults = mutableListOf<ScanResult>()
            val apCount = 3 + Random.nextInt(3)
            
            for (i in 0 until apCount) {
                // 基于位置生成BSSID
                val bssid = if (wifiConfig.bssid.isNotBlank() && i == 0) {
                    wifiConfig.bssid
                } else {
                    generateBssidFromLocation(lat, lng, i)
                }
                
                val ssid = if (wifiConfig.ssid.isNotBlank() && i == 0) {
                    wifiConfig.ssid
                } else {
                    listOf("TP-LINK_", "CMCC-", "ChinaNet-", "WiFi-", "NETGEAR", "Xiaomi_", "HUAWEI-")
                        .random() + (1000 + Random.nextInt(9000)).toString()
                }

                ScanResult::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
                    this.SSID = ssid
                    this.BSSID = bssid
                    this.level = -30 - Random.nextInt(60)  // -30 to -90 dBm
                    this.frequency = if (Random.nextBoolean()) 2462 else 2412
                    this.capabilities = "[WPA2-PSK-CCMP][ESS]"
                    fakeResults.add(this)
                }
            }
            
            // 反射注入
            try {
                val field = WifiManager::class.java.getDeclaredField("mScanResults")
                field.isAccessible = true
                field.set(wifiManager, fakeResults)
            } catch (_: Exception) {
                // 备用方案：通过 WifiManager 内部类注入
                try {
                    val method = WifiManager::class.java.getDeclaredMethod("setScanResults", List::class.java)
                    method.isAccessible = true
                    method.invoke(wifiManager, fakeResults)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // WiFi伪装在某些设备上可能失败，不影响主功能
        }
    }

    /**
     * 基站信息伪装
     * 通过TelephonyManager反射设置伪基站信息
     * 注意：Android 9+对TelephonyManager的修改受限严重
     */
    @SuppressLint("MissingPermission")
    private fun spoofCellInfo() {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val networkType = try { tm.dataNetworkType } catch (_: Exception) { 13 /* LTE */ }
            
            val cellList = mutableListOf<CellInfo>()
            val mcc = if (wifiConfig.bssid.isNotBlank()) "460" else "460"
            val mnc = if (wifiConfig.bssid.isNotBlank()) "00" else "00"
            
            when {
                networkType == 13 || networkType == 19 -> {
                    // LTE
                    val ci = CellIdentityLte::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java,
                        Int::class.java, Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        100 + Random.nextInt(400),     // ci
                        0,                               // pci
                        30000 + Random.nextInt(30000),  // tac
                        mcc.toInt(),                    // mcc
                        mnc.toInt(),                    // mnc
                        ""                               // alpha
                    )
                    val ss = CellSignalStrengthLte::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java,
                        Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        -70 - Random.nextInt(30),       // rssi
                        -80 - Random.nextInt(30),       // rsrp
                        -5 - Random.nextInt(15),        // rsrq
                        20000 + Random.nextInt(10000),  // rssnr
                        0                                // cqi
                    )
                    val cellInfo = CellInfoLte::class.java.getConstructor().apply { isAccessible = true }.newInstance()
                    CellInfoLte::class.java.getDeclaredMethod("setCellIdentity", CellIdentityLte::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ci)
                    CellInfoLte::class.java.getDeclaredMethod("setCellSignalStrength", CellSignalStrengthLte::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ss)
                    cellList.add(cellInfo)
                }
                networkType == 3 || networkType == 8 || networkType == 9 || networkType == 10 -> {
                    // WCDMA
                    val ci = CellIdentityWcdma::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java,
                        Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        100 + Random.nextInt(400), mcc.toInt(), mnc.toInt(),
                        30000 + Random.nextInt(30000), 1000 + Random.nextInt(5000)
                    )
                    val cellInfo = CellInfoWcdma::class.java.getConstructor().apply { isAccessible = true }.newInstance()
                    CellInfoWcdma::class.java.getDeclaredMethod("setCellIdentity", CellIdentityWcdma::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ci)
                    cellList.add(cellInfo)
                }
                else -> {
                    // GSM
                    val ci = CellIdentityGsm::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java,
                        Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        100 + Random.nextInt(400), mcc.toInt(), mnc.toInt(),
                        30000 + Random.nextInt(30000), 1000 + Random.nextInt(5000)
                    )
                    val ss = CellSignalStrengthGsm::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        -60 - Random.nextInt(40),       // rssi
                        0,                                // ber
                        0                                 // timingAdvance
                    )
                    val cellInfo = CellInfoGsm::class.java.getConstructor().apply { isAccessible = true }.newInstance()
                    CellInfoGsm::class.java.getDeclaredMethod("setCellIdentity", CellIdentityGsm::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ci)
                    CellInfoGsm::class.java.getDeclaredMethod("setCellSignalStrength", CellSignalStrengthGsm::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ss)
                    cellList.add(cellInfo)
                }
            }
        } catch (_: Exception) {
            // 基站伪装可能失败（权限/API限制），不影响GPS主功能
        }
    }

    /**
     * 基于位置坐标生成伪BSSID（保持一致性）
     */
    private fun generateBssidFromLocation(lat: Double, lng: Double, idx: Int): String {
        val hash = ((lat * 1_000_000).toLong() xor (lng * 1_000_000).toLong() + idx * 0xABCDEF)
        val parts = listOf(
            (hash shr 40) and 0xFF,
            (hash shr 32) and 0xFF,
            (hash shr 24) and 0xFF,
            (hash shr 16) and 0xFF,
            (hash shr 8) and 0xFF,
            hash and 0xFF
        )
        return parts.joinToString(":") { byteToHex(it.toInt()) }
    }

    private fun byteToHex(b: Int): String {
        val s = Integer.toHexString(b and 0xFF).uppercase()
        return if (s.length == 1) "0$s" else s
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        mockThread?.interrupt()
        isRunning = false
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "位置模拟", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "mock_location"
        @Volatile var isRunning = false
    }
}
