package com.research.location.hook.data

import android.telephony.*

/**
 * Factory for constructing fake CellInfo objects.
 */
object CellDataBuilder {

    /**
     * Build a list of CellInfo for a target location.
     * @param mcc mobile country code (460 = China)
     * @param mnc mobile network code (00=CMCC, 01=CU, 03=CT)
     * @param lac location area code / tracking area code
     * @param neighborCount number of neighbor cells
     * @param rssiRange signal strength range [min, max] in dBm
     * @param networkType "LTE" | "NR" | "WCDMA" | "GSM"
     * @param seed deterministic seed
     */
    fun buildCellInfoList(
        mcc: String,
        mnc: String,
        lac: Int,
        neighborCount: Int,
        rssiRange: IntRange,
        networkType: String,
        seed: Long
    ): List<CellInfo> {
        val rng = kotlin.random.Random(seed)
        val cells = mutableListOf<CellInfo>()
        val mccInt = mcc.toIntOrNull() ?: 460
        val mncInt = mnc.toIntOrNull() ?: 0

        // Primary serving cell (strong signal)
        val servingRssi = rssiRange.last - rng.nextInt(15)
        cells.add(buildServingCell(mccInt, mncInt, lac, servingRssi, networkType, seed xor 0xFF))

        // Neighbor cells (progressively weaker)
        for (i in 0 until neighborCount) {
            val nRssi = servingRssi - 5 - rng.nextInt(25)
            val nCi = lac * 1000 + 500 + rng.nextInt(500)
            cells.add(buildNeighborCell(mccInt, mncInt, lac, nCi, nRssi, networkType, seed xor (i + 1).toLong()))
        }

        return cells
    }

    private fun buildServingCell(
        mcc: Int, mnc: Int, lac: Int, rssi: Int,
        networkType: String, seed: Long
    ): CellInfo {
        val rng = kotlin.random.Random(seed)
        val ci = lac * 1000 + rng.nextInt(500)  // Cell identity in same LAC range
        val pci = rng.nextInt(504)

        return when (networkType) {
            "LTE" -> buildLteCellInfo(mcc, mnc, lac, ci, pci, rssi, true, rng)
            "NR" -> buildNrCellInfo(mcc, mnc, lac, ci, pci, rssi, true, rng)
            "WCDMA" -> buildWcdmaCellInfo(mcc, mnc, lac, ci, rssi, true, rng)
            else -> buildGsmCellInfo(mcc, mnc, lac, ci, rssi, true, rng)
        }
    }

    private fun buildNeighborCell(
        mcc: Int, mnc: Int, lac: Int, ci: Int, rssi: Int,
        networkType: String, seed: Long
    ): CellInfo {
        val rng = kotlin.random.Random(seed)
        val pci = rng.nextInt(504)
        return when (networkType) {
            "LTE" -> buildLteCellInfo(mcc, mnc, lac, ci, pci, rssi, false, rng)
            "NR" -> buildNrCellInfo(mcc, mnc, lac, ci, pci, rssi, false, rng)
            "WCDMA" -> buildWcdmaCellInfo(mcc, mnc, lac, ci, rssi, false, rng)
            else -> buildGsmCellInfo(mcc, mnc, lac, ci, rssi, false, rng)
        }
    }

