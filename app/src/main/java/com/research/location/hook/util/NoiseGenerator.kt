package com.research.location.hook.util

import kotlin.math.*
import kotlin.random.Random

/**
 * Noise generators for realistic sensor and location simulation.
 * Uses deterministic seeding for cross-module consistency.
 */
object NoiseGenerator {

    /**
     * Box-Muller transform: 2D Gaussian noise with standard deviation sigma.
     * @param seed deterministic seed (same seed = same noise)
     * @param sigmaMeters standard deviation in meters
     * @param latDeg reference latitude for longitude scaling
     * @return (dLat_deg, dLng_deg) offset
     */
    fun gaussian2D(
        seed: Long,
        sigmaMeters: Double,
        latDeg: Double
    ): Pair<Double, Double> {
        val rng = Random(seed)
        val u1 = rng.nextDouble()
        val u2 = rng.nextDouble()
        // Avoid log(0)
        val r = sigmaMeters * sqrt(-2.0 * ln(max(u1, 1e-10)))
        val theta = 2.0 * PI * u2

        val dNorth = r * cos(theta)
        val dEast = r * sin(theta)
        return GeoMath.metersToDegrees(latDeg, dNorth, dEast)
    }

    /** 1D Gaussian noise */
    fun gaussian1D(seed: Long, sigma: Double): Double {
        val rng = Random(seed)
        val u1 = rng.nextDouble()
        val u2 = rng.nextDouble()
        return sigma * sqrt(-2.0 * ln(max(u1, 1e-10))) * cos(2.0 * PI * u2)
    }

    /** Uniform noise in [-range, +range] */
    fun uniform1D(seed: Long, range: Double): Double {
        val rng = Random(seed)
        return (rng.nextDouble() - 0.5) * 2.0 * range
    }

    /** Perlin-like smooth random walk for continuous parameter variation */
    class SmoothWalker(
        private val min: Double,
        private val max: Double,
        private val maxStep: Double
    ) {
        private var value = (min + max) / 2.0
        private var rng = Random(System.currentTimeMillis())

        fun next(): Double {
            value += (rng.nextDouble() - 0.5) * 2.0 * maxStep
            value = value.coerceIn(min, max)
            return value
        }

        fun reset(seed: Long) { rng = Random(seed) }
        fun current() = value
    }

    /** Walking phase generator for gait simulation */
    class GaitSimulator(
        private val stepsPerSecond: Double = 1.8
    ) {
        private var startTime = System.currentTimeMillis()

        /** Returns phase [0.0, 1.0) representing position in current step */
        fun phase(): Double {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            return (elapsed * stepsPerSecond) % 1.0
        }

        fun reset() { startTime = System.currentTimeMillis() }
    }
}
