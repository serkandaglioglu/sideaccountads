package com.sideaccountapps.ads.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.ViewGroup
import com.sideaccountapps.ads.core.internal.AppOpenAutopilot
import com.sideaccountapps.ads.core.internal.BannerFlow
import com.sideaccountapps.ads.core.internal.BannerSlots
import com.sideaccountapps.ads.core.internal.Dispatch
import com.sideaccountapps.ads.core.internal.Engine
import com.sideaccountapps.ads.core.internal.Logger
import com.sideaccountapps.ads.core.internal.Main
import com.sideaccountapps.ads.core.internal.SettingsJson
import com.sideaccountapps.ads.core.internal.UmpConsent
import com.sideaccountapps.ads.core.mediation.ServerSideVerification

/**
 * Kütüphanenin giriş noktası. Mediator (ör. AdMob) eklenen modülden otomatik bulunur; uygulama kodu mediator'dan
 * bağımsızdır.
 *
 * ```
 * // Application.onCreate
 * SideAds.init(this, sideAdsSettings {
 *     interstitial {
 *         preload = true
 *         addPlacement("inter_default", "ca-app-pub-…/111")
 *     }
 * })
 *
 * // Gösterim (main thread)
 * SideAds.interstitial.show(activity)
 * ```
 */
object SideAds {

    /** Interstitial yükleme ve gösterimi. */
    @JvmField
    val interstitial = InterstitialAds()

    /** Rewarded yükleme ve gösterimi. */
    @JvmField
    val rewarded = RewardedAds()

    /** App open yükleme, gösterim ve otomatik gösterim. */
    @JvmField
    val appOpen = AppOpenAds()

    /** Banner gösterimi. */
    @JvmField
    val banner = BannerAds()

    /** UMP (GDPR) consent akışı. */
    @JvmField
    val consent = ConsentFlow()

    /**
     * Kütüphaneyi başlatır; `Application.onCreate` içinde çağrılır. SDK arka planda başlar; yükleme istekleri init
     * bitene kadar kuyrukta bekler. `umpConsentEnabled` ise SDK [ConsentFlow.request] ile başlar: kayıtlı consent
     * varsa çağrıldığı anda, yoksa form cevaplanınca.
     *
     * @param onReady SDK hazır olunca (ya da 20 sn dolunca) bir kez çağrılır; reklamlar kapalıysa hemen.
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, settings: SideAdsSettings, onReady: Runnable? = null) {
        Engine.init(context, settings, onReady)
    }

    /**
     * JSON ayarla başlatır (ör. Firebase Remote Config). `testMode`, `admobTestDeviceIds`, `childDirected`,
     * `doNotSell` JSON'dan okunmaz; [localSettings] bloğunda verilir. Blok saklanır ve her [setSettings] (JSON)
     * çağrısında yeniden uygulanır. JSON okunamazsa yalnızca [localSettings] ile (placement'sız) başlatılır.
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        settingsJson: String,
        localSettings: (SideAdsSettings.Builder.() -> Unit)? = null,
        onReady: Runnable? = null
    ) {
        Engine.localSettings = localSettings
        val parsed = SettingsJson.parseOrNull(settingsJson, localSettings)
            ?: SideAdsSettings.Builder().apply { localSettings?.invoke(this) }.build()
        Engine.init(context, parsed, onReady)
    }

    /** Geçerli ayarlar; init'ten önce null. */
    @JvmStatic
    val settings: SideAdsSettings?
        get() = Engine.settings

    /**
     * Ayarları çalışma anında değiştirir. Yüklü reklamlar korunur; yeni preload ayarları hemen uygulanır, kapatılan
     * banner'lar destroy edilir. Mediator'ın init'te kullandığı alanların (test cihazları, mediator ayarları)
     * sonradan değişmesinin etkisi yoktur.
     */
    @JvmStatic
    fun setSettings(settings: SideAdsSettings) = Engine.setSettings(settings)

