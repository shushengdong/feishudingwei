package com.research.location.hook.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.research.location.hook.data.SensorDataBuilder
import com.research.location.hook.util.NoiseGenerator

/**
 * Priority 6: Hook SensorManager to inject fake sensor readings.
 * Covers accelerometer, gyroscope, magnetometer, barometer, etc.
 */
class SensorHook : BaseHook("Sensor", priority = 6) {

    private data class ListenerEntry(
        val listener: SensorEventListener,
        val sensor: Sensor,
        val samplingPeriodUs: Int
    )

    private val activeListeners = mutableMapOf<SensorEventListener, MutableList<ListenerEntry>>()
    private var injectThread: android.os.HandlerThread? = null
    private var injectHandler: android.os.Handler? = null

    // Supported sensor types
    private val supportedTypes = setOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PROXIMITY
    )

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)
        if (!config.sensor.enabled) return

        // Check which sensors are enabled in config
        val enabledSensorTypes = mutableSetOf<Int>()
        with(config.sensor) {
            if (accelerometer) enabledSensorTypes.add(Sensor.TYPE_ACCELEROMETER)
            if (gyroscope) enabledSensorTypes.add(Sensor.TYPE_GYROSCOPE)
            if (magnetometer) enabledSensorTypes.add(Sensor.TYPE_MAGNETIC_FIELD)
            if (barometer) enabledSensorTypes.add(Sensor.TYPE_PRESSURE)
            if (gravity) enabledSensorTypes.add(Sensor.TYPE_GRAVITY)
            if (linearAcceleration) enabledSensorTypes.add(Sensor.TYPE_LINEAR_ACCELERATION)
            if (rotationVector) enabledSensorTypes.add(Sensor.TYPE_ROTATION_VECTOR)
            if (light) enabledSensorTypes.add(Sensor.TYPE_LIGHT)
            if (proximity) enabledSensorTypes.add(Sensor.TYPE_PROXIMITY)
        }

        if (enabledSensorTypes.isEmpty()) return

        // Hook: registerListener (3 overloads)
        hookBefore(
            SensorManager::class.java, "registerListener",
            SensorEventListener::class.java, Sensor::class.java, Int::class.javaPrimitiveType!!
        ) { param ->
            register(param)
        }

        try {
            hookBefore(
                SensorManager::class.java, "registerListener",
                SensorEventListener::class.java, Sensor::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            ) { param -> register(param) }
        } catch (_: Exception) {}

        try {
            hookBefore(
                SensorManager::class.java, "registerListener",
                SensorEventListener::class.java, Sensor::class.java,
                Int::class.javaPrimitiveType!!, android.os.Handler::class.java
            ) { param -> register(param) }
        } catch (_: Exception) {}

        // Hook: unregisterListener
        hookBefore(
            SensorManager::class.java, "unregisterListener",
            SensorEventListener::class.java
        ) { param ->
            val listener = param.args[0] as SensorEventListener
            activeListeners.remove(listener)
        }

        try {
            hookBefore(
                SensorManager::class.java, "unregisterListener",
                SensorEventListener::class.java, Sensor::class.java
            ) { param ->
                val listener = param.args[0] as SensorEventListener
                val sensor = param.args[1] as Sensor
                activeListeners[listener]?.removeAll { it.sensor === sensor }
                if (activeListeners[listener]?.isEmpty() == true) {
                    activeListeners.remove(listener)
                }
            }
        } catch (_: Exception) {}

        // Start injection thread
        if (enabledSensorTypes.isNotEmpty()) startInjectThread(enabledSensorTypes)

        log("Installed — Sensor spoofing active for ${enabledSensorTypes.size} types")
    }

    private fun register(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        val listener = param.args[0] as SensorEventListener
        val sensor = param.args[1] as Sensor
        val period = param.args[2] as Int

        if (sensor.type !in supportedTypes) return

        val entry = ListenerEntry(listener, sensor, period)
        activeListeners.getOrPut(listener) { mutableListOf() }.add(entry)
    }

    private fun startInjectThread(enabledTypes: Set<Int>) {
        if (injectThread != null && injectThread!!.isAlive) return
        injectThread = android.os.HandlerThread("LocMod-Sensor").apply { start() }
        injectHandler = android.os.Handler(injectThread!!.looper)

        val gaitSim = NoiseGenerator.GaitSimulator(1.8)
        val weatherOffset = 0.0  // could be randomly generated once per day

        injectHandler!!.post(object : Runnable {
            override fun run() {
                injectSensorData(gaitSim, weatherOffset)
                injectHandler?.postDelayed(this, 100) // 100ms = 10Hz
            }
        })
    }

    private fun injectSensorData(gait: NoiseGenerator.GaitSimulator, weatherOffset: Double) {
        val e = engine ?: return
        val frame = e.currentFrame
        val phase = gait.phase()
        val walking = frame.speed > 0.3f
        val snapshot = activeListeners.toMap()

        for ((listener, entries) in snapshot) {
            for (entry in entries) {
                try {
                    val event = SensorDataBuilder.buildSensorEvent(
                        sensor = entry.sensor,
                        latDeg = frame.jitteredLat,
                        lngDeg = frame.jitteredLng,
                        altitudeM = frame.altitude,
                        currentSpeed = frame.speed,
                        currentBearing = frame.bearing,
                        prevBearing = e.previousFrame?.bearing ?: frame.bearing,
                        dtSeconds = 0.1,
                        walking = walking,
                        phase = phase,
                        weatherOffset = weatherOffset,
                        seed = frame.frameSeed xor entry.sensor.type.toLong()
                    )
                    if (event != null) {
                        listener.onSensorChanged(event)
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
