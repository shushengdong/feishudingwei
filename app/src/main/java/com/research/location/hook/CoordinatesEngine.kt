package com.research.location.hook

import com.research.location.hook.util.GeoMath
import com.research.location.hook.util.NoiseGenerator
import com.research.location.hook.util.SensorMath
import kotlin.math.*
import kotlin.random.Random

/**
 * Single source of truth for ALL modules.
 *
 * Every hook module reads from this engine to ensure
 * WiFi/BTS/GNSS/Sensor/Location data is self-consistent.
 *
 * Extensibility: new data types (e.g., Bluetooth beacons) add
 * provider methods here.
 */
class CoordinatesEngine(private val config: MockConfig) {

    // ---- Current dynamic state ----
    @Volatile var currentLat: Double = config.location.lat
    @Volatile var currentLng: Double = config.location.lng
    @Volatile var currentSpeed: Float = 0f
    @Volatile var currentBearing: Float = 0f
    @Volatile var currentAccuracy: Float = 8f
    @Volatile var currentAltitude: Double = config.location.altitude
    @Volatile var frameIndex: Long = 0
    @Volatile var baseTimestampMs: Long = System.currentTimeMillis()
    @Volatile var baseElapsedNanos: Long = System.nanoTime()

    // Walking simulation state
    private val gaitSim = NoiseGenerator.GaitSimulator(1.8)
    private val accuracyWalker = NoiseGenerator.SmoothWalker(5.0, 20.0, 3.0)
    private var prevLat = config.location.lat
    private var prevLng = config.location.lng

    // Weather offset for barometer (stable within a day)
    private val weatherOffset: Double = Random.nextInt(-10, 10).toDouble()

    // ---- Per-package resolved configs ----
    private val resolvedCache = mutableMapOf<String, ConfigLoader.ResolvedConfig>()

    fun resolvedFor(pkgName: String): ConfigLoader.ResolvedConfig =
        resolvedCache.getOrPut(pkgName) {
            ConfigLoader.resolveForPackage(config, pkgName)
        }

    // ========== FRAME CYCLE ==========

    /**
     * Advance one frame. Called every [BehaviorConfig.updateIntervalMs].
     * Updates all dynamic state atomically.
     */
    fun tick(pkgName: String) {
        val resolved = resolvedFor(pkgName)
        val bhv = resolved.behavior

        frameIndex++
        baseTimestampMs = System.currentTimeMillis()
        baseElapsedNanos = System.nanoTime()

        // Compute next position
        val jitterSigma = bhv.gpsJitterMeters
        val seed = deterministicSeed()

        when (bhv.mode) {
            "static" -> {
                val (dLat, dLng) = NoiseGenerator.gaussian2D(seed, jitterSigma * 0.3, currentLat)
                currentLat = config.location.lat + dLat
                currentLng = config.location.lng + dLng
                currentSpeed = 0f
                currentBearing = (currentBearing + (Random.nextFloat() - 0.5f) * 0.1f) % 360f
            }
            "walking" -> {
                val (dLat, dLng) = NoiseGenerator.gaussian2D(seed, jitterSigma, currentLat)
                val walkPhase = gaitSim.phase()
                val walkAmp = 0.000008  // ~0.9m walk cycle radius

                val walkNorth = walkAmp * cos(walkPhase * 2 * PI)
                val walkEast = walkAmp * sin(walkPhase * 2 * PI) * 0.6

                currentLat = config.location.lat + dLat + walkNorth
                currentLng = config.location.lng + dLng + walkEast
                currentSpeed = SensorMath.naturalSpeed(1.0, seed).toFloat()
                currentBearing = computeBearing()
            }
            "transition" -> {
                if (bhv.transitionEnabled) {
                    // Simulate arriving at destination over ~3 minutes
                    // Phase 1 (0-60%): walk toward target (speed 1.0-1.5 m/s)
                    // Phase 2 (60-90%): slow down (speed 0.3-0.8 m/s)
                    // Phase 3 (90-100%): arrive, minor jitter at target
                    val totalFrames = 90  // ~3 min at 2s/frame
                    val progress = (frameIndex % totalFrames).toDouble() / totalFrames

                    if (progress < 0.6) {
                        val speed = 1.2 + Random.nextDouble() * 0.3
                        currentSpeed = speed.toFloat()
                        val dist = speed * 2.0 / 111_320.0 // m/s * interval / deg_per_m
                        val angle = GeoMath.bearingDegrees(currentLat, currentLng, config.location.lat, config.location.lng)
                        currentLat += dist * cos(Math.toRadians(angle))
                        currentLng += dist * sin(Math.toRadians(angle))
                    } else if (progress < 0.9) {
                        currentSpeed = (0.3 + Random.nextDouble() * 0.5).toFloat()
                        val dist = 0.8 * 2.0 / 111_320.0
                        val angle = GeoMath.bearingDegrees(currentLat, currentLng, config.location.lat, config.location.lng)
                        currentLat += dist * cos(Math.toRadians(angle))
                        currentLng += dist * sin(Math.toRadians(angle))
                    } else {
                        currentSpeed = 0f
                        val (dLat, dLng) = NoiseGenerator.gaussian2D(seed, jitterSigma * 0.2, currentLat)
                        currentLat = config.location.lat + dLat
                        currentLng = config.location.lng + dLng
                    }
                    currentBearing = computeBearing()
                } else {
                    currentLat = config.location.lat
                    currentLng = config.location.lng
                    currentSpeed = 0f
                }
            }
        }

        // Accuracy with natural variation
        currentAccuracy = SensorMath.realisticAccuracy(
            bhv.accuracyRangeM[0]..bhv.accuracyRangeM[1], seed
        )

        // Altitude with slight variation
        currentAltitude = config.location.altitude +
                NoiseGenerator.gaussian1D(seed xor 0xABCD, 2.0)
    }

