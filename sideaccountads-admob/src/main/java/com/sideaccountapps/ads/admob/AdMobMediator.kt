package com.sideaccountapps.ads.admob

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.applovin.sdk.AppLovinPrivacySettings
import com.bytedance.sdk.openadsdk.api.PAGConstant
import com.facebook.ads.AdSettings
import com.fyber.inneractive.sdk.external.InneractiveAdManager
import com.google.ads.mediation.pangle.PangleMediationAdapter
import com.google.android.gms.ads.AgeRestrictedTreatment
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.initialization.AdapterStatus
import com.mbridge.msdk.out.MBridgeSDKFactory
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.SideAdsSettings
import com.sideaccountapps.ads.core.mediation.AdMediator
import com.sideaccountapps.ads.core.mediation.BannerEvents
import com.sideaccountapps.ads.core.mediation.FullscreenLoadListener
import com.sideaccountapps.ads.core.mediation.MediatedBanner
import com.sideaccountapps.ads.core.mediation.MediatorLog
import com.unity3d.ads.metadata.MetaData
import com.vungle.ads.VunglePrivacySettings

/**
 * AdMob mediation (legacy GMA SDK) mediator'ı. Uygulama doğrudan kullanmaz: core, bu modülün manifest'indeki
 * `com.sideaccountapps.ads.MEDIATOR` meta-data'sından sınıfı bulur ve yaratır.
 *
 * Hangi ağların yarışacağı (bidding/waterfall) AdMob konsolundaki mediation group'larda ayarlanır.
 */
class AdMobMediator : AdMediator {

    override val name = "AdMob"

    /** GMA'nın ve yedi ağın fullscreen activity paketleri (SDK AAR manifest'lerinden). */
    override val adActivityPrefixes = listOf(
        "com.google.android.gms.ads.",   // GMA AdActivity
        "com.applovin.",                 // AppLovin
        "com.facebook.ads.",             // Meta Audience Network
        "com.mbridge.msdk.",             // Mintegral
        "com.bytedance.sdk.openadsdk.",  // Pangle
        "com.unity3d.ads.",              // Unity Ads
        "com.vungle.ads.",               // Liftoff Monetize
        "com.fyber.inneractive.sdk."     // DT Exchange
    )

    @Volatile
    private var sdkReady = false
    private var appContext: Context? = null

    override fun applyPrivacy(context: Context, settings: SideAdsSettings) {
        appContext = context.applicationContext
        MobileAds.setRequestConfiguration(requestConfiguration(settings))
        settings.doNotSell?.let { applyDoNotSell(context, it) }
    }

    override fun initialize(context: Context, settings: SideAdsSettings, onReady: () -> Unit) {
        // Google: init arka plan thread'inde; mediation'da yüklemeler tamamlanma callback'ini bekler.
        Thread({
            MobileAds.initialize(context) { status ->
                status.adapterStatusMap.forEach { (adapter, adapterStatus) ->
                    val line = ("Adapter $adapter: ${adapterStatus.initializationState} " +
                        "(${adapterStatus.latency} ms) ${adapterStatus.description}").trimEnd()
                    if (adapterStatus.initializationState == AdapterStatus.State.READY) MediatorLog.d(line)
                    else MediatorLog.w(line)
                }
                sdkReady = true
                // Mintegral'in çağrısı Google'a göre GMA init'inden sonra, ilk istekten önce yapılır.
                settings.doNotSell?.let { applyMintegralDoNotSell(it) }
                onReady()
            }
        }, "SideAds-init").start()
    }

    override fun loadFullscreen(context: Context, type: AdType, adUnitId: String, listener: FullscreenLoadListener) =
        AdMobAds.loadFullscreen(context, type, adUnitId, listener)

    override fun loadBanner(
        context: Context,
        host: ViewGroup,
        size: BannerSize,
        adUnitId: String,
        events: BannerEvents
    ): MediatedBanner = AdMobAds.loadBanner(context, host, size, adUnitId, events)

    override fun openDebugger(activity: Activity) {
        MobileAds.openAdInspector(activity) { error ->
            error?.let { MediatorLog.w("Ad Inspector closed with error: ${it.code} ${it.message}") }
        }
    }

    /** Test cihazları ve yaş sinyali. Uygulamanın kendi koyduğu diğer ayarlar korunur. */
    private fun requestConfiguration(settings: SideAdsSettings): RequestConfiguration =
        MobileAds.getRequestConfiguration().toBuilder().apply {
            if (settings.admobTestDeviceIds.isNotEmpty()) setTestDeviceIds(settings.admobTestDeviceIds)
            settings.childDirected?.let { child ->
                setAgeRestrictedTreatment(if (child) AgeRestrictedTreatment.CHILD else AgeRestrictedTreatment.UNSPECIFIED)
                // Unity adaptörü yaş sinyalini değil yalnızca bu (deprecated) bayrağı iletiyor.
                @Suppress("DEPRECATION")
                setTagForChildDirectedTreatment(
                    if (child) RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                    else RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                )
            }
        }.build()

    /**
     * US eyalet gizlilik yasaları ("do not sell"). GMA bunu ağlara iletmez; Google'ın her ağ için verdiği çağrı
     * yapılır. Değerler ağların dokümanı ve SDK bytecode'u ile doğrulandı.
     */
    private fun applyDoNotSell(context: Context, optOut: Boolean) {
        // Google talebi: restricted data processing. SDK her yüklemede default SharedPreferences'tan okur.
        safely("RDP") {
            val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            if (optOut) prefs.edit().putInt("gad_rdp", 1).apply() else prefs.edit().remove("gad_rdp").apply()
        }
        safely("AppLovin") { AppLovinPrivacySettings.setDoNotSell(optOut) }
        safely("Meta") {
            // Limited Data Use; 0, 0: konumu Meta belirler.
            if (optOut) AdSettings.setDataProcessingOptions(arrayOf("LDU"), 0, 0)
            else AdSettings.setDataProcessingOptions(arrayOf())
        }
        safely("Pangle") {
            PangleMediationAdapter.setPAConsent(
                if (optOut) PAGConstant.PAGPAConsentType.PAG_PA_CONSENT_TYPE_NO_CONSENT
                else PAGConstant.PAGPAConsentType.PAG_PA_CONSENT_TYPE_CONSENT
            )
        }
        safely("Unity Ads") {
            // Unity'nin ve Google'ın dokümanındaki tek yol bu constructor (SDK'da deprecated işaretli).
            @Suppress("DEPRECATION")
            MetaData(context).apply { set("privacy.consent", !optOut) }.commit()
        }
        safely("Liftoff") { VunglePrivacySettings.setCCPAStatus(!optOut) } // true = opt-in
        safely("DT Exchange") { InneractiveAdManager.setUSPrivacyString(if (optOut) "1YYN" else "1YNN") }
        if (sdkReady) applyMintegralDoNotSell(optOut)
    }

    private fun applyMintegralDoNotSell(optOut: Boolean) {
        val context = appContext ?: return
        safely("Mintegral") { MBridgeSDKFactory.getMBridgeSDK().setDoNotTrackStatus(context, optOut) }
    }

    private inline fun safely(network: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            MediatorLog.w("$network privacy setting could not be applied: ${t.message}")
        }
    }
}
