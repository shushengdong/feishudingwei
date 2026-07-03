package com.research.location.hook.data

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * Factory for constructing Location objects with isFromMockProvider() = false.
 *
 * Critical: Using new Location(provider) constructor produces Location objects
 * that are NOT mock-flagged. This is the key advantage over addTestProvider().
 */
object LocationBuilder {

    /**
     * Build a GPS-quality Location.
     */
    fun buildGps(
        lat: Double,
        lng: Double,
        accuracy: Float,
        altitude: Double,
        speed: Float,
        bearing: Float,
        timeMs: Long
    ): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat
        longitude = lng
        this.accuracy = accuracy
        this.altitude = altitude
        this.speed = speed
        this.bearing = bearing
        this.time = timeMs
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        // Extras: pretend to have reasonable satellite data
        extras = android.os.Bundle().apply {
            putInt("satellites", 8 + (timeMs % 8).toInt())
        }
    }

    /**
     * Build a Network-quality Location (less precise than GPS).
     */
    fun buildNetwork(
        lat: Double,
        lng: Double,
        accuracy: Float,
        timeMs: Long
    ): Location = Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude = lat
        longitude = lng
        this.accuracy = accuracy
        // Network location has no speed/bearing/altitude typically
        hasSpeed = false
        hasBearing = false
        hasAltitude = false
        this.time = timeMs
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    /**
     * Build a Passive location (simulates data from other apps' location requests).
     * Less precise, slightly different coordinates to simulate independent source.
     */
    fun buildPassive(
        lat: Double,
        lng: Double,
        accuracy: Float,
        timeMs: Long
    ): Location = Location(LocationManager.PASSIVE_PROVIDER).apply {
        latitude = lat
        longitude = lng
        this.accuracy = accuracy
        hasSpeed = false
        hasBearing = false
        hasAltitude = false
        this.time = timeMs
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    /**
     * Build a Fused location (composite of GPS + Network).
     */
    fun buildFused(
        lat: Double,
        lng: Double,
        accuracy: Float,
        altitude: Double,
        speed: Float,
        bearing: Float,
        timeMs: Long
    ): Location = Location("fused").apply {
        latitude = lat
        longitude = lng
        this.accuracy = accuracy
        this.altitude = altitude
        this.speed = speed
        this.bearing = bearing
        this.time = timeMs
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}