    /** Deterministic seed from frame + location for cross-module consistency */
    fun deterministicSeed(): Long =
        (config.location.lat * 1e6).toLong() xor
        (config.location.lng * 1e6).toLong() xor
        frameIndex

    private fun computeBearing(): Float {
        val bearing = GeoMath.bearingDegrees(prevLat, prevLng, currentLat, currentLng)
        prevLat = currentLat
        prevLng = currentLng
        // Smooth transition
        val smooth = currentBearing * 0.7f + bearing.toFloat() * 0.3f
        return smooth % 360f
    }

    // ========== LOCATION DATA ==========

    /** GPS-type Location values */
    fun gpsLocation(): LocationValues = LocationValues(
        lat = currentLat,
        lng = currentLng,
        accuracy = currentAccuracy,
        speed = currentSpeed,
        bearing = currentBearing,
        altitude = currentAltitude,
        time = baseTimestampMs,
        elapsedNanos = baseElapsedNanos
    )

    /** NETWORK-type Location values (less precise, offset from GPS) */
    fun networkLocation(seed: Long): LocationValues {
        val (dLat, dLng) = NoiseGenerator.gaussian2D(seed xor 0xBEEF, 50.0, currentLat)
        return LocationValues(
            lat = currentLat + dLat,
            lng = currentLng + dLng,
            accuracy = SensorMath.realisticAccuracy(15.0..80.0, seed),
            speed = 0f,
            bearing = 0f,
            altitude = 0.0,
            time = baseTimestampMs + 50,
            elapsedNanos = baseElapsedNanos + 50_000_000
        )
    }

    /** PASSIVE-type Location values (larger offset) */
    fun passiveLocation(seed: Long): LocationValues {
        val (dLat, dLng) = NoiseGenerator.gaussian2D(seed xor 0xCAFE, 100.0, currentLat)
        return LocationValues(
            lat = currentLat + dLat,
            lng = currentLng + dLng,
            accuracy = SensorMath.realisticAccuracy(30.0..120.0, seed),
            speed = 0f,
            bearing = 0f,
            altitude = 0.0,
            time = baseTimestampMs + (seed % 100),
            elapsedNanos = baseElapsedNanos + (seed % 100_000_000)
        )
    }

