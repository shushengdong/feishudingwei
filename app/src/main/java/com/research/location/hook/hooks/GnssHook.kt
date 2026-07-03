package com.research.location.hook.hooks

import android.location.GnssStatus
import android.location.LocationManager
import com.research.location.hook.data.GnssDataBuilder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Priority 5: Hook LocationManager GnssStatus registration.
 * Injects fake satellite data to prevent "0 satellites" detection.
 */
class GnssHook : BaseHook("Gnss", priority = 5) {

    private data class GnssEntry(
        val callback: Any,       // GnssStatus.Callback or GpsStatus.Listener
        val isCallback: Boolean  // true = GnssStatus.Callback, false = GpsStatus.Listener
    )

    private val activeCallbacks = CopyOnWriteArrayList<GnssEntry>()
    private var injectThread: android.os.HandlerThread? = null
    private var injectHandler: android.os.Handler? = null

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)
        if (!config.gnss.enabled) return

        // Hook-1: registerGnssStatusCallback(GnssStatus.Callback)
        hookBefore(
            LocationManager::class.java,
            "registerGnssStatusCallback",
            GnssStatus.Callback::class.java
        ) { param ->
            val callback = param.args[0] as GnssStatus.Callback
            activeCallbacks.add(GnssEntry(callback, true))
            startInjectThread()
        }

        // Hook-2: registerGnssStatusCallback(Executor, GnssStatus.Callback)
        try {
            hookBefore(
                LocationManager::class.java,
                "registerGnssStatusCallback",
                java.util.concurrent.Executor::class.java,
                GnssStatus.Callback::class.java
            ) { param ->
                val callback = param.args[1] as GnssStatus.Callback
                activeCallbacks.add(GnssEntry(callback, true))
                startInjectThread()
            }
        } catch (_: Exception) {}

        // Hook-3: unregisterGnssStatusCallback
        try {
            hookBefore(
                LocationManager::class.java,
                "unregisterGnssStatusCallback",
                GnssStatus.Callback::class.java
            ) { param ->
                val callback = param.args[0] as GnssStatus.Callback
                activeCallbacks.removeAll { it.callback === callback && it.isCallback }
            }
        } catch (_: Exception) {}

        // Hook-4: addGpsStatusListener (older API)
        try {
            hookBefore(
                LocationManager::class.java,
                "addGpsStatusListener",
                android.location.GpsStatus.Listener::class.java
            ) { param ->
                val listener = param.args[0] as android.location.GpsStatus.Listener
                activeCallbacks.add(GnssEntry(listener, false))
                startInjectThread()
            }
        } catch (_: Exception) {}

        // Hook-5: removeGpsStatusListener
        try {
            hookBefore(
                LocationManager::class.java,
                "removeGpsStatusListener",
                android.location.GpsStatus.Listener::class.java
            ) { param ->
                val listener = param.args[0] as android.location.GpsStatus.Listener
                activeCallbacks.removeAll { it.callback === listener && !it.isCallback }
            }
        } catch (_: Exception) {}

        log("Installed — GNSS spoofing active")
    }

    private fun startInjectThread() {
        if (injectThread != null && injectThread!!.isAlive) return
        injectThread = android.os.HandlerThread("LocMod-Gnss").apply { start() }
        injectHandler = android.os.Handler(injectThread!!.looper)

        injectHandler!!.post(object : Runnable {
            override fun run() {
                injectGnssData()
                injectHandler?.postDelayed(this, 1000) // every 1 second
            }
        })
    }

    private fun injectGnssData() {
        val e = engine ?: return
        val gcfg = config?.gnss ?: return
        val snapshot = activeCallbacks.toList()

        for (entry in snapshot) {
            try {
                if (entry.isCallback) {
                    val status = GnssDataBuilder.buildGnssStatus(
                        latDeg = e.currentFrame.jitteredLat,
                        lngDeg = e.currentFrame.jitteredLng,
                        satelliteCount = gcfg.satelliteCount[0]..gcfg.satelliteCount[1],
                        constellationMix = gcfg.constellationMix,
                        cn0Range = gcfg.cn0Range[0]..gcfg.cn0Range[1],
                        seed = e.currentFrame.frameSeed
                    )
                    if (status != null) {
                        val callback = entry.callback as GnssStatus.Callback
                        callback.onSatelliteStatusChanged(status)
                    }
                } else {
                    val listener = entry.callback as android.location.GpsStatus.Listener
                    val gpsStatus = GnssDataBuilder.buildGpsStatus(
                        latDeg = e.currentFrame.jitteredLat,
                        lngDeg = e.currentFrame.jitteredLng,
                        count = gcfg.satelliteCount[0]..
                                gcfg.satelliteCount[1],
                        seed = e.currentFrame.frameSeed
                    )
                    if (gpsStatus != null) {
                        listener.onGpsStatusChanged(android.location.GpsStatus.GPS_EVENT_SATELLITE_STATUS)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