    /**
     * JSON ile ayar günceller (ör. Remote Config fetch sonrası). [localSettings] verilmezse init'teki blok yeniden
     * uygulanır. JSON okunamazsa `false` döner ve mevcut ayar korunur.
     */
    @JvmStatic
    @JvmOverloads
    fun setSettings(
        settingsJson: String,
        localSettings: (SideAdsSettings.Builder.() -> Unit)? = Engine.localSettings
    ): Boolean {
        val parsed = SettingsJson.parseOrNull(settingsJson, localSettings) ?: return false
        Engine.localSettings = localSettings
        Engine.setSettings(parsed)
        return true
    }

    /**
     * Mevcut ayarın yalnızca verilen alanlarını değiştirir: `SideAds.editSettings { appOpenMinIntervalSec = 60 }`.
     * Sonraki bir JSON güncellemesi ayarı sıfırdan kurduğu için bu değişiklikleri ezer. Init'ten önce `false` döner.
     */
    @JvmStatic
    fun editSettings(block: SideAdsSettings.Builder.() -> Unit): Boolean {
        val current = Engine.settings ?: run {
            Logger.e("editSettings ignored: call SideAds.init first")
            return false
        }
        Engine.setSettings(current.newBuilder().apply(block).build())
        return true
    }

    /** Tüm reklam olayları (main thread'de). */
    @JvmStatic
    var listener: SideAdsListener?
        get() = Dispatch.listener
        set(value) {
            Dispatch.listener = value
        }

    /** Gösterim başına gelir (banner dahil tüm formatlar). */
    @JvmStatic
    var revenueListener: RevenueListener?
        get() = Dispatch.revenueListener
        set(value) {
            Dispatch.revenueListener = value
        }

    /** Kütüphane logları (ör. uyarıları analytics'e göndermek için). */
    @JvmStatic
    var logListener: LogListener?
        get() = Logger.listener
        set(value) {
            Logger.listener = value
        }

    /** Mediation test ekranını açar (AdMob'da Ad Inspector): ağların kurulumu ve tek ağ testi için. */
    @JvmStatic
    fun openDebugger(activity: Activity) = Engine.openDebugger(activity)
}

/** Interstitial işlemleri: [SideAds.interstitial]. Gösterim çağrıları main thread'den yapılır. */
class InterstitialAds internal constructor() {

    /** Yüklemeyi başlatır (preload açıksa genellikle gerekmez). Geçerli reklam varken ya da yükleme sürerken etkisizdir. */
    @JvmOverloads
    fun load(placementId: String? = null) = Engine.load(AdType.INTERSTITIAL, placementId)

    /**
     * Yükler ve sonucu yalnızca [callbacks]'e bir kez bildirir; genel [SideAds.listener]'a dokunmak gerekmez (ör.
     * splash). Reklam zaten yüklüyse onLoaded hemen gelir; yükleme sürüyorsa onun sonucu beklenir. Placement
     * kapalıysa, bağlantı yoksa ya da SDK consent yüzünden başlamadıysa onLoadFailed hemen gelir (son ikisinde yükleme
     * yine kuyruğa girer). Kütüphanede yükleme zaman aşımı yoktur; ne kadar bekleneceğine uygulama karar verir.
     */
    fun load(placementId: String?, callbacks: LoadCallbacks) = Engine.load(AdType.INTERSTITIAL, placementId, callbacks)

    /** Gösterilebilir (yüklü ve süresi dolmamış) reklam var mı? */
    @JvmOverloads
    fun isReady(placementId: String? = null): Boolean = Engine.isReady(AdType.INTERSTITIAL, placementId)

    /**
     * Frekans kurallarıyla gösterir. Gösterim başladıysa `true`; reklam hazır değilse `false` döner ve yükleme
     * tetiklenir. Başka bir fullscreen reklam açıkken ya da kurallar engellerse `false`.
     */
    @JvmOverloads
    fun show(activity: Activity, placementId: String? = null, callbacks: ShowCallbacks? = null): Boolean =
        Engine.show(AdType.INTERSTITIAL, activity, placementId, false, callbacks, null)

