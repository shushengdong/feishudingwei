package com.research.location.hook.hooks

import android.media.ExifInterface

/**
 * Hook ExifInterface to return fake GPS coordinates from photos.
 *
 * If Feishu requires photo check-in, the photo EXIF contains real GPS.
 * This hook intercepts EXIF reads to return mock coordinates instead.
 */
class ExifHook : BaseHook("Exif", priority = 11) {

    private val gpsKeys = listOf(
        "GPSLatitude", "GPSLatitudeRef",
        "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef",
        "GPSProcessingMethod", "GPSDateStamp", "GPSTimeStamp"
    )

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        // Hook ExifInterface.getAttribute(String) — single attribute read
        hookAfter(
            ExifInterface::class.java, "getAttribute",
            String::class.java
        ) { param ->
            val tag = param.args[0] as? String ?: return@hookAfter
            if (tag in gpsKeys) {
                val e = engine ?: return@hookAfter
                val frame = e.currentFrame
                param.result = when (tag) {
                    "GPSLatitude" -> formatLatLng(Math.abs(frame.jitteredLat))
                    "GPSLatitudeRef" -> if (frame.jitteredLat >= 0) "N" else "S"
                    "GPSLongitude" -> formatLatLng(Math.abs(frame.jitteredLng))
                    "GPSLongitudeRef" -> if (frame.jitteredLng >= 0) "E" else "W"
                    "GPSAltitude" -> "${frame.altitude.toInt()}/1"
                    "GPSAltitudeRef" -> "0"
                    "GPSProcessingMethod" -> "GPS"
                    "GPSDateStamp" -> {
                        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd", java.util.Locale.US)
                        sdf.format(java.util.Date(frame.timestampMs))
                    }
                    "GPSTimeStamp" -> {
                        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        sdf.format(java.util.Date(frame.timestampMs))
                    }
                    else -> param.result
                }
            }
        }

        // Hook ExifInterface.getLatLong(float[]) — returns [lat, lng]
        hookAfter(
            ExifInterface::class.java, "getLatLong",
            FloatArray::class.java
        ) { param ->
            val output = param.args[0] as? FloatArray ?: return@hookAfter
            val e = engine ?: return@hookAfter
            val frame = e.currentFrame
            output[0] = frame.jitteredLat.toFloat()
            output[1] = frame.jitteredLng.toFloat()
            param.result = output
        }

        // Also hook ExifInterface(String) constructor — but nothing to intercept there.
        // The file path is real, only attribute reads need spoofing.

        log("Installed — EXIF GPS spoofing active")
    }

    /** Format decimal degrees as "DD/1,MM/1,SSSS/100" DMS string */
    private fun formatLatLng(decimal: Double): String {
        val deg = decimal.toInt()
        val minFloat = (decimal - deg) * 60
        val min = minFloat.toInt()
        val sec = ((minFloat - min) * 60 * 100).toInt()
        return "$deg/1,$min/1,$sec/100"
    }
}
