package com.research.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val df = DecimalFormat("#.000000")

    // -- 临时WiFi配置 --
    private var tempWifiSsid = ""
    private var tempWifiBssid = ""

    // -- UI --
    private lateinit var tvTitle: TextView
    private lateinit var tvCurrentCoords: TextView
    private lateinit var tvMockStatus: TextView
    private lateinit var tvConfigInfo: TextView
    private lateinit var tvSelectedApp: TextView
    private lateinit var tvEnvInfo: TextView
    private lateinit var llEnvInfo: LinearLayout
    private lateinit var rvLocations: RecyclerView
    private lateinit var btnStopMock: Button
    private lateinit var btnDiagnostics: Button

    // -- 权限 --
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // -- 地图选点回调 --
    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val lat = result.data?.getDoubleExtra("lat", Double.NaN)
                ?: return@registerForActivityResult
            val lng = result.data?.getDoubleExtra("lng", Double.NaN)
                ?: return@registerForActivityResult
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
                tvCurrentCoords.text =
                    "\uD83D\uDCCD ${df.format(it.latitude)}, " +
                    "${df.format(it.longitude)} (\u00B1${it.accuracy.toInt()}m)"
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
        tvConfigInfo = findViewById(R.id.tv_env_info)  // reuse env info for config status
        tvSelectedApp = findViewById(R.id.tv_selected_app)
        tvEnvInfo = findViewById(R.id.tv_env_info)
        llEnvInfo = findViewById(R.id.ll_env_info)
        rvLocations = findViewById(R.id.rv_locations)
        btnStopMock = findViewById(R.id.btn_stop_mock)
        btnDiagnostics = findViewById(R.id.btn_diagnostics)

        tvTitle.text = "\uD83D\uDCCD 定位修改 [Root版]"

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
        btnStopMock.setOnClickListener { disableMock() }
        btnDiagnostics.setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.btn_guide).setOnClickListener { showUsageGuide() }

        // Hide old setup guide text - no longer needed (no developer options required)
        findViewById<TextView>(R.id.tv_setup_guide)?.visibility = View.GONE

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
        updateMockStatus()
    }

    override fun onPause() {
        super.onPause()
        try { client.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ========== Config-based Mock (replaces MockService) ==========

    private fun showApplyDialog(loc: SavedLocation) {
        val msgs = buildString {
            append("\uD83D\uDCCD ${loc.name}\n")
            append("坐标: ${df.format(loc.lat)}, ${df.format(loc.lng)}")
            if (loc.targetAppName.isNotEmpty()) append("\n\uD83C\uDFAF 目标: ${loc.targetAppName}")
            if (loc.wifiSsid.isNotEmpty()) append("\n\uD83D\uDCE1 WiFi: ${loc.wifiSsid}")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("应用定位 (Xposed模式)")
            .setMessage(msgs)
            .setPositiveButton("写入配置并启动App") { _, _ -> writeConfigAndLaunch(loc) }
            .setNeutralButton("仅写入配置") { _, _ -> writeConfigOnly(loc) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun writeConfigOnly(loc: SavedLocation) {
        if (selectedAppPkg.isEmpty() && loc.targetAppPackage.isEmpty()) {
            Toast.makeText(this, "请先选择目标App", Toast.LENGTH_SHORT).show()
            return
        }

        val pkg = loc.targetAppPackage.ifEmpty { selectedAppPkg }
        val ssid = loc.wifiSsid.ifEmpty { tempWifiSsid }
        val bssid = loc.wifiBssid.ifEmpty { tempWifiBssid }

        val success = ConfigWriter.writeConfig(loc, pkg, ssid, bssid)

        if (success) {
            updateMockStatus()
            Toast.makeText(this, "配置已写入!\n目标: ${loc.name}\n重启飞书后生效", Toast.LENGTH_LONG).show()
            if (ssid.isNotEmpty()) {
                tvEnvInfo.text = "WiFi: $ssid | BSSID: $bssid"
                llEnvInfo.visibility = View.VISIBLE
            }
        } else {
            Toast.makeText(this, "配置写入失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeConfigAndLaunch(loc: SavedLocation) {
        val pkg = loc.targetAppPackage.ifEmpty { selectedAppPkg }
        if (pkg.isEmpty()) {
            Toast.makeText(this, "请先选择目标App", Toast.LENGTH_SHORT).show()
            return
        }

        val success = ConfigWriter.writeConfig(
            loc, pkg,
            loc.wifiSsid.ifEmpty { tempWifiSsid },
            loc.wifiBssid.ifEmpty { tempWifiBssid }
        )

        if (!success) {
            Toast.makeText(this, "配置写入失败", Toast.LENGTH_SHORT).show()
            return
        }

        updateMockStatus()
        Toast.makeText(this, "配置已写入: ${loc.name}", Toast.LENGTH_SHORT).show()

        if (loc.wifiSsid.isNotEmpty()) {
            tvEnvInfo.text = "WiFi: ${loc.wifiSsid} | BSSID: ${loc.wifiBssid}"
            llEnvInfo.visibility = View.VISIBLE
        }

        // Kill and restart target app to pick up new config
        Handler(Looper.getMainLooper()).postDelayed({
            restartTargetApp(pkg, loc.targetAppName)
        }, 500)
    }

    private fun restartTargetApp(pkg: String, appName: String) {
        // Force stop to clear process (so Xposed module reloads config)
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            // Note: killBackgroundProcesses needs KILL_BACKGROUND_PROCESSES permission
            // or we just launch and the old process gets cleaned up
        } catch (_: Exception) {}

        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                Toast.makeText(this, "已启动: $appName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "无法启动: $appName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableMock() {
        val success = ConfigWriter.disableConfig()
        updateMockStatus()
        llEnvInfo.visibility = View.GONE
        if (success) {
            Toast.makeText(this, "配置已禁用。重启飞书恢复正常定位。", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "配置文件不存在或已禁用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMockStatus() {
        val configValid = ConfigWriter.isConfigValid()
        val config = ConfigWriter.readConfig()

        if (configValid && config != null) {
            val targetInfo = config.targetPackages.firstOrNull()?.let { pkg ->
                installedApps.firstOrNull { it.packageName == pkg }?.appName ?: pkg
            } ?: "未指定"
            tvMockStatus.text = "\u25CF 已配置 | 目标: $targetInfo"
            tvMockStatus.setTextColor(0xFF4CAF50.toInt())
            btnStopMock.isEnabled = true
            btnStopMock.text = "禁用配置"
        } else if (config != null && !config.enabled) {
            tvMockStatus.text = "\u25CB 已禁用"
            tvMockStatus.setTextColor(0xFFFFA000.toInt())
            btnStopMock.isEnabled = false
            btnStopMock.text = "停止Mock"
        } else {
            tvMockStatus.text = "\u25CB 未配置"
            tvMockStatus.setTextColor(0xFF999999.toInt())
            btnStopMock.isEnabled = false
            btnStopMock.text = "停止Mock"
        }
    }

    // ========== Diagnostics ==========

    private fun showDiagnostics() {
        val report = DiagnosticsEngine.runDiagnostics(this, selectedAppPkg.ifEmpty { "com.ss.android.lark" })

        val msg = buildString {
            append("=== 诊断报告 ===\n\n")
            append("Root状态: ${if (report.rootDetected) "✅ 已Root" else "⚠️ 未Root"}\n")
            append("Magisk: ${if (report.magiskInstalled) "✅" else "❌"}\n")
            append("LSPosed: ${if (report.lsposedInstalled) "✅" else "❌"}\n")
            append("Xposed模块: ${if (report.xposedModuleActive) "✅ 已激活" else "❌ 未激活"}\n")
            append("配置文件: ${if (report.configExists) "✅ 存在" else "❌ 不存在"}\n")
            append("配置有效: ${if (report.configValid) "✅" else "❌"}\n")
            append("目标App: ${if (report.targetAppInstalled) "✅ 已安装" else "❌ 未安装"}\n")
            if (report.developerOptionsEnabled != null) {
                append("开发者选项: ${if (report.developerOptionsEnabled) "⚠️ 开启" else "✅ 关闭"}\n")
            }
            if (report.adbEnabled != null) {
                append("USB调试: ${if (report.adbEnabled) "⚠️ 开启" else "✅ 关闭"}\n")
            }
            if (report.suspiciousApps.isNotEmpty()) {
                append("\n可疑App: ${report.suspiciousApps.joinToString(", ")}\n")
            }
            append("\n综合评分: ${report.overallScore}/100 (${report.riskLevel})\n")
            if (report.recommendations.isNotEmpty()) {
                append("\n建议:\n${report.recommendations.joinToString("\n") { "• $it" }}\n")
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("\uD83D\uDD27 诊断面板")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .show()
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
            try { pm.getPackageInfo(pkg, 0); installedApps.add(AppInfo(pkg, name)) }
            catch (_: Exception) {}
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
            .setTitle("选择目标应用")
            .setItems(names) { _, which ->
                val app = installedApps[which]
                selectedAppPkg = app.packageName
                selectedAppName = app.appName
                tvSelectedApp.text = "\u2713 ${app.appName}"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========== 位置捕获 & 保存 ==========

    @SuppressLint("MissingPermission")
    private fun captureCurrentLocation() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            checkPermissions()
            return
        }
        currentLocation?.let { loc ->
            showSaveDialog(loc.latitude, loc.longitude)
            return
        }
        Toast.makeText(this, "正在获取位置...", Toast.LENGTH_SHORT).show()
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        currentLocation = loc
                        showSaveDialog(loc.latitude, loc.longitude)
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

        view.findViewById<TextView>(R.id.tv_map_pick).setOnClickListener {
            val curLat = etLat.text.toString().toDoubleOrNull() ?: lat
            val curLng = etLng.text.toString().toDoubleOrNull() ?: lng
            openMapPickerAt(curLat, curLng)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("保存标记点")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString()?.ifBlank { generateCryptoName() }
                    ?: return@setPositiveButton
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

    // ========== 地图选点 ==========

    private fun openMapPicker() {
        currentLocation?.let { openMapPickerAt(it.latitude, it.longitude) }
            ?: openMapPickerAt(39.9042, 116.4074)
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

        if (tempWifiSsid.isEmpty()) {
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                val conn = wm?.connectionInfo
                if (conn != null && conn.ssid != null &&
                    conn.ssid != "<unknown ssid>" && conn.ssid != "0x"
                ) {
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
                    ?: run {
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
        val guide = """
=== 定位修改使用指南 [Root版] ===

① 前提条件:
  - 手机已解锁BL + 刷入Magisk
  - 安装LSPosed框架
  - 在LSPosed中激活本模块，作用域选择飞书

② 选择目标App:
  点击"选择App" → 选择飞书/钉钉等

③ 设置目标位置:
  点击"捕获"获取当前GPS位置
  或点击"地图选点"手动选择
  保存位置

④ 配置WiFi（可选）:
  点击"WiFi配置" → 捕获当前WiFi
  或手动输入目标WiFi信息

⑤ 写入配置并启动:
  点击已保存的位置 → 写入配置并启动App
  → Xposed模块自动在目标App进程内激活

⑥ 注意事项:
  - 修改目标位置后需重启目标App
  - 配置文件位于 /sdcard/location_mod/config.json
  - 可在LSPosed管理器中查看Hook日志
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("\uD83D\uDCD6 使用指南")
            .setMessage(guide)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun generateCryptoName(): String {
        val prefixes = listOf("地点", "位置点", "坐标", "标记", "点位", "节点")
        val suffixes = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val sdf = SimpleDateFormat("ddHHmm", Locale.getDefault())
        return "${prefixes.random()}${suffixes.random()}-${sdf.format(Date())}"
    }

    // ========== Persistence ==========

    private fun loadSavedLocations() {
        val json = getSharedPreferences("locations", 0).getString("data", null) ?: return
        try {
            val list = Gson().fromJson<List<SavedLocation>>(
                json,
                object : TypeToken<List<SavedLocation>>() {}.type
            )
            savedLocations.clear()
            savedLocations.addAll(list)
        } catch (_: Exception) {}
    }

    private fun saveLocations() {
        getSharedPreferences("locations", 0).edit()
            .putString("data", Gson().toJson(savedLocations)).apply()
    }
}
