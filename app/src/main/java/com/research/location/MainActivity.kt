package com.research.location

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.research.location.model.SavedLocation
import com.research.location.model.AppInfo
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    // -- 定位 --
    private lateinit var client: FusedLocationProviderClient
    private var currentLocation: Location? = null

    // -- 数据 --
    private val savedLocations = mutableListOf<SavedLocation>()
    private val installedApps = mutableListOf<AppInfo>()
    private lateinit var adapter: LocationAdapter
    private var selectedAppPkg = ""
    private var selectedAppName = ""
    private var mockActive = false
    private val df = DecimalFormat("#.000000")

    // -- 临时WiFi配置 --
    private var tempWifiSsid = ""
    private var tempWifiBssid = ""

    // -- 隐藏开发者模式 --
    private var devTapCount = 0
    private var devModeEnabled = false

    // -- UI --
    private lateinit var tvTitle: TextView
    private lateinit var tvCurrentCoords: TextView
    private lateinit var tvMockStatus: TextView
    private lateinit var tvSelectedApp: TextView
    private lateinit var tvEnvInfo: TextView
    private lateinit var llEnvInfo: LinearLayout
    private lateinit var rvLocations: RecyclerView
    private lateinit var btnStopMock: Button

    // -- 权限 --
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // -- 地图选点回调 --
    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val lat = result.data?.getDoubleExtra("lat", Double.NaN) ?: return@registerForActivityResult
            val lng = result.data?.getDoubleExtra("lng", Double.NaN) ?: return@registerForActivityResult
            if (!lat.isNaN() && !lng.isNaN()) {
                showSaveDialog(lat, lng)
            }
        }
    }

    // -- 定位回调 --
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            currentLocation = r.lastLocation
            r.lastLocation?.let {
                tvCurrentCoords.text = "\uD83D\uDCCD ${df.format(it.latitude)}, ${df.format(it.longitude)} (\u00B1${it.accuracy.toInt()}m)"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        client = LocationServices.getFusedLocationProviderClient(this)
        initViews()
        loadSavedLocations()
        loadInstalledApps()
        checkPermissions()
        startLocationUpdates()
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tv_title)
        tvCurrentCoords = findViewById(R.id.tv_current_coords)
        tvMockStatus = findViewById(R.id.tv_mock_status)
        tvSelectedApp = findViewById(R.id.tv_selected_app)
        tvEnvInfo = findViewById(R.id.tv_env_info)
        llEnvInfo = findViewById(R.id.ll_env_info)
        rvLocations = findViewById(R.id.rv_locations)
        btnStopMock = findViewById(R.id.btn_stop_mock)

        tvTitle.setOnLongClickListener {
            devTapCount++
            if (devTapCount >= 5 && !devModeEnabled) {
                devModeEnabled = true
                Toast.makeText(this, "\uD83D\uDD27 开发者模式已启用", Toast.LENGTH_LONG).show()
                tvTitle.text = "\uD83D\uDCCD 定位修改和运用 [DEV]"
                devTapCount = 0
            } else if (devTapCount in 1..4) {
                Toast.makeText(this, "再长按${5 - devTapCount}次进入开发者模式", Toast.LENGTH_SHORT).show()
            }
            true
        }

        adapter = LocationAdapter(
            savedLocations,
            onApply = { loc -> showApplyDialog(loc) },
            onDelete = { loc -> deleteLocation(loc) }
        )
        rvLocations.layoutManager = LinearLayoutManager(this)
        rvLocations.adapter = adapter

        findViewById<Button>(R.id.btn_capture).setOnClickListener { captureCurrentLocation() }
        findViewById<Button>(R.id.btn_map_picker).setOnClickListener { openMapPicker() }
        findViewById<Button>(R.id.btn_select_app).setOnClickListener { showAppSelector() }
        findViewById<Button>(R.id.btn_wifi_config).setOnClickListener { showWifiConfigDialog() }
        btnStopMock.setOnClickListener { stopMock() }
        findViewById<Button>(R.id.btn_guide).setOnClickListener { showUsageGuide() }
        findViewById<TextView>(R.id.tv_setup_guide).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }

        mockActive = MockService.isRunning
        updateMockStatus()
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            client.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build(),
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (_: Exception) {}
    }

    private fun checkPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 30) needed.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) startLocationUpdates()
        mockActive = MockService.isRunning
        updateMockStatus()
    }

    override fun onPause() {
        super.onPause()
        try { client.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    // ========== 位置模糊化 ==========

    private fun fuzzCoordinate(lat: Double, lng: Double): Pair<Double, Double> {
        if (devModeEnabled) return Pair(lat, lng)
        val seed = ((lat * 1e6).roundToInt() xor (lng * 1e6).roundToInt() xor System.currentTimeMillis().toInt())
        val rng = Random(seed)
        val offsetMeters = 30 + rng.nextDouble() * 50
        val angle = rng.nextDouble() * 2 * Math.PI
        val dLat = (offsetMeters * Math.cos(angle)) / 111320.0
        val dLng = (offsetMeters * Math.sin(angle)) / (111320.0 * Math.cos(Math.toRadians(lat)))
        return Pair(lat + dLat, lng + dLng)
    }

    private fun generateCryptoName(): String {
        val prefixes = listOf("地点", "位置点", "坐标", "标记", "点位", "节点")
        val suffixes = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val sdf = SimpleDateFormat("ddHHmm", Locale.getDefault())
        return "${prefixes.random()}${suffixes.random()}-${sdf.format(Date())}"
    }

    // ========== 位置捕获 & 保存 ==========

    private fun captureCurrentLocation() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            checkPermissions()
            return
        }

        // 优先使用缓存位置
        currentLocation?.let { loc ->
            val (fuzzedLat, fuzzedLng) = fuzzCoordinate(loc.latitude, loc.longitude)
            showSaveDialog(fuzzedLat, fuzzedLng)
            return
        }

        // 无缓存，强制获取新位置
        Toast.makeText(this, "正在获取位置...", Toast.LENGTH_SHORT).show()
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        currentLocation = loc
                        val (fuzzedLat, fuzzedLng) = fuzzCoordinate(loc.latitude, loc.longitude)
                        showSaveDialog(fuzzedLat, fuzzedLng)
                    } else {
                        Toast.makeText(this, "无法获取位置，请确认GPS已开启", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "定位失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } catch (e: SecurityException) {
            Toast.makeText(this, "权限不足", Toast.LENGTH_SHORT).show()
            checkPermissions()
        }
    }

    private fun showSaveDialog(lat: Double, lng: Double) {
        val view = layoutInflater.inflate(R.layout.dialog_add_location, null)
        val etName = view.findViewById<TextInputEditText>(R.id.et_name)
        val etLat = view.findViewById<TextInputEditText>(R.id.et_lat)
        val etLng = view.findViewById<TextInputEditText>(R.id.et_lng)

        etName.hint = "名称（如：${generateCryptoName()}）"
        etLat.setText(df.format(lat))
        etLng.setText(df.format(lng))

        // 地图选点按钮
        view.findViewById<TextView>(R.id.tv_map_pick).setOnClickListener {
            val curLat = etLat.text.toString().toDoubleOrNull() ?: lat
            val curLng = etLng.text.toString().toDoubleOrNull() ?: lng
            openMapPickerAt(curLat, curLng)
        }

        val note = if (selectedAppName.isNotEmpty()) "目标App: $selectedAppName" else null
        MaterialAlertDialogBuilder(this)
            .setTitle("保存标记点")
            .setView(view)
            .apply { if (note != null) setMessage(note) }
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString()?.ifBlank { generateCryptoName() } ?: return@setPositiveButton
                val saveLat = etLat.text.toString().toDoubleOrNull() ?: lat
                val saveLng = etLng.text.toString().toDoubleOrNull() ?: lng

                savedLocations.add(
                    SavedLocation(
                        name = name, lat = saveLat, lng = saveLng,
                        targetAppPackage = selectedAppPkg,
                        targetAppName = selectedAppName,
                        wifiSsid = tempWifiSsid,
                        wifiBssid = tempWifiBssid,
                        mcc = "460", mnc = "00",
                        lac = (10000 + Math.random() * 50000).toInt(),
                        cid = (1000000 + Math.random() * 9999999).toInt()
                    )
                )
                saveLocations()
                adapter.notifyItemInserted(savedLocations.size - 1)
                Toast.makeText(this, "已保存: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteLocation(loc: SavedLocation) {
        val idx = savedLocations.indexOf(loc)
        if (idx >= 0) {
            savedLocations.removeAt(idx)
            saveLocations()
            adapter.notifyItemRemoved(idx)
        }
    }

    // ========== 应用模拟 ==========

    private fun showApplyDialog(loc: SavedLocation) {
        val msgs = buildString {
            append("\uD83D\uDCCD ${loc.name}\n")
            append("坐标: ${df.format(loc.lat)}, ${df.format(loc.lng)}")
            if (loc.targetAppName.isNotEmpty()) append("\n\uD83C\uDFAF 目标: ${loc.targetAppName}")
            if (loc.wifiSsid.isNotEmpty()) append("\n\uD83D\uDCE1 WiFi: ${loc.wifiSsid}")
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("应用模拟位置")
            .setMessage(msgs)

        if (loc.targetAppPackage.isNotEmpty()) {
            builder.setPositiveButton("应用并启动App") { _, _ -> applyAndLaunch(loc) }
            builder.setNeutralButton("仅应用定位") { _, _ -> applyMock(loc) }
        } else {
            builder.setPositiveButton("应用定位") { _, _ -> applyMock(loc) }
        }
        builder.setNegativeButton("取消", null).show()
    }

    private fun applyMock(loc: SavedLocation) {
        // Check if this app is set as mock location provider
        val mockApp = Settings.Secure.getString(contentResolver, "mock_location")
        if (mockApp.isNullOrEmpty() || mockApp != packageName) {
            Toast.makeText(this, "Mock Location not configured!", Toast.LENGTH_LONG).show()
            MaterialAlertDialogBuilder(this)
                .setTitle("Mock Location Required")
                .setMessage("Go to:\nSettings > Developer Options > Select mock location app\n\nChoose 'LocationMod' as the mock location provider.\n\nThis is REQUIRED for location spoofing.")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        startService(Intent(this, MockService::class.java).apply {
            putExtra("lat", loc.lat)
            putExtra("lng", loc.lng)
            putExtra("targetPackage", loc.targetAppPackage)
            putExtra("targetApp", loc.targetAppName)
            putExtra("wifiSsid", loc.wifiSsid)
            putExtra("wifiBssid", loc.wifiBssid)
            putExtra("mcc", loc.mcc)
            putExtra("mnc", loc.mnc)
            putExtra("lac", loc.lac)
            putExtra("cid", loc.cid)
        })
        mockActive = true
        updateMockStatus()
        Toast.makeText(this, "定位已修改: ${loc.name}", Toast.LENGTH_SHORT).show()

        if (loc.wifiSsid.isNotEmpty()) {
            tvEnvInfo.text = "WiFi: ${loc.wifiSsid} | BSSID: ${loc.wifiBssid}"
            llEnvInfo.visibility = View.VISIBLE
        }
    }

    private fun applyAndLaunch(loc: SavedLocation) {
        applyMock(loc)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = packageManager.getLaunchIntentForPackage(loc.targetAppPackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "无法启动: ${loc.targetAppName}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, 800)
    }

    private fun stopMock() {
        stopService(Intent(this, MockService::class.java))
        mockActive = false
        updateMockStatus()
        llEnvInfo.visibility = View.GONE
        Toast.makeText(this, "模拟已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateMockStatus() {
        tvMockStatus.text = if (mockActive) "\u25CF 运行中 | 点击停止\u2192" else "\u25CB 未启动"
        tvMockStatus.setTextColor(if (mockActive) 0xFF4CAF50.toInt() else 0xFF999999.toInt())
        btnStopMock.isEnabled = mockActive
    }

    // ========== App选择器 ==========

    private fun loadInstalledApps() {
        installedApps.clear()
        val pm = packageManager

        val known = mapOf(
            "com.alibaba.android.rimet" to "钉钉",
            "com.tencent.wework" to "企业微信",
            "com.tencent.mm" to "微信",
            "com.ss.android.lark" to "飞书",
            "com.larksuite.suite" to "Lark",
            "com.ss.android.lark.lite" to "飞书极速版",
            "com.alibaba.android.rimet.lite" to "钉钉极速版",
            "com.tencent.wework.intl" to "企微海外版"
        )
        for ((pkg, name) in known) {
            try { pm.getPackageInfo(pkg, 0); installedApps.add(AppInfo(pkg, name)) } catch (_: Exception) {}
        }

        val mainIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        for (ri in pm.queryIntentActivities(mainIntent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (installedApps.none { it.packageName == pkg }) {
                installedApps.add(AppInfo(pkg, ri.loadLabel(pm).toString()))
            }
        }
    }

    private fun showAppSelector() {
        val names = installedApps.map { "${it.appName} (${it.packageName})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择目标应用（仅对此App生效）")
            .setItems(names) { _, which ->
                val app = installedApps[which]
                selectedAppPkg = app.packageName
                selectedAppName = app.appName
                tvSelectedApp.text = "\u2713 ${app.appName}"
                AntiDetection.tryHideMockLocationFlag(this)
                val profile = AntiDetection.getProfile(app.packageName)
                Toast.makeText(this, "${app.appName} | ${profile.extraMeasures.firstOrNull() ?: "标准防护"}",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========== 地图选点 ==========

    private fun openMapPicker() {
        currentLocation?.let {
            openMapPickerAt(it.latitude, it.longitude)
        } ?: openMapPickerAt(39.9042, 116.4074)
    }

    private fun openMapPickerAt(lat: Double, lng: Double) {
        val intent = Intent(this, MapPickerActivity::class.java).apply {
            putExtra("lat", lat)
            putExtra("lng", lng)
        }
        mapPickerLauncher.launch(intent)
    }

    // ========== WiFi配置 ==========

    @SuppressLint("MissingPermission")
    private fun showWifiConfigDialog() {
        val view = try {
            layoutInflater.inflate(R.layout.dialog_wifi_config, null)
        } catch (e: Exception) {
            Toast.makeText(this, "界面加载失败", Toast.LENGTH_SHORT).show()
            return
        }

        val etSsid = view.findViewById<TextInputEditText>(R.id.et_wifi_ssid)
        val etBssid = view.findViewById<TextInputEditText>(R.id.et_wifi_bssid)

        etSsid.setText(tempWifiSsid)
        etBssid.setText(tempWifiBssid)

        // 自动捕获
        if (tempWifiSsid.isEmpty()) {
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                val conn = wm?.connectionInfo
                if (conn != null && conn.ssid != null && conn.ssid != "<unknown ssid>" && conn.ssid != "0x") {
                    tempWifiSsid = conn.ssid.replace("\"", "")
                    tempWifiBssid = conn.bssid?.replace("\"", "") ?: ""
                    etSsid.setText(tempWifiSsid)
                    etBssid.setText(tempWifiBssid)
                }
            } catch (_: Exception) {}
        }

        view.findViewById<Button>(R.id.btn_capture_wifi).setOnClickListener {
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                if (wm == null) {
                    Toast.makeText(this, "无法访问WiFi服务", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val conn = wm.connectionInfo
                val ssid = conn?.ssid?.replace("\"", "") ?: ""
                val bssid = conn?.bssid?.replace("\"", "") ?: ""
                if (ssid.isNotEmpty() && ssid != "0x" && ssid != "<unknown ssid>") {
                    etSsid.setText(ssid)
                    etBssid.setText(bssid)
                    Toast.makeText(this, "已捕获: $ssid", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未连接WiFi，请先连接WiFi再捕获", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "捕获失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.btn_gen_bssid).setOnClickListener {
            etBssid.setText(generateRandomBssid())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("WiFi信息配置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                tempWifiSsid = etSsid.text?.toString()?.trim() ?: ""
                tempWifiBssid = etBssid.text?.toString()?.trim() ?: ""
                Toast.makeText(this, "WiFi配置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun generateRandomBssid(): String {
        val sb = StringBuilder()
        for (i in 0 until 6) {
            if (i > 0) sb.append(":")
            val hex = Integer.toHexString((Math.random() * 256).toInt())
            if (hex.length == 1) sb.append("0")
            sb.append(hex)
        }
        return sb.toString()
    }

    // ========== Usage Guide ==========

    private fun showUsageGuide() {
        val guide = "=== Location Mod Usage Guide ===\n\n" +
                "1. Developer Options\n" +
                "   Settings > Developer Options > Mock Location App\n" +
                "   Select 'LocationMod' as mock location provider\n" +
                "   REQUIRED for location spoofing\n\n" +
                "2. Select Target App\n" +
                "   Choose the app to spoof (DingTalk, WeChat, etc.)\n\n" +
                "3. Set Target Location\n" +
                "   Tap Capture for current GPS or Map to pick visually\n" +
                "   Save the location after naming it\n\n" +
                "4. Configure WiFi (Optional)\n" +
                "   Tap WiFi to capture current network info\n" +
                "   This helps anti-detection matching\n\n" +
                "5. Apply & Launch\n" +
                "   Tap a saved location, then Apply + Launch\n" +
                "   Use Stop button when done\n\n" +
                "Note: Mock location affects ALL apps.\n" +
                "Keep LocationMod running in background."

        MaterialAlertDialogBuilder(this)
            .setTitle("Usage Guide")
            .setMessage(guide)
            .setPositiveButton("OK", null)
            .show()
    }

    // ========== Persistence ==========

    private fun loadSavedLocations() {
        val json = getSharedPreferences("locations", 0).getString("data", null) ?: return
        try {
            val list = Gson().fromJson<List<SavedLocation>>(json,
                object : TypeToken<List<SavedLocation>>() {}.type)
            savedLocations.clear()
            savedLocations.addAll(list)
        } catch (_: Exception) {}
    }

    private fun saveLocations() {
        getSharedPreferences("locations", 0).edit()
            .putString("data", Gson().toJson(savedLocations)).apply()
    }
}
