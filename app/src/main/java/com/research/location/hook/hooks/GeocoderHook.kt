package com.research.location.hook.hooks

import android.location.Address
import android.location.Geocoder
import java.util.Locale

/**
 * Hook Geocoder.getFromLocation() to return fake addresses
 * matching the mock GPS coordinates instead of real ones.
 *
 * Without this: Feishu calls Geocoder to display address like
 * "上海市浦东新区..." but mock GPS is Beijing → contradiction.
 */
class GeocoderHook : BaseHook("Geocoder", priority = 10) {

    // Chinese city templates (city→district→street level)
    private val cityTemplates = mapOf(
        "北京" to AddressTemplate("中国", "北京市", "朝阳区", "国贸", "建国路", "100020"),
        "上海" to AddressTemplate("中国", "上海市", "浦东新区", "陆家嘴", "世纪大道", "200120"),
        "广州" to AddressTemplate("中国", "广东省", "天河区", "珠江新城", "华夏路", "510623"),
        "深圳" to AddressTemplate("中国", "广东省", "南山区", "科技园", "科苑路", "518000"),
        "成都" to AddressTemplate("中国", "四川省", "武侯区", "天府软件园", "天府大道", "610041"),
        "杭州" to AddressTemplate("中国", "浙江省", "西湖区", "文三路", "文三路", "310012"),
        "武汉" to AddressTemplate("中国", "湖北省", "洪山区", "光谷", "珞喻路", "430074"),
        "南京" to AddressTemplate("中国", "江苏省", "鼓楼区", "新街口", "中山路", "210005"),
        "重庆" to AddressTemplate("中国", "重庆市", "渝北区", "两江新区", "金开大道", "401120"),
        "西安" to AddressTemplate("中国", "陕西省", "雁塔区", "高新区", "科技路", "710065"),
        "苏州" to AddressTemplate("中国", "江苏省", "工业园区", "湖东", "苏州大道", "215000"),
        "长沙" to AddressTemplate("中国", "湖南省", "岳麓区", "麓谷", "麓谷大道", "410006"),
        "厦门" to AddressTemplate("中国", "福建省", "思明区", "软件园", "环岛路", "361005"),
        "合肥" to AddressTemplate("中国", "安徽省", "蜀山区", "高新区", "望江西路", "230088"),
        "天津" to AddressTemplate("中国", "天津市", "和平区", "劝业场", "南京路", "300041"),
        "郑州" to AddressTemplate("中国", "河南省", "郑东新区", "CBD", "商务内环路", "450046"),
        "济南" to AddressTemplate("中国", "山东省", "历下区", "奥体中心", "经十路", "250014"),
        "青岛" to AddressTemplate("中国", "山东省", "市南区", "软件园", "香港中路", "266071"),
        "福州" to AddressTemplate("中国", "福建省", "鼓楼区", "软件园", "软件大道", "350003"),
        "大连" to AddressTemplate("中国", "辽宁省", "沙河口区", "软件园", "数码路", "116023")
    )

    data class AddressTemplate(
        val country: String,
        val admin: String,
        val locality: String,
        val subLocality: String,
        val thoroughfare: String,
        val postalCode: String
    )

    override fun install(
        lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        engine: com.research.location.hook.CoordinatesEngine,
        config: com.research.location.hook.ConfigLoader.ResolvedConfig
    ) {
        super.install(lpp, engine, config)

        // Hook Geocoder.getFromLocation(double, double, int)
        hookReplace(
            Geocoder::class.java, "getFromLocation",
            Double::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) { param ->
            val lat = param.args[0] as Double
            val lng = param.args[1] as Double
            val maxResults = param.args[2] as Int
            buildFakeAddresses(lat, lng, maxResults)
        }

        log("Installed — Geocoder spoofing active")
    }

    private fun buildFakeAddresses(lat: Double, lng: Double, maxResults: Int): List<Address> {
        val e = engine ?: return emptyList()
        val template = findNearestTemplate(e.currentFrame?.jitteredLat ?: lat)
        return (0 until maxResults.coerceIn(1, 3)).map { i ->
            Address(Locale.CHINA).apply {
                countryName = template.country
                countryCode = "CN"
                adminArea = template.admin
                locality = template.locality
                subLocality = if (i == 0) template.subLocality else buildVariation(template.subLocality, i)
                thoroughfare = if (i == 0) template.thoroughfare else buildVariation(template.thoroughfare, i)
                featureName = if (i == 0) "${template.subLocality}大厦" else "${template.subLocality}中心"
                postalCode = template.postalCode
                latitude = lat
                longitude = lng
                setPhone("010-${(10000000 + kotlin.random.Random.nextLong() % 90000000)}")
            }
        }
    }

    private fun findNearestTemplate(lat: Double): AddressTemplate {
        // Simplified: return Beijing for north, Shanghai for east
        return when {
            lat > 38 -> cityTemplates["北京"]!!
            lat > 34 -> cityTemplates["西安"]!!
            lat > 30 -> cityTemplates["上海"]!!
            lat > 28 -> cityTemplates["成都"]!!
            lat > 25 -> cityTemplates["厦门"]!!
            lat > 23 -> cityTemplates["广州"]!!
            lat > 22 -> cityTemplates["深圳"]!!
            else -> cityTemplates["北京"]!!
        }
    }

    private fun buildVariation(base: String, idx: Int): String =
        when (idx) {
            1 -> "${base}东"
            2 -> "${base}西"
            3 -> "${base}南"
            else -> "${base}北"
        }
}
