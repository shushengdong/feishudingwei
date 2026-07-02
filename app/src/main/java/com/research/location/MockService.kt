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
        profile = AntiDetection.getProfile(targetPackage)
        step = 0

        val df = java.text.DecimalFormat("#.000000")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Mock Active")
            .setContentText("Target: ${profile.displayName} (${df.format(baseLat)}, ${df.format(baseLng)})")
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

    private fun setupProviders(lm: LocationManager): Boolean {
        var success = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
            try {
                lm.addTestProvider(p, false, false, false, false, true, true, true,
                    android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
                success = true
            } catch (_: Exception) {}
        }
        return success
    }

    private fun runMock() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        // CRITICAL: Set up test providers before starting mock
        val providersSetup = setupProviders(lm)

        if (!providersSetup) {
            // Mock location not properly configured
            val note = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mock NOT configured!")
                .setContentText("Go to Developer Options > Mock Location App > select LocationMod")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, note)
            running = false
            isRunning = false
            stopSelf()
            return
        }

        // Try to hide mock flag
        AntiDetection.tryHideMockLocationFlag(this)

        var stepCount = 0
        val df = java.text.DecimalFormat("#.000000")
        while (running) {
            val (jitterLat, jitterLng) = AntiDetection.jitterLocation(baseLat, baseLng, profile, stepCount)
            val gpsAcc = AntiDetection.realisticAccuracy(profile)
            val speed = AntiDetection.simulatedSpeed(profile)
            val altitude = AntiDetection.simulatedAltitude()

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

            val netAcc = AntiDetection.networkAccuracy()
            val netLoc = Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = jitterLat + (Random.nextDouble() - 0.5) * 0.0003
                longitude = jitterLng + (Random.nextDouble() - 0.5) * 0.0003
                accuracy = netAcc
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = System.nanoTime()
            }
            try { lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, netLoc) } catch (_: Exception) {}

            if (profile.spoofWifi) spoofWifiScanResults(jitterLat, jitterLng)
            if (profile.spoofCell) spoofCellInfo()

            // Update notification with current mock location
            val note = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mock: ${df.format(jitterLat)}, ${df.format(jitterLng)}")
                .setContentText("Target: ${profile.displayName} | Accuracy: ${gpsAcc.toInt()}m")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, note)

            stepCount++
            try { Thread.sleep(profile.updateInterval + Random.nextLong(500)) } catch (_: InterruptedException) { break }
        }
    }

    @SuppressLint("MissingPermission")
    private fun spoofWifiScanResults(lat: Double, lng: Double) {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val fakeResults = mutableListOf<ScanResult>()
            val apCount = 3 + Random.nextInt(3)
            for (i in 0 until apCount) {
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
                    this.level = -30 - Random.nextInt(60)
                    this.frequency = if (Random.nextBoolean()) 2462 else 2412
                    this.capabilities = "[WPA2-PSK-CCMP][ESS]"
                    fakeResults.add(this)
                }
            }
            try {
                val field = WifiManager::class.java.getDeclaredField("mScanResults")
                field.isAccessible = true
                field.set(wifiManager, fakeResults)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun spoofCellInfo() {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val networkType = try { tm.dataNetworkType } catch (_: Exception) { 13 }
            val cellList = mutableListOf<CellInfo>()
            when {
                networkType == 13 || networkType == 19 -> {
                    val ci = CellIdentityLte::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java,
                        Int::class.java, Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        100 + Random.nextInt(400), 0, 30000 + Random.nextInt(30000),
                        460, 0, ""
                    )
                    val ss = CellSignalStrengthLte::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java,
                        Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        -70 - Random.nextInt(30), -80 - Random.nextInt(30),
                        -5 - Random.nextInt(15), 20000 + Random.nextInt(10000), 0
                    )
                    val cellInfo = CellInfoLte::class.java.getConstructor().apply { isAccessible = true }.newInstance()
                    CellInfoLte::class.java.getDeclaredMethod("setCellIdentity", CellIdentityLte::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ci)
                    CellInfoLte::class.java.getDeclaredMethod("setCellSignalStrength", CellSignalStrengthLte::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ss)
                    cellList.add(cellInfo)
                }
                else -> {
                    val ci = CellIdentityGsm::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java,
                        Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        100 + Random.nextInt(400), 460, 0,
                        30000 + Random.nextInt(30000), 1000 + Random.nextInt(5000)
                    )
                    val ss = CellSignalStrengthGsm::class.java.getConstructor(
                        Int::class.java, Int::class.java, Int::class.java
                    ).apply { isAccessible = true }.newInstance(
                        -60 - Random.nextInt(40), 0, 0
                    )
                    val cellInfo = CellInfoGsm::class.java.getConstructor().apply { isAccessible = true }.newInstance()
                    CellInfoGsm::class.java.getDeclaredMethod("setCellIdentity", CellIdentityGsm::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ci)
                    CellInfoGsm::class.java.getDeclaredMethod("setCellSignalStrength", CellSignalStrengthGsm::class.java)
                        .apply { isAccessible = true }.invoke(cellInfo, ss)
                    cellList.add(cellInfo)
                }
            }
        } catch (_: Exception) {}
    }

    private fun generateBssidFromLocation(lat: Double, lng: Double, idx: Int): String {
        val hash = ((lat * 1_000_000).toLong() xor (lng * 1_000_000).toLong() + idx * 0xABCDEF)
        val parts = listOf(
            (hash shr 40) and 0xFF, (hash shr 32) and 0xFF, (hash shr 24) and 0xFF,
            (hash shr 16) and 0xFF, (hash shr 8) and 0xFF, hash and 0xFF
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
            val channel = NotificationChannel(CHANNEL_ID, "Location Mock", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "mock_location"
        @Volatile var isRunning = false
    }
}