    /** [show] gibi, ama en kısa aralık ve ilk gecikme kurallarını atlar (oturum sınırı ve fullscreen kilidi geçerli). */
    @JvmOverloads
    fun showIgnoringTimeRules(activity: Activity, placementId: String? = null, callbacks: ShowCallbacks? = null): Boolean =
        Engine.show(AdType.INTERSTITIAL, activity, placementId, true, callbacks, null)
}

/** Rewarded işlemleri: [SideAds.rewarded]. */
class RewardedAds internal constructor() {

    /** Yüklemeyi başlatır (preload açıksa genellikle gerekmez). */
    @JvmOverloads
    fun load(placementId: String? = null) = Engine.load(AdType.REWARDED, placementId)

    /** Yükler, sonucu yalnızca [callbacks]'e bir kez bildirir; kurallar [InterstitialAds.load] ile aynı. */
    fun load(placementId: String?, callbacks: LoadCallbacks) = Engine.load(AdType.REWARDED, placementId, callbacks)

    /** Gösterilebilir reklam var mı? */
    @JvmOverloads
    fun isReady(placementId: String? = null): Boolean = Engine.isReady(AdType.REWARDED, placementId)

    /**
     * Gösterir; ödül kazanılınca [onReward] çağrılır (gösterim bittikten sonra gelse bile). Gösterim başladıysa
     * `true`; hazır değilse `false` döner ve yükleme tetiklenir.
     */
    @JvmOverloads
    fun show(
        activity: Activity,
        placementId: String? = null,
        callbacks: ShowCallbacks? = null,
        onReward: RewardCallback
    ): Boolean = Engine.show(AdType.REWARDED, activity, placementId, false, callbacks, onReward)

    /** Server-side verification için kullanıcı id'si ve özel veri; sonraki gösterimlerde uygulanır. null'lar kapatır. */
    fun setServerSideVerification(userId: String?, customData: String?) {
        Engine.ssv = if (userId == null && customData == null) null else ServerSideVerification(userId, customData)
    }
}

/** App open işlemleri: [SideAds.appOpen]. */
class AppOpenAds internal constructor() {

    /** Yüklemeyi başlatır (preload açıksa genellikle gerekmez). */
    @JvmOverloads
    fun load(placementId: String? = null) = Engine.load(AdType.APP_OPEN, placementId)

    /** Yükler, sonucu yalnızca [callbacks]'e bir kez bildirir; kurallar [InterstitialAds.load] ile aynı. */
    fun load(placementId: String?, callbacks: LoadCallbacks) = Engine.load(AdType.APP_OPEN, placementId, callbacks)

    /** Gösterilebilir reklam var mı? */
    @JvmOverloads
    fun isReady(placementId: String? = null): Boolean = Engine.isReady(AdType.APP_OPEN, placementId)

    /**
     * Gösterir. Başka bir fullscreen reklam açıkken, son interstitial/rewarded üzerinden `appOpenMinGapSec` ya da son
     * app open üzerinden `appOpenMinIntervalSec` geçmeden gösterilmez.
     */
    @JvmOverloads
    fun show(activity: Activity, placementId: String? = null, callbacks: ShowCallbacks? = null): Boolean =
        Engine.show(AdType.APP_OPEN, activity, placementId, false, callbacks, null)

    /**
     * Uygulama ön plana dönünce app open'ı otomatik gösterir (aynı kurallarla). `Application.onCreate`'te çağrılır.
     *
     * @param showOnColdStart soğuk açılışta da gösterilsin mi.
     * @param excludedActivities bu activity'ler öndeyken gösterilmez (ör. splash, satın alma).
     * @param excludePredicate `true` dönerse gösterilmez (dinamik kontrol).
     */
    @JvmOverloads
    fun enableAutoShow(
        application: Application,
        placementId: String? = null,
        showOnColdStart: Boolean = false,
        excludedActivities: Set<Class<out Activity>> = emptySet(),
        excludePredicate: ActivityPredicate? = null
    ) = Main.run {
        AppOpenAutopilot.enable(application, placementId, showOnColdStart, excludedActivities, excludePredicate)
    }

