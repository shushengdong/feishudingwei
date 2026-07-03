package com.research.location.hook.data

import android.location.GnssStatus
import android.location.GpsSatellite
import android.location.GpsStatus
import android.os.Build
import java.lang.reflect.Constructor

/**
 * Factory for constructing fake GnssStatus and GpsStatus objects.
 * Xposed modules have unrestricted hidden API access.
 */
object GnssDataBuilder {

    /**
     * Build a fake GnssStatus with the given satellite count.
     * Uses reflection since GnssStatus.Builder is @hide.
     * @param satCount total satellites to simulate
     * @param cn0Range signal-to-noise ratio range
     * @param constellationMix distribution of constellation types
     * @param seed deterministic seed
     */
    fun buildGnssStatus(
        latDeg: Double = 0.0,
        lngDeg: Double = 0.0,
        satelliteCount: ClosedRange<Int> = 8..15,
        constellationMix: Map<String, Int> = mapOf("GPS" to 35, "BeiDou" to 35),
        cn0Range: ClosedFloatingPointRange<Double> = 25.0..45.0,
        seed: Long = 0
    ): GnssStatus? {
        val rng = kotlin.random.Random(seed)
        val satCount = rng.nextInt(satelliteCount.start, satelliteCount.endInclusive + 1)

        return try {
            val builderClass = Class.forName("android.location.GnssStatus\$Builder")
            val builder: Any = builderClass.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()

            val addSatelliteMethod = builderClass.getDeclaredMethod(
                "addSatellite",
                Int::class.java,    // constellationType
                Int::class.java,    // svid
                Float::class.java,  // cn0DbHz
                Float::class.java,  // elevation
                Float::class.java,  // azimuth
                Boolean::class.java,// hasEphemeris
                Boolean::class.java,// hasAlmanac
                Boolean::class.java,// usedInFix
                Boolean::class.java,// hasBasebandCn0
                Float::class.java,  // basebandCn0DbHz
                Float::class.java   // carrierFrequencyHz (Android 12+)
            )
            addSatelliteMethod.isAccessible = true

            // Build constellation list from mix
            val constellations = mutableListOf<Int>()
            for ((name, pct) in constellationMix) {
                val type = constellationType(name)
                val count = (satCount * pct / 100).coerceAtLeast(1)
                repeat(count) { constellations.add(type) }
            }
            // Shuffle to mix types
            constellations.shuffle(rng)

            for (i in 0 until satCount.coerceAtMost(constellations.size)) {
                val cn0 = (cn0Range.start + rng.nextDouble() *
                        (cn0Range.endInclusive - cn0Range.start)).toFloat()
                val azimuth = (rng.nextFloat() * 360f)
                val elevation = (10f + rng.nextFloat() * 75f)  // 10-85 degrees
                val svid = (1 + rng.nextInt(32))  // 1-32
                val used = i < (satCount * 0.6).toInt()  // 60% used in fix

                try {
                    addSatelliteMethod.invoke(
                        builder,
                        constellations[i.coerceAtMost(constellations.size - 1)],
                        svid,
                        cn0,
                        elevation,
                        azimuth,
                        true,   // hasEphemeris
                        true,   // hasAlmanac
                        used,   // usedInFix
                        false,  // hasBasebandCn0
                        0f,     // basebandCn0DbHz
                        1_575_420_000_000f  // L1 frequency in micro-Hz (1.57542 GHz)
                    )
                } catch (_: Exception) {
                    // Try older 10-param version (Android 11-)
                    try {
                        val addMethod10 = builderClass.getDeclaredMethod(
                            "addSatellite",
                            Int::class.java, Int::class.java,
                            Float::class.java, Float::class.java, Float::class.java,
                            Boolean::class.java, Boolean::class.java, Boolean::class.java,
                            Boolean::class.java, Float::class.java
                        )
                        addMethod10.isAccessible = true
                        addMethod10.invoke(
                            builder,
                            constellations[i.coerceAtMost(constellations.size - 1)],
                            svid, cn0, elevation, azimuth,
                            true, true, used, false, 0f
                        )
                    } catch (_: Exception) {
                        // Skip this satellite
                    }
                }
            }

            val buildMethod = builderClass.getDeclaredMethod("build")
            buildMethod.isAccessible = true
            buildMethod.invoke(builder) as? GnssStatus
        } catch (e: Exception) {
            android.util.Log.e("GnssBuilder", "Failed to build GnssStatus: ${e.message}")
            null
        }
    }

