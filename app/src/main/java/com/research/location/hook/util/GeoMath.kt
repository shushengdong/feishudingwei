package com.research.location.hook.util

import kotlin.math.*

/**
 * Geographic math utilities shared by all hook modules.
 */
object GeoMath {
    const val EARTH_RADIUS_M = 6_371_000.0
    const val METERS_PER_DEG_LAT = 111_320.0

    /** Meters per degree of longitude at given latitude */
    fun metersPerDegLng(latDeg: Double): Double =
        METERS_PER_DEG_LAT * cos(Math.toRadians(latDeg))

    /** Convert meter offsets to degree deltas */
    fun metersToDegrees(
        latDeg: Double,
        dNorthM: Double,
        dEastM: Double
    ): Pair<Double, Double> {
        val dLat = dNorthM / METERS_PER_DEG_LAT
        val dLng = dEastM / metersPerDegLng(latDeg)
        return dLat to dLng
    }

    /** Haversine distance in meters */
    fun distanceMeters(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Bearing from point A to point B (degrees, 0=North) */
    fun bearingDegrees(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /**
     * Magnetic inclination (dip angle) at given latitude.
     * Simplified dipole model: tan(I) = 2 * tan(lat)
     */
    fun magneticInclinationDeg(latDeg: Double): Double =
        Math.toDegrees(atan(2.0 * tan(Math.toRadians(latDeg))))

    /**
     * Approximate magnetic declination for Chinese cities.
     * Positive = east of true north.
     */
    fun magneticDeclinationDeg(latDeg: Double, lngDeg: Double): Double = when {
        // Northeast China
        latDeg > 40 && lngDeg > 115 -> -7.0  // Harbin/Changchun
        // Beijing area
        latDeg > 39 && lngDeg in 115.0..117.0 -> -6.5
        // Shanghai area
        latDeg in 30.0..32.0 && lngDeg in 120.0..122.0 -> -5.0
        // Guangzhou/Shenzhen
        latDeg < 24 && lngDeg in 112.0..115.0 -> -2.0
        // Chengdu/Chongqing
        latDeg in 28.0..32.0 && lngDeg in 103.0..107.0 -> -2.5
        // Default: China average
        else -> -4.0
    }

    /**
     * Barometric pressure from altitude using standard atmosphere model.
     * @param altitudeM altitude in meters
     * @return pressure in hPa
     */
    fun pressureFromAltitude(altitudeM: Double): Double {
        val tempLapse = 0.0065      // K/m
        val seaLevelTemp = 288.15   // K
        val seaLevelPressure = 1013.25 // hPa
        val gravity = 9.80665
        val molarMass = 0.0289644
        val gasConstant = 8.314

        val exponent = gravity * molarMass / (gasConstant * tempLapse)
        return seaLevelPressure * (1 - tempLapse * altitudeM / seaLevelTemp).pow(exponent)
    }

    /** City name -> typical altitude (meters) */
    fun cityAltitude(cityName: String): Double = when {
        cityName.contains("北京") -> 50.0
        cityName.contains("上海") -> 4.0
        cityName.contains("广州") -> 20.0
        cityName.contains("深圳") -> 15.0
        cityName.contains("成都") -> 500.0
        cityName.contains("重庆") -> 250.0
        cityName.contains("杭州") -> 10.0
        cityName.contains("武汉") -> 25.0
        cityName.contains("南京") -> 15.0
        cityName.contains("西安") -> 400.0
        cityName.contains("拉萨") -> 3650.0
        else -> 30.0
    }
}
