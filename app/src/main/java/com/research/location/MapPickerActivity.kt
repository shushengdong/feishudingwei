package com.research.location

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray

class MapPickerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var lat = intent.getDoubleExtra("lat", 39.9042)
        var lng = intent.getDoubleExtra("lng", 116.4074)

        // 读取 HTML 内容
        val html = assets.open("map_picker.html").bufferedReader().use { it.readText() }
        // 注入初始坐标
        val htmlWithCoords = html.replace("INIT_LAT", lat.toString()).replace("INIT_LNG", lng.toString())

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setGeolocationEnabled(true)
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "LocationPicker/2.0 (Android)"
            }
            addJavascriptInterface(JSInterface(), "Android")
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            // ★ 关键Fix: baseURL设为nominatim域名，允许fetch同源请求
            loadDataWithBaseURL("https://nominatim.openstreetmap.org/", htmlWithCoords, "text/html", "UTF-8", null)
        }
        setContentView(webView)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class JSInterface {
        @JavascriptInterface
        fun onLocationSelected(lat: Double, lng: Double) {
            runOnUiThread {
                intent.putExtra("lat", lat)
                intent.putExtra("lng", lng)
                setResult(RESULT_OK, intent)
                finish()
            }
        }

        @JavascriptInterface
        fun onCancel() {
            runOnUiThread {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        /** 反向地理编码：将坐标转为地址文本 */
        @JavascriptInterface
        fun reverseGeocode(lat: Double, lng: Double) {
            scope.launch(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&accept-language=zh")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "LocationPicker/2.0")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(text)
                    val displayName = json.optString("display_name", "")
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("showAddress('${displayName.replace("'", "\\'")}')", null)
                    }
                } catch (e: Exception) {
                    Log.w("MapPicker", "Reverse geocode failed", e)
                }
            }
        }
    }
}
