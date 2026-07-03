package com.research.location.hook.data

import android.hardware.Sensor
import android.hardware.SensorEvent
import com.research.location.hook.util.SensorMath
import java.lang.reflect.Constructor

/**
 * Factory for constructing fake SensorEvent objects.
 */
object SensorDataBuilder {

    private var eventConstructor: Constructor<SensorEvent>? = null

    init {
        try {
            eventConstructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.java)
            eventConstructor?.isAccessible = true
        } catch (_: Exception) {}
    }

    /**
     * Build a SensorEvent for the given sensor type and current engine state.
     */
    fun buildSensorEvent(
        sensor: Sensor,
        latDeg: Double,
        lngDeg: Double,
        altitudeM: Double,
        currentSpeed: Float,
        currentBearing: Float,
        prevBearing: Float,
        dtSeconds: Double,
        walking: Boolean,
        phase: Double,
        weatherOffset: Double,
        seed: Long
    ): SensorEvent? {
        val values = when (sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (walking) SensorMath.walkingAccelerometer(phase, currentSpeed.toDouble(), seed)
                else SensorMath.stationaryAccelerometer(seed)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val bearingChange = currentBearing - prevBearing
                SensorMath.gyroscopeValues(bearingChange.toDouble(), dtSeconds, walking, seed)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                SensorMath.magnetometerValues(latDeg, lngDeg, currentBearing.toDouble(), seed)
            }
            Sensor.TYPE_PRESSURE -> {
                floatArrayOf(SensorMath.barometerValue(altitudeM, weatherOffset, seed))
            }
            Sensor.TYPE_GRAVITY -> {
                // Gravity is stable ~9.8, slight variation
                val rng = kotlin.random.Random(seed)
                floatArrayOf(
                    (rng.nextFloat() - 0.5f) * 0.05f,
                    (rng.nextFloat() - 0.5f) * 0.05f,
                    9.8f + (rng.nextFloat() - 0.5f) * 0.03f
                )
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Linear acceleration = accel - gravity → very low when still
                val rng = kotlin.random.Random(seed)
                if (walking) {
                    val amp = (currentSpeed / 1.5f).coerceIn(0.1f, 0.8f)
                    floatArrayOf(
                        (rng.nextFloat() - 0.5f) * amp * 2,
                        (rng.nextFloat() - 0.5f) * amp * 2,
                        (rng.nextFloat() - 0.5f) * amp * 0.5f
                    )
                } else {
                    floatArrayOf(
                        (rng.nextFloat() - 0.5f) * 0.02f,
                        (rng.nextFloat() - 0.5f) * 0.02f,
                        (rng.nextFloat() - 0.5f) * 0.01f
                    )
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                // Simplified rotation vector (x*sin(θ/2), y*sin(θ/2), z*sin(θ/2), cos(θ/2))
                val rng = kotlin.random.Random(seed)
                val angle = Math.toRadians(currentBearing.toDouble())
                val sinHalf = kotlin.math.sin(angle / 2).toFloat()
                val cosHalf = kotlin.math.cos(angle / 2).toFloat()
                floatArrayOf(0f, 0f, sinHalf, cosHalf).also {
                    for (i in 0..2) it[i] += (rng.nextFloat() - 0.5f) * 0.01f
                }
            }
            Sensor.TYPE_LIGHT -> {
                // Indoor office lighting
                val rng = kotlin.random.Random(seed)
                floatArrayOf(300f + rng.nextFloat() * 200f)
            }
            Sensor.TYPE_PROXIMITY -> {
                floatArrayOf(5.0f)  // far (phone on desk)
            }
            else -> return null
        }

        return buildEvent(sensor, values)
    }

    private fun buildEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        val ctor = eventConstructor ?: return null
        return try {
            val event = ctor.newInstance(values.size)
            event.sensor = sensor
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            event.timestamp = System.nanoTime()
            System.arraycopy(values, 0, event.values, 0, values.size)
            event
        } catch (_: Exception) {
            null
        }
    }

    /** Android's SensorManager constants (avoid import issues in Xposed context) */
    private object SensorManager {
        const val SENSOR_STATUS_ACCURACY_HIGH = 3
    }
}
