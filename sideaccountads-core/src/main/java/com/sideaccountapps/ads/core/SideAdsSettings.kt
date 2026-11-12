package com.sideaccountapps.ads.core

import com.sideaccountapps.ads.core.internal.SettingsJson

/**
 * Bir placement: uygulamanın reklam gösterdiği yer ve onun ad unit zinciri.
 *
 * @property adUnitIds sırayla denenen ad unit'ler (ör. önce yüksek floor'lu, sonra tüm fiyatlar).
 *   SDK "yüklendi" derse durulur, hata verirse sıradakine geçilir.
 * @property preload `false` ise placement otomatik preload'a hiç katılmaz (ne açılışta ne gösterim sonrası);
 *   yalnızca `load()` ya da hazır değilken yapılan `show()` ile yüklenir.
 * @property reloadAfterShow `false` ise placement tek kullanımlıktır: gösterim (veya gösterim hatası) sonrası
 *   yeniden yüklenmez ve yükleme hatasında retry kurulmaz (ör. splash).
 */
class PlacementSettings internal constructor(
    val id: String,
    val adUnitIds: List<String>,
    val enabled: Boolean,
    val preload: Boolean,
    val reloadAfterShow: Boolean
)

/**
 * Bir formatın (interstitial, rewarded, app open, banner) ayarları.
 *
 * @property preload otomatik yüklemenin ana anahtarı (açılışta ve gösterim sonrası). Banner'ı etkilemez.
 * @property defaultPlacementId placement verilmeyen çağrılarda kullanılan placement.
 * @property initialPreloadPlacementIds açılışta yüklenecek placement'lar; boşsa yalnızca default placement.
 */
class FormatSettings internal constructor(
    val type: AdType,
    val enabled: Boolean,
    val preload: Boolean,
    val defaultPlacementId: String?,
    val placements: Map<String, PlacementSettings>,
    val initialPreloadPlacementIds: List<String>
) {
    /** [id] null ise default placement'ı döner; yoksa null. */
    fun placement(id: String?): PlacementSettings? {
        val key = id ?: defaultPlacementId ?: return null
        return placements[key]
    }

    /** Placement otomatik preload'a katılıyor mu (açılış ve gösterim sonrası)? */
    internal fun autoPreloads(placement: PlacementSettings): Boolean =
        preload && placement.enabled && placement.preload

    /** Gösterim veya gösterim hatası sonrası yeniden yüklensin mi; yükleme hatasında retry kurulsun mu? */
    internal fun reloadsAfterShow(placement: PlacementSettings): Boolean =
        autoPreloads(placement) && placement.reloadAfterShow

    /** Açılışta yüklenecek placement'lar. */
    internal fun initialPreloadPlacements(): List<PlacementSettings> =
        initialPreloadPlacementIds.ifEmpty { listOfNotNull(defaultPlacementId) }
            .mapNotNull { placements[it] }
            .filter { autoPreloads(it) }

    /** Format ayarlarını kuran builder; [SideAdsSettings.Builder.interstitial] gibi bloklarda kullanılır. */
    class Builder internal constructor(private val type: AdType) {
        var enabled: Boolean = true
        var preload: Boolean = false

        /** Verilmezse ilk eklenen placement. */
        var defaultPlacement: String? = null

        /** Açılışta yüklenecek placement id'leri; boşsa yalnızca default placement. */
        var initialPreloadPlacements: List<String> = emptyList()

        private val placements = LinkedHashMap<String, PlacementSettings>()

        /** Tek ad unit'li placement ekler. */
        @JvmOverloads
        fun addPlacement(
            id: String,
            adUnitId: String,
            enabled: Boolean = true,
            preload: Boolean = true,
            reloadAfterShow: Boolean = true
        ) = addPlacement(id, listOf(adUnitId), enabled, preload, reloadAfterShow)

        /** Sırayla denenecek ad unit zinciriyle placement ekler. Aynı id tekrar verilirse eskisinin yerine geçer. */
        @JvmOverloads
        fun addPlacement(
            id: String,
            adUnitIds: List<String>,
            enabled: Boolean = true,
            preload: Boolean = true,
            reloadAfterShow: Boolean = true
        ) {
            placements[id] = PlacementSettings(id, adUnitIds.filter { it.isNotBlank() }, enabled, preload, reloadAfterShow)
            if (defaultPlacement == null) defaultPlacement = id
        }

        internal fun seedFrom(source: FormatSettings) {
            enabled = source.enabled
            preload = source.preload
            defaultPlacement = source.defaultPlacementId
            initialPreloadPlacements = source.initialPreloadPlacementIds
            placements.putAll(source.placements)
        }

        internal fun build() = FormatSettings(
            type, enabled, preload, defaultPlacement, placements.toMap(), initialPreloadPlacements
        )
    }
}