    /**
     * Build fake GpsStatus (older API, Android < 9).
     */
    fun buildGpsStatus(
        latDeg: Double = 0.0,
        lngDeg: Double = 0.0,
        count: ClosedRange<Int> = 8..15,
        seed: Long = 0
    ): GpsStatus? {
        val rng = kotlin.random.Random(seed)
        val satCount = rng.nextInt(count.start, count.endInclusive + 1)

        return try {
            val ctor: Constructor<GpsStatus> = GpsStatus::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val gpsStatus = ctor.newInstance()

            val satellitesField = GpsStatus::class.java.getDeclaredField("mSatellites")
            satellitesField.isAccessible = true

            val satellites = (0 until satCount).map { i ->
                val sat = GpsSatellite::class.java.getDeclaredConstructor(
                    Int::class.java
                ).apply { isAccessible = true }.newInstance(1 + i % 32)

                // Set fields
                GpsSatellite::class.java.getDeclaredField("mValid").apply {
                    isAccessible = true; setBoolean(sat, true)
                }
                GpsSatellite::class.java.getDeclaredField("mSnr").apply {
                    isAccessible = true; setFloat(sat, 25f + rng.nextFloat() * 20f)
                }
                GpsSatellite::class.java.getDeclaredField("mElevation").apply {
                    isAccessible = true; setFloat(sat, 10f + rng.nextFloat() * 75f)
                }
                GpsSatellite::class.java.getDeclaredField("mAzimuth").apply {
                    isAccessible = true; setFloat(sat, rng.nextFloat() * 360f)
                }
                GpsSatellite::class.java.getDeclaredField("mHasEphemeris").apply {
                    isAccessible = true; setBoolean(sat, true)
                }
                GpsSatellite::class.java.getDeclaredField("mHasAlmanac").apply {
                    isAccessible = true; setBoolean(sat, true)
                }
                GpsSatellite::class.java.getDeclaredField("mUsedInFix").apply {
                    isAccessible = true; setBoolean(sat, i < satCount * 0.6)
                }
                sat
            }

            try {
                satellitesField.set(gpsStatus, satellites.toTypedArray())
                val countField = GpsStatus::class.java.getDeclaredField("mSatelliteCount")
                countField.isAccessible = true
                countField.setInt(gpsStatus, satCount)
            } catch (_: Exception) {
                // Array vs Iterable; try Iterable
                try {
                    satellitesField.set(gpsStatus, satellites)
                } catch (_: Exception) {}
            }

            gpsStatus
        } catch (e: Exception) {
            android.util.Log.e("GpsBuilder", "Failed to build GpsStatus: ${e.message}")
            null
        }
    }

    private fun constellationType(name: String): Int = when (name.uppercase()) {
        "GPS" -> GnssStatus.CONSTELLATION_GPS          // 1
        "GLONASS" -> GnssStatus.CONSTELLATION_GLONASS  // 3
        "BEIDOU" -> GnssStatus.CONSTELLATION_BEIDOU    // 5
        "GALILEO" -> GnssStatus.CONSTELLATION_GALILEO  // 6
        "QZSS" -> GnssStatus.CONSTELLATION_QZSS        // 4
        "IRNSS" -> GnssStatus.CONSTELLATION_IRNSS      // 7
        "SBAS" -> GnssStatus.CONSTELLATION_SBAS        // 2
        else -> GnssStatus.CONSTELLATION_GPS
    }
}
