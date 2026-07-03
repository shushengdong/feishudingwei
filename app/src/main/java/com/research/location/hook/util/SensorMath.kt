package com.research.location.hook.util

import kotlin.math.*

/**
 * Physics formulas for sensor data simulation.
 * All outputs are in standard Android sensor units.
 */
object SensorMath {

    /**
     * Simulate accelerometer readings for walking.
     * @param phase walking cycle phase [0.0, 1.0)
     * @param speedMps current walking speed in m/s
     * @return float[3] = {x, y, z} in m/s²
     */
    fun walkingAccelerometer(phase: Double, speedMps: Double, seed: Long): FloatArray {
        val rng = kotlin.random.Random(seed)
        val amp = (speedMps / 1.5).coerceIn(0.3, 1.2)

        // X-axis: lateral sway
        val ax = (amp * 0.3 * sin(phase * 2 * PI)).toFloat() +
                (rng.nextFloat() - 0.5f) * 0.04f

        // Y-axis: forward acceleration/deceleration per gait phase
        val ay = when {
            phase < 0.3 -> (amp * -0.5 * sin(phase / 0.3 * PI)).toFloat()   // heel strike
            phase < 0.7 -> (amp * 0.8 * sin((phase - 0.3) / 0.4 * PI)).toFloat() // push-off
            else -> (amp * 0.2 * sin((phase - 0.7) / 0.3 * PI)).toFloat()   // swing
        } + (rng.nextFloat() - 0.5f) * 0.06f

        // Z-axis: vertical bounce
        val az = (9.8f + amp * 0.5f * sin(phase * 2 * PI + PI / 2).toFloat()) +
                (rng.nextFloat() - 0.5f) * 0.04f

        return floatArrayOf(ax, ay, az)
    }

    /**
     * Simulate stationary accelerometer (phone on desk).
     */
    fun stationaryAccelerometer(seed: Long): FloatArray {
        val rng = kotlin.random.Random(seed)
        return floatArrayOf(
            (rng.nextFloat() - 0.5f) * 0.04f,
            (rng.nextFloat() - 0.5f) * 0.04f,
            9.8f + (rng.nextFloat() - 0.5f) * 0.02f
        )
    }

    /**
     * Simulate gyroscope readings (rad/s).
     * @param bearingChangeDeg bearing change in degrees since last frame
     * @param dtSeconds time delta in seconds
     * @param walking whether the user is walking
     */
    fun gyroscopeValues(
        bearingChangeDeg: Double,
        dtSeconds: Double,
        walking: Boolean,
        seed: Long
    ): FloatArray {
        val rng = kotlin.random.Random(seed)
        // Z-axis rotation matches bearing change
        val gz = (Math.toRadians(bearingChangeDeg) / dtSeconds).toFloat()

        val noiseScale = if (walking) 0.04f else 0.01f
        return floatArrayOf(
            (rng.nextFloat() - 0.5f) * noiseScale * 2,
            (rng.nextFloat() - 0.5f) * noiseScale * 2,
            gz + (rng.nextFloat() - 0.5f) * 0.01f
        )
    }

    /**
     * Simulate magnetometer readings (μT).
     * @param latDeg latitude for inclination
     * @param lngDeg longitude for declination
     * @param bearingDeg current device bearing
     */
    fun magnetometerValues(
        latDeg: Double,
        lngDeg: Double,
        bearingDeg: Double,
        seed: Long
    ): FloatArray {
        val rng = kotlin.random.Random(seed)
        val fieldStrength = 50.0f  // μT, total field

        val inclination = Math.toRadians(GeoMath.magneticInclinationDeg(latDeg))
        val declination = Math.toRadians(GeoMath.magneticDeclinationDeg(latDeg, lngDeg))

        val horizontal = (fieldStrength * cos(inclination)).toFloat()
        val vertical = (fieldStrength * sin(inclination)).toFloat()

        val magBearing = Math.toRadians(bearingDeg) - declination

        val mx = (horizontal * sin(magBearing)).toFloat() +
                (rng.nextFloat() - 0.5f) * 2.0f
        val my = (horizontal * cos(magBearing)).toFloat() +
                (rng.nextFloat() - 0.5f) * 2.0f
        val mz = vertical + (rng.nextFloat() - 0.5f) * 1.0f

        return floatArrayOf(mx, my, mz)
    }

    /**
     * Simulate barometer (hPa) from altitude.
     */
    fun barometerValue(altitudeM: Double, weatherOffset: Double, seed: Long): Float {
        val rng = kotlin.random.Random(seed)
        return (GeoMath.pressureFromAltitude(altitudeM) + weatherOffset +
                (rng.nextFloat() - 0.5f) * 0.2f).toFloat()
    }

    /**
     * Walking speed with natural variation.
     * @param baseSpeed target speed in m/s
     * @param seed deterministic seed
     */
    fun naturalSpeed(baseSpeed: Double, seed: Long): Double {
        val rng = kotlin.random.Random(seed)
        // Random pause (10% chance)
        if (rng.nextDouble() < 0.10) return 0.0

        // Gaussian variation around base speed
        val variation = NoiseGenerator.gaussian1D(seed, baseSpeed * 0.3)
        return (baseSpeed + variation).coerceIn(0.0, 2.5)
    }

    /**
     * Realistic GPS accuracy with occasional outliers.
     * @param baseRange normal accuracy range in meters
     * @param seed deterministic seed
     * @return accuracy in meters
     */
    fun realisticAccuracy(baseRange: ClosedFloatingPointRange<Double>, seed: Long): Float {
        val rng = kotlin.random.Random(seed)
        val roll = rng.nextDouble()
        return when {
            roll < 0.05 -> (baseRange.endInclusive * 1.5).toFloat()  // occasional bad fix
            roll < 0.15 -> (baseRange.start * 0.6).toFloat()         // occasional good fix
            else -> (baseRange.start + rng.nextDouble() *
                    (baseRange.endInclusive - baseRange.start)).toFloat()
        }
    }
}