    // ========== SENSOR DATA (delegates to SensorMath) ==========

    fun accelerometerValues(): FloatArray {
        val seed = deterministicSeed()
        return if (config.behavior.mode == "static") {
            SensorMath.stationaryAccelerometer(seed)
        } else {
            SensorMath.walkingAccelerometer(gaitSim.phase(), currentSpeed.toDouble(), seed)
        }
    }

    fun gyroscopeValues(prevBearing: Float): FloatArray {
        val seed = deterministicSeed()
        val dT = config.behavior.updateIntervalMs / 1000.0
        val bearingChange = (currentBearing - prevBearing + 360f) % 360f
        if (bearingChange > 180f) return SensorMath.gyroscopeValues(
            bearingChange - 360f, dT, config.behavior.mode != "static", seed
        )
        return SensorMath.gyroscopeValues(
            bearingChange.toDouble(), dT, config.behavior.mode != "static", seed
        )
    }

    fun magnetometerValues(): FloatArray {
        val seed = deterministicSeed()
        return SensorMath.magnetometerValues(
            config.location.lat, config.location.lng,
            currentBearing.toDouble(), seed
        )
    }

    fun barometerValue(): Float {
        val seed = deterministicSeed()
        return SensorMath.barometerValue(currentAltitude, weatherOffset, seed)
    }

    // ========== DATA TYPES ==========

    data class LocationValues(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val speed: Float,
        val bearing: Float,
        val altitude: Double,
        val time: Long,
        val elapsedNanos: Long
    )

    /**
     * Current frame snapshot â€?read by all hook modules.
     * All values are consistent within one tick.
     */
    data class FrameSnapshot(
        val jitteredLat: Double,
        val jitteredLng: Double,
        val gpsAccuracy: Float,
        val networkAccuracy: Float,
        val passiveAccuracy: Float,
        val fusedAccuracy: Float,
        val altitude: Double,
        val speed: Float,
        val bearing: Float,
        val timestampMs: Long,
        val networkOffsetLat: Double,
        val networkOffsetLng: Double,
        val passiveOffsetLat: Double,
        val passiveOffsetLng: Double,
        val frameIndex: Long,
        val frameSeed: Long
    )

    /** Thread-safe current frame for hook consumption */
    val currentFrame: FrameSnapshot
        get() {
            val seed = deterministicSeed()
            val snap = FrameSnapshot(
                jitteredLat = currentLat,
                jitteredLng = currentLng,
                gpsAccuracy = currentAccuracy,
                networkAccuracy = SensorMath.realisticAccuracy(15.0..80.0, seed xor 0xBEEF),
                passiveAccuracy = SensorMath.realisticAccuracy(30.0..120.0, seed xor 0xCAFE),
                fusedAccuracy = currentAccuracy * 1.2f,
                altitude = currentAltitude,
                speed = currentSpeed,
                bearing = currentBearing,
                timestampMs = baseTimestampMs,
                networkOffsetLat = NoiseGenerator.gaussian1D(seed xor 0xAAAA, 0.0003),
                networkOffsetLng = NoiseGenerator.gaussian1D(seed xor 0xBBBB, 0.0003),
                passiveOffsetLat = NoiseGenerator.gaussian1D(seed xor 0xCCCC, 0.0006),
                passiveOffsetLng = NoiseGenerator.gaussian1D(seed xor 0xDDDD, 0.0006),
                frameIndex = frameIndex,
                frameSeed = seed
            )
            previousFrame = snap
            return snap
        }

    /** Snapshot of previous frame (for gyroscope delta calculations) */
    @Volatile var previousFrame: FrameSnapshot? = null

    companion object {
        @Volatile
        private var instance: CoordinatesEngine? = null

        fun initialize(resolved: ConfigLoader.ResolvedConfig): CoordinatesEngine {
            val config = ConfigLoader.load() ?:
                throw IllegalStateException("Config not found at ${ConfigLoader.CONFIG_PATH}")
            val engine = CoordinatesEngine(config)
            instance = engine
            return engine
        }

        fun getInstance(): CoordinatesEngine? = instance
    }
}
