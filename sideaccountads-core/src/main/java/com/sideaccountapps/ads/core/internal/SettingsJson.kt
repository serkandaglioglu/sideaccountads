package com.sideaccountapps.ads.core.internal

import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.FormatSettings
import com.sideaccountapps.ads.core.SideAdsSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON ayar okuyucu (Remote Config). Şema README'de.
 *
 * `testMode`, `admobTestDeviceIds`, `childDirected`, `doNotSell` bilinçli olarak okunmaz: JSON'da olsalar da yok
 * sayılır, `localSettings` bloğundan gelir. Blok JSON'dan sonra uygulanır, yani koddaki değer her zaman kazanır.
 * Bilinmeyen anahtarlar (eski şemadan kalanlar dahil) sessizce yok sayılır.
 */
internal object SettingsJson {

    fun parse(json: String, localSettings: (SideAdsSettings.Builder.() -> Unit)?): SideAdsSettings {
        val root = JSONObject(json)
        val builder = SideAdsSettings.Builder()
        builder.apply {
            adsEnabled = root.optBoolean("adsEnabled", adsEnabled)
            debugLogging = root.optBoolean("debugLogging", debugLogging)
            umpConsentEnabled = root.optBoolean("umpConsentEnabled", umpConsentEnabled)
            interstitialMinIntervalSec = root.optInt("interstitialMinIntervalSec", interstitialMinIntervalSec)
            appOpenMinGapSec = root.optInt("appOpenMinGapSec", appOpenMinGapSec)
            appOpenMinIntervalSec = root.optInt("appOpenMinIntervalSec", appOpenMinIntervalSec)
            sessionMaxInterstitials = root.optInt("sessionMaxInterstitials", sessionMaxInterstitials)
            firstInterstitialDelaySec = root.optInt("firstInterstitialDelaySec", firstInterstitialDelaySec)
            retryBaseDelaySec = root.optInt("retryBaseDelaySec", retryBaseDelaySec)
            retryMaxDelaySec = root.optInt("retryMaxDelaySec", retryMaxDelaySec)
            maxBannerRetryCount = root.optInt("maxBannerRetryCount", maxBannerRetryCount)
            reuseBannersAcrossActivities = root.optBoolean("reuseBannersAcrossActivities", reuseBannersAcrossActivities)
            adTtlMinutes = root.optInt("adTtlMinutes", adTtlMinutes)
            for (type in AdType.entries) {
                root.optJSONObject(type.jsonKey)?.let { obj -> format(type) { readFormat(obj) } }
            }
        }
        localSettings?.invoke(builder)
        return builder.build()
    }

    /** Ayrıştırılamazsa null; çağıran mevcut ayarı korur. */
    fun parseOrNull(json: String, localSettings: (SideAdsSettings.Builder.() -> Unit)?): SideAdsSettings? =
        try {
            parse(json, localSettings)
        } catch (e: Exception) {
            Logger.e("Settings JSON could not be parsed: ${e.message}")
            null
        }

    private fun FormatSettings.Builder.readFormat(obj: JSONObject) {
        enabled = obj.optBoolean("enabled", true)
        preload = obj.optBoolean("preload", false)
        obj.optJSONObject("placements")?.let { placements ->
            for (id in placements.keys()) {
                val p = placements.optJSONObject(id) ?: continue
                addPlacement(
                    id = id,
                    adUnitIds = p.adUnits(),
                    enabled = p.optBoolean("enabled", true),
                    preload = p.optBoolean("preload", true),
                    reloadAfterShow = p.optBoolean("reloadAfterShow", true)
                )
            }
        }
        // Placement'lardan sonra: addPlacement ilk placement'ı default yapar, JSON'daki değer onu ezer.
        obj.optNonBlankString("defaultPlacement")?.let { defaultPlacement = it }
        initialPreloadPlacements = obj.optJSONArray("initialPreloadPlacements")?.strings().orEmpty()
    }

    /** `"adUnit"`: tek id (`"..."`) ya da sırayla denenecek dizi (`["...", "..."]`). */
    private fun JSONObject.adUnits(): List<String> =
        optJSONArray("adUnit")?.strings() ?: listOfNotNull(optNonBlankString("adUnit"))

    private fun JSONObject.optNonBlankString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