/**
 * Kütüphanenin tüm ayarları. Değişmezdir; Kotlin'de [sideAdsSettings] DSL'i, Java'da [Builder] ya da
 * Remote Config için [fromJson] ile kurulur. Kısmi değişiklik için [newBuilder].
 *
 * `testMode`, `admobTestDeviceIds`, `childDirected`, `doNotSell` ve [mediatorSettings] JSON'dan okunmaz;
 * yalnızca koddan (JSON ile kullanımda `localSettings` bloğundan) verilir.
 */
class SideAdsSettings private constructor(
    /** Global kapatma anahtarı. `false` ise SDK hiç başlatılmaz. */
    val adsEnabled: Boolean,
    /** İki interstitial arasındaki en kısa süre (sn). */
    val interstitialMinIntervalSec: Int,
    /** Son interstitial/rewarded gösteriminden sonra app open için gereken en kısa süre (sn). */
    val appOpenMinGapSec: Int,
    /** İki app open arasındaki en kısa süre (sn); 0 = sınırsız. */
    val appOpenMinIntervalSec: Int,
    /** Oturum başına en fazla interstitial; 0 = sınırsız. */
    val sessionMaxInterstitials: Int,
    /** Açılıştan ilk interstitial'a kadar beklenecek süre (sn). */
    val firstInterstitialDelaySec: Int,
    /** Yükleme tamamen başarısız olunca ilk retry gecikmesi (sn); her denemede iki katına çıkar. */
    val retryBaseDelaySec: Int,
    /** Retry gecikmesinin tavanı (sn). */
    val retryMaxDelaySec: Int,
    /** Banner için en fazla otomatik retry; 0 = sınırsız. Yeni banner gösterme çağrısı sayacı sıfırlar. */
    val maxBannerRetryCount: Int,
    /**
     * `true` ise banner'lar application context ile yaratılır ve activity'ler arası paylaşılır. Bazı adaptörler
     * (ör. Unity) banner için Activity context istediğinden varsayılan `false`'tur.
     */
    val reuseBannersAcrossActivities: Boolean,
    /** Yüklenen fullscreen reklamın geçerlilik süresi (dk). */
    val adTtlMinutes: Int,
    /** Test modu: test cihazlarını ve UMP debug coğrafyasını devreye alır, debug log'u açar. */
    val testMode: Boolean,
    /** Debug logları (testMode açıksa her zaman açık). */
    val debugLogging: Boolean,
    /** `true` ise UMP consent alınana kadar SDK başlatılmaz; bkz. [SideAds.consent]. */
    val umpConsentEnabled: Boolean,
    /** COPPA: çocuklara yönelik muamele. null = belirtilmemiş. */
    val childDirected: Boolean?,
    /** US eyalet gizlilik yasaları: "satma" (do not sell). null = belirtilmemiş. */
    val doNotSell: Boolean?,
    /** Test cihazlarının hashed id'leri (GMA/UMP logundan). UMP debug coğrafyası için de kullanılır. */
    val admobTestDeviceIds: List<String>,
    /** Mediator'a özgü ayarlar (ör. ileride MAX modülünün SDK key'i). Mediator modülleri tanımlar. */
    val mediatorSettings: Map<String, Any>,
    val formats: Map<AdType, FormatSettings>
) {

    /** Formatın ayarları; o format için hiç blok verilmediyse null. */
    fun format(type: AdType): FormatSettings? = formats[type]

    /** Global anahtar ve format açık mı? */
    fun isFormatEnabled(type: AdType): Boolean = adsEnabled && formats[type]?.enabled == true

    /** Bu ayarlarla doldurulmuş yeni bir builder; birkaç alanı değiştirip yeni ayar üretmek için. */
    fun newBuilder(): Builder = Builder().also { it.seedFrom(this) }

    companion object {
        /**
         * JSON'dan ayar üretir (ör. Firebase Remote Config). [localSettings] JSON'dan sonra uygulanır;
         * JSON'dan okunmayan alanlar (testMode, test cihazları, gizlilik bayrakları) burada verilir.
         *
         * @throws org.json.JSONException JSON geçersizse.
         */
        @JvmStatic
        @JvmOverloads
        fun fromJson(json: String, localSettings: (Builder.() -> Unit)? = null): SideAdsSettings =
            SettingsJson.parse(json, localSettings)
    }

    /** Java'dan ve [sideAdsSettings] DSL'inden kullanılan builder. Varsayılanlar alanların yanında. */
    class Builder {
        var adsEnabled: Boolean = true
        var interstitialMinIntervalSec: Int = 30
        var appOpenMinGapSec: Int = 30
        var appOpenMinIntervalSec: Int = 0
        var sessionMaxInterstitials: Int = 0
        var firstInterstitialDelaySec: Int = 0
        var retryBaseDelaySec: Int = 8
        var retryMaxDelaySec: Int = 64
        var maxBannerRetryCount: Int = 5
        var reuseBannersAcrossActivities: Boolean = false
        var adTtlMinutes: Int = 55
        var testMode: Boolean = false
        var debugLogging: Boolean = false
        var umpConsentEnabled: Boolean = false
        var childDirected: Boolean? = null
        var doNotSell: Boolean? = null
        var admobTestDeviceIds: List<String> = emptyList()

        private val mediatorSettings = LinkedHashMap<String, Any>()
        private val formats = LinkedHashMap<AdType, FormatSettings.Builder>()

        /** Interstitial ayarları ve placement'ları. Birden çok kez çağrılırsa aynı builder'a eklenir. */
        fun interstitial(block: FormatSettings.Builder.() -> Unit) = format(AdType.INTERSTITIAL, block)

        /** Rewarded ayarları ve placement'ları. */
        fun rewarded(block: FormatSettings.Builder.() -> Unit) = format(AdType.REWARDED, block)

        /** App open ayarları ve placement'ları. */
        fun appOpen(block: FormatSettings.Builder.() -> Unit) = format(AdType.APP_OPEN, block)

        /** Banner placement'ları (banner'lar `SideAds.banner.show` ile yüklenir; preload etkilemez). */
        fun banner(block: FormatSettings.Builder.() -> Unit) = format(AdType.BANNER, block)

        internal fun format(type: AdType, block: FormatSettings.Builder.() -> Unit) {
            formats.getOrPut(type) { FormatSettings.Builder(type) }.apply(block)
        }

        /** Mediator'a özgü bir ayar yazar; null değer ayarı siler. Anahtarları mediator modülleri belgeler. */
        fun mediatorSetting(key: String, value: Any?) {
            if (value == null) mediatorSettings.remove(key) else mediatorSettings[key] = value
        }

        /** Daha önce yazılmış mediator ayarı; yoksa null. */
        fun mediatorSetting(key: String): Any? = mediatorSettings[key]

        internal fun seedFrom(source: SideAdsSettings) {
            adsEnabled = source.adsEnabled
            interstitialMinIntervalSec = source.interstitialMinIntervalSec
            appOpenMinGapSec = source.appOpenMinGapSec
            appOpenMinIntervalSec = source.appOpenMinIntervalSec
            sessionMaxInterstitials = source.sessionMaxInterstitials
            firstInterstitialDelaySec = source.firstInterstitialDelaySec
            retryBaseDelaySec = source.retryBaseDelaySec
            retryMaxDelaySec = source.retryMaxDelaySec
            maxBannerRetryCount = source.maxBannerRetryCount
            reuseBannersAcrossActivities = source.reuseBannersAcrossActivities
            adTtlMinutes = source.adTtlMinutes
            testMode = source.testMode
            debugLogging = source.debugLogging
            umpConsentEnabled = source.umpConsentEnabled
            childDirected = source.childDirected
            doNotSell = source.doNotSell
            admobTestDeviceIds = source.admobTestDeviceIds
            mediatorSettings.putAll(source.mediatorSettings)
            source.formats.forEach { (type, fs) -> format(type) { seedFrom(fs) } }
        }

        /** Değişmez ayarı üretir. */
        fun build(): SideAdsSettings = SideAdsSettings(
            adsEnabled = adsEnabled,
            interstitialMinIntervalSec = interstitialMinIntervalSec,
            appOpenMinGapSec = appOpenMinGapSec,
            appOpenMinIntervalSec = appOpenMinIntervalSec,
            sessionMaxInterstitials = sessionMaxInterstitials,
            firstInterstitialDelaySec = firstInterstitialDelaySec,
            retryBaseDelaySec = retryBaseDelaySec,
            retryMaxDelaySec = retryMaxDelaySec,
            maxBannerRetryCount = maxBannerRetryCount,
            reuseBannersAcrossActivities = reuseBannersAcrossActivities,
            adTtlMinutes = adTtlMinutes,
            testMode = testMode,
            debugLogging = debugLogging || testMode,
            umpConsentEnabled = umpConsentEnabled,
            childDirected = childDirected,
            doNotSell = doNotSell,
            admobTestDeviceIds = admobTestDeviceIds,
            mediatorSettings = mediatorSettings.toMap(),
            formats = formats.mapValues { it.value.build() }
        )
    }
}

/**
 * Kotlin DSL'i:
 * ```
 * val settings = sideAdsSettings {
 *     interstitial {
 *         preload = true
 *         addPlacement("inter_default", "ca-app-pub-…/111")
 *     }
 * }
 * ```
 */
fun sideAdsSettings(block: SideAdsSettings.Builder.() -> Unit): SideAdsSettings =
    SideAdsSettings.Builder().apply(block).build()
