package com.research.location.hook.hooks

import android.telephony.TelephonyManager
import com.research.location.hook.data.CellDataBuilder

/**
 * Priority 4: Hook TelephonyManager to return fake cell tower data.
 */
class CellHook : BaseHook("Cell", priority = 4) {

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)
        if (!config.cell.enabled) return

        val ccfg = config.cell

        // Hook-1: getAllCellInfo()
        hookReplace(
            TelephonyManager::class.java, "getAllCellInfo"
        ) { _ ->
            val e = engine ?: return@hookReplace emptyList<android.telephony.CellInfo>()
            val frame = e.currentFrame
            val lac = CellDataBuilder.generateLac(frame.jitteredLat, frame.jitteredLng)

            CellDataBuilder.buildCellInfoList(
                mcc = ccfg.mcc,
                mnc = ccfg.mnc,
                lac = lac,
                neighborCount = ccfg.neighborCount,
                rssiRange = ccfg.rssiRange[0]..ccfg.rssiRange[1],
                networkType = ccfg.networkType,
                seed = frame.frameSeed
            )
        }

        // Hook-2: getCellLocation()
        hookReplace(
            TelephonyManager::class.java, "getCellLocation"
        ) { _ -> null  // Deprecated API, return null is safer than fake GsmCellLocation
        }

        // Hook-3: getNetworkOperator()
        hookAfter(
            TelephonyManager::class.java, "getNetworkOperator"
        ) { param ->
            param.result = "${ccfg.mcc}${ccfg.mnc}"
        }

        // Hook-4: getNetworkOperatorName()
        hookAfter(
            TelephonyManager::class.java, "getNetworkOperatorName"
        ) { param ->
            param.result = ccfg.operatorName
        }

        // Hook-5: getSimOperator()
        hookAfter(
            TelephonyManager::class.java, "getSimOperator"
        ) { param ->
            param.result = "${ccfg.mcc}${ccfg.mnc}"
        }

        // Hook-6: getSimOperatorName()
        hookAfter(
            TelephonyManager::class.java, "getSimOperatorName"
        ) { param ->
            param.result = ccfg.operatorName
        }

        // Hook-7: getNetworkCountryIso()
        hookAfter(
            TelephonyManager::class.java, "getNetworkCountryIso"
        ) { param ->
            param.result = "cn"
        }

        // Hook-8: getSimCountryIso()
        hookAfter(
            TelephonyManager::class.java, "getSimCountryIso"
        ) { param ->
            param.result = "cn"
        }

        // Hook-9: getNetworkType()
        hookAfter(
            TelephonyManager::class.java, "getNetworkType"
        ) { param ->
            param.result = mapNetworkType(ccfg.networkType)
        }

        // Hook-10: getDataNetworkType()
        hookAfter(
            TelephonyManager::class.java, "getDataNetworkType"
        ) { param ->
            param.result = mapNetworkType(ccfg.networkType)
        }

        // Hook-11: getPhoneType()
        hookAfter(
            TelephonyManager::class.java, "getPhoneType"
        ) { param ->
            param.result = TelephonyManager.PHONE_TYPE_GSM
        }

        // Hook-12: getServiceState() — return IN_SERVICE
        try {
            hookReplace(
                TelephonyManager::class.java, "getServiceState"
            ) { _ -> null  // Returns android.telephony.ServiceState, too complex to fake
            }
        } catch (_: Exception) {}

        log("Installed — Cell spoofing active")
    }

    private fun mapNetworkType(type: String): Int = when (type) {
        "LTE" -> TelephonyManager.NETWORK_TYPE_LTE        // 13
        "NR" -> TelephonyManager.NETWORK_TYPE_NR           // 20
        "WCDMA" -> TelephonyManager.NETWORK_TYPE_HSPA     // 10
        "GSM" -> TelephonyManager.NETWORK_TYPE_EDGE       // 2
        else -> TelephonyManager.NETWORK_TYPE_LTE
    }
}