    /** Otomatik gösterimi kapatır; elle [show] çalışmaya devam eder. */
    fun disableAutoShow() = AppOpenAutopilot.disable()
}

/** Banner işlemleri: [SideAds.banner]. Main thread'den çağrılır. */
class BannerAds internal constructor() {

    /**
     * Banner'ı yükleyip [container]'a yerleştirir. Aynı activity + placement + boyut için canlı bir banner varsa yeni
     * istek atılmaz, mevcut banner taşınır. Boyut değiştiyse eskisi destroy edilip yenisi yüklenir.
     *
     * ADAPTIVE genişliği [container]'ın (padding hariç) genişliğidir: container henüz layout olmadıysa yükleme ilk
     * layout'u bekler; GONE ise pencere genişliği kullanılır. Banner daha dar bir container'a taşınırsa yeni
     * genişlikle yeniden yüklenir.
     *
     * @return banner'ı yönetmek için slot; ayar/placement kapalıysa null.
     */
    @JvmOverloads
    fun show(
        activity: Activity,
        container: ViewGroup,
        size: BannerSize = BannerSize.ADAPTIVE,
        placementId: String? = null
    ): BannerSlot? = BannerFlow.show(activity, container, size, placementId, null)

    /**
     * [show] gibi; ayrıca bu banner'ın olayları [callbacks]'e gelir (ör. kartı reklam gelince göstermek, banner
     * kaldırılınca gizlemek). Cache'teki banner tekrar gösterilirse olaylar son verilen callback'lere gider ve reklam
     * zaten yüklüyse onLoaded hemen çağrılır. null dönerse callback çağrılmaz.
     */
    fun show(
        activity: Activity,
        container: ViewGroup,
        size: BannerSize = BannerSize.ADAPTIVE,
        placementId: String? = null,
        callbacks: BannerCallbacks
    ): BannerSlot? = BannerFlow.show(activity, container, size, placementId, callbacks)

    /** [placementId] null ise tüm banner'ları, değilse o placement'ınkileri destroy eder. */
    @JvmOverloads
    fun destroy(placementId: String? = null) = Main.run { BannerSlots.destroy(placementId) }
}

/** UMP consent işlemleri: [SideAds.consent]. */
class ConsentFlow internal constructor() {

    /**
     * Consent bilgisini günceller, gerekiyorsa formu gösterir. Kayıtlı consent varsa SDK'yı çağrıldığı anda (ağ isteğini
     * ve [callback]'i beklemeden), yoksa form cevaplanınca başlatır. İlk activity'de (ör. splash) her açılışta
     * çağrılması önerilir; form yalnızca gerektiğinde görünür.
     */
    @JvmOverloads
    fun request(activity: Activity, callback: ConsentCallback? = null) = Engine.requestConsent(activity, callback)

    /**
     * Reklam istenebilir mi (UMP)? Değer ancak bu süreçte [request] çağrıldıktan sonra güvenilirdir: UMP kayıtlı
     * consent'i bile o çağrıdan önce bildirmez (`false` döner). [request] döner dönmez önceki oturumun consent'ini
     * yansıtır; ör. splash'te form ve ağ isteği beklenmeden devam etmek ya da consent yokken banner istememek için.
     * `umpConsentEnabled = false` ise (consent'i kütüphane yönetmiyor) [request] gibi her zaman `true`.
     */
    fun canRequestAds(context: Context): Boolean =
        Engine.settings?.umpConsentEnabled == false || UmpConsent.canRequestAds(context)

    /** Ayarlar ekranında "gizlilik seçenekleri" girişi gösterilmeli mi? */
    fun requiresPrivacyOptions(context: Context): Boolean = UmpConsent.requiresPrivacyOptions(context)

    /** Gizlilik seçenekleri formunu açar. */
    @JvmOverloads
    fun showPrivacyOptions(activity: Activity, onDismissed: Runnable? = null) =
        UmpConsent.showPrivacyOptions(activity, onDismissed)

    /** Yalnızca test için: kayıtlı consent durumunu siler. */
    fun resetForTesting(context: Context) = UmpConsent.reset(context)
}
