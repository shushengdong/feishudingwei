package com.research.location.hook.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import com.research.location.hook.data.LocationBuilder

/**
 * Priority 2: Hook all LocationManager entry points.
 *
 * Intercepts getLastKnownLocation, requestLocationUpdates (all overloads),
 * and removeUpdates. Returns clean Location objects that pass all checks.
 */
class LocationHook : BaseHook("Location", priority = 2) {

    private data class ListenerEntry(
        val provider: String,
        val listener: LocationListener,
        val minTimeMs: Long
    )

    private val activeListeners = mutableMapOf<LocationListener, ListenerEntry>()
    private var injectThread: android.os.HandlerThread? = null
    private var injectHandler: android.os.Handler? = null

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        // Hook-1: getLastKnownLocation(String)
        hookReplace(
            LocationManager::class.java, "getLastKnownLocation",
            String::class.java
        ) { param ->
            val provider = param.args[0] as String
            buildLocationForProvider(provider)
        }

        // Hook-2: requestLocationUpdates(String, long, float, LocationListener)
        hook(
            LocationManager::class.java, "requestLocationUpdates",
            String::class.java, Long::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!, LocationListener::class.java,
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val provider = param.args[0] as String
                    val minTime = param.args[1] as Long
                    val listener = param.args[3] as LocationListener
                    registerListener(provider, listener, minTime)
                }
            }
        )

        // Hook-3: requestLocationUpdates(String, long, float, LocationListener, Looper)
        hook(
            LocationManager::class.java, "requestLocationUpdates",
            String::class.java, Long::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!, LocationListener::class.java,
            Looper::class.java,
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val provider = param.args[0] as String
                    val minTime = param.args[1] as Long
                    val listener = param.args[3] as LocationListener
                    registerListener(provider, listener, minTime)
                }
            }
        )

        // Hook-4: requestLocationUpdates(LocationRequest, LocationListener, Looper) — newer API
        try {
            hook(
                LocationManager::class.java, "requestLocationUpdates",
                LocationRequest::class.java, LocationListener::class.java,
                Looper::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val request = param.args[0] as LocationRequest
                        val listener = param.args[1] as LocationListener
                        val provider = "fused" // LocationRequest doesn't specify provider
                        registerListener(provider, listener, request.intervalMillis)
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook-5: removeUpdates(LocationListener)
        hookBefore(
            LocationManager::class.java, "removeUpdates",
            LocationListener::class.java
        ) { param ->
            val listener = param.args[0] as LocationListener
            activeListeners.remove(listener)
            if (activeListeners.isEmpty()) stopInjectThread()
        }

        // Start injection thread
        startInjectThread()

        log("Installed — all LocationManager entry points hooked")
    }

    private fun registerListener(provider: String, listener: LocationListener, minTime: Long) {
        activeListeners[listener] = ListenerEntry(provider, listener, minTime.coerceAtLeast(500))
        startInjectThread()
    }

    private fun startInjectThread() {
        if (injectThread != null && injectThread!!.isAlive) return
        injectThread = android.os.HandlerThread("LocMod-Injector").apply { start() }
        injectHandler = android.os.Handler(injectThread!!.looper)

        injectHandler!!.post(object : Runnable {
            override fun run() {
                injectToAllListeners()
                injectHandler?.postDelayed(this, 1000) // inject every second
            }
        })
    }

    private fun stopInjectThread() {
        injectHandler?.removeCallbacksAndMessages(null)
        injectThread?.quitSafely()
        injectThread = null
        injectHandler = null
    }

    private fun injectToAllListeners() {
        val e = engine ?: return
        val snapshot = activeListeners.toMap()

        for ((listener, entry) in snapshot) {
            try {
                val loc = buildLocationForProvider(entry.provider)
                listener.onLocationChanged(loc)
            } catch (_: Exception) {
                // Listener might be invalid, remove it
                activeListeners.remove(listener)
            }
        }
    }

    private fun buildLocationForProvider(provider: String): Location? {
        val e = engine ?: return null
        val frame = e.currentFrame

        return when (provider.lowercase()) {
            "gps" -> LocationBuilder.buildGps(
                lat = frame.jitteredLat,
                lng = frame.jitteredLng,
                accuracy = frame.gpsAccuracy,
                altitude = frame.altitude,
                speed = frame.speed,
                bearing = frame.bearing,
                timeMs = frame.timestampMs
            )
            "network" -> LocationBuilder.buildNetwork(
                lat = frame.jitteredLat + frame.networkOffsetLat,
                lng = frame.jitteredLng + frame.networkOffsetLng,
                accuracy = frame.networkAccuracy,
                timeMs = frame.timestampMs + 50  // slightly later than GPS
            )
            "passive" -> LocationBuilder.buildPassive(
                lat = frame.jitteredLat + frame.passiveOffsetLat,
                lng = frame.jitteredLng + frame.passiveOffsetLng,
                accuracy = frame.passiveAccuracy,
                timeMs = frame.timestampMs + (Math.random() * 100).toLong()
            )
            "fused" -> LocationBuilder.buildFused(
                lat = frame.jitteredLat,
                lng = frame.jitteredLng,
                accuracy = frame.fusedAccuracy,
                altitude = frame.altitude,
                speed = frame.speed,
                bearing = frame.bearing,
                timeMs = frame.timestampMs + 25
            )
            else -> null
        }
    }
}