    private fun buildLteCellInfo(
        mcc: Int, mnc: Int, tac: Int, ci: Int, pci: Int,
        rssi: Int, isRegistered: Boolean, rng: kotlin.random.Random
    ): CellInfo {
        val ciLte = CellIdentityLte::class.java.getConstructor(
            Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(ci, pci, tac, mcc, mnc, "")

        val ssLte = CellSignalStrengthLte::class.java.getConstructor(
            Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(
            rssi, rssi - 20, rssi - 3, 20000 + rng.nextInt(10000), 0
        )

        val cellInfo = CellInfoLte::class.java.getConstructor().apply { isAccessible = true }.newInstance()
        CellInfoLte::class.java.getDeclaredMethod("setCellIdentity", CellIdentityLte::class.java)
            .apply { isAccessible = true }.invoke(cellInfo, ciLte)
        CellInfoLte::class.java.getDeclaredMethod("setCellSignalStrength", CellSignalStrengthLte::class.java)
            .apply { isAccessible = true }.invoke(cellInfo, ssLte)
        if (isRegistered) {
            try {
                CellInfoLte::class.java.getDeclaredField("mCellConnectionStatus")
                    .apply { isAccessible = true }.set(cellInfo, 0) // PRIMARY_SERVING
            } catch (_: Exception) {}
        }

        // Band: choose common Chinese LTE bands
        val bands = listOf(3, 39, 40, 41)
        val band = bands[rng.nextInt(bands.size)]
        try {
            val bandField = CellIdentityLte::class.java.getDeclaredField("mBandwidth")
            bandField.isAccessible = true
            bandField.set(ciLte, 20) // 20MHz
        } catch (_: Exception) {}

        return cellInfo
    }

    private fun buildNrCellInfo(
        mcc: Int, mnc: Int, tac: Int, ci: Int, pci: Int,
        rssi: Int, isRegistered: Boolean, rng: kotlin.random.Random
    ): CellInfo {
        // Fallback to LTE if NR reflection fails
        return buildLteCellInfo(mcc, mnc, tac, ci, pci, rssi, isRegistered, rng)
    }

    private fun buildWcdmaCellInfo(
        mcc: Int, mnc: Int, lac: Int, ci: Int,
        rssi: Int, isRegistered: Boolean, rng: kotlin.random.Random
    ): CellInfo {
        val psc = rng.nextInt(512)

        val ciWcdma = CellIdentityWcdma::class.java.getConstructor(
            Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(mcc, mnc, lac, ci, psc, null)

        val ssWcdma = CellSignalStrengthWcdma::class.java.getConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(rssi, 0, 0)

        val cellInfo = CellInfoWcdma::class.java.getConstructor().apply { isAccessible = true }.newInstance()
        CellInfoWcdma::class.java.getDeclaredMethod("setCellIdentity", CellIdentityWcdma::class.java)
            .apply { isAccessible = true }.invoke(cellInfo, ciWcdma)
        CellInfoWcdma::class.java.getDeclaredMethod("setCellSignalStrength", CellSignalStrengthWcdma::class.java)
            .apply { isAccessible = true }.invoke(cellInfo, ssWcdma)

        return cellInfo
    }

    private fun buildGsmCellInfo(
        mcc: Int, mnc: Int, lac: Int, ci: Int,
        rssi: Int, isRegistered: Boolean, rng: kotlin.random.Random
    ): CellInfo {
        val cid = ci and 0xFFFF

        val ciGsm = CellIdentityGsm::class.java.getConstructor(
            Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(lac, cid, mcc, mnc, 0)

        val ssGsm = CellSignalStrengthGsm::class.java.getConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(rssi, 0, 0)

        val cellInfo = CellInfoGsm::class.java.getConstructor().apply { isAccessible = true }.newInstance()
        CellInfoGsm::class.java.getDeclaredMethod("setCellIdentity", CellIdentityGsm::class.java)
            .apply { isAccessible = true }.invoke(cellInfo, ciGsm)
        CellInfoGsm::class.java.getDeclaredMethod("setCellSignalStrength", CellSignalStrengthGsm::class.java)
            .apply { isAccessible = true }.invoke(cellInfo, ssGsm)

        return cellInfo
    }

    /**
     * Generate LAC from city location.
     * Deterministic so same city always produces similar LAC range.
     */
    fun generateLac(lat: Double, lng: Double): Int {
        val cityHash = ((lat * 100).toInt() xor (lng * 100).toInt())
        return (cityHash and 0x7FFF) + 30000  // 30000-65535 range (common for urban)
    }
}
