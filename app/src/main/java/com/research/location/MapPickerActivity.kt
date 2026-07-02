package com.research.location

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MapPickerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var lat = intent.getDoubleExtra("lat", 39.9042)
        var lng = intent.getDoubleExtra("lng", 116.4074)

        val html = assets.open("map_picker.html").bufferedReader().use { it.readText() }
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
            // 使用nominatim域名作为baseURL，允许JS fetch同源
            loadDataWithBaseURL("https://nominatim.openstreetmap.org/", htmlWithCoords, "text/html", "UTF-8", null)
        }
        setContentView(webView)
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
    }
}
