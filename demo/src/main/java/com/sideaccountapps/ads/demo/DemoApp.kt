package com.sideaccountapps.ads.demo

import android.app.Application
import android.util.Log
import com.sideaccountapps.ads.core.AdDetails
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.LogLevel
import com.sideaccountapps.ads.core.LogListener
import com.sideaccountapps.ads.core.RevenueListener
import com.sideaccountapps.ads.core.Reward
import com.sideaccountapps.ads.core.SideAds
import com.sideaccountapps.ads.core.SideAdsListener
import com.sideaccountapps.ads.core.sideAdsSettings

/**
 * Referans demo: ayarların **tamamı** varsayılan değerleri yorumda olacak şekilde açıkça yazılmıştır. Reklamlar
 * Google'ın resmi test ad unit'leriyle gelir. Kütüphanenin logları için: `adb logcat -s SideAds Demo`.
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Listener'lar init'ten önce verilir ki açılıştaki yükleme olayları da gelsin.
        SideAds.listener = object : SideAdsListener {
            override fun onLoaded(ad: AdDetails) = log("Loaded: $ad")
            override fun onLoadFailed(type: AdType, placementId: String, error: String) =
                log("Load failed: $type/$placementId -> $error")
            override fun onShown(ad: AdDetails) = log("Shown: $ad")
            override fun onShowFailed(ad: AdDetails, error: String) = log("Show failed: $ad -> $error")
            override fun onDismissed(ad: AdDetails) = log("Dismissed: $ad")
            override fun onClicked(ad: AdDetails) = log("Clicked: $ad")
            override fun onRewarded(ad: AdDetails, reward: Reward) = log("Rewarded: ${reward.amount} ${reward.type}")
        }
        // Gösterim başına gelir; Firebase/Adjust/AppsFlyer'a buradan iletilir.
        SideAds.revenueListener = RevenueListener { event ->
            log("Revenue: ${event.value} ${event.currencyCode} (${event.precision}) from ${event.adSourceName} for ${event.type}/${event.placementId}")
        }
        // Kütüphanenin uyarılarını kendi log/analytics altyapınıza aktarmak için.
        SideAds.logListener = LogListener { level, message ->
            if (level == LogLevel.ERROR) log("SideAds error: $message")
        }

        val settings = sideAdsSettings {
            // ------------------------------------------------------------------ genel
            adsEnabled = true                    // global anahtar; false ise SDK hiç başlamaz (varsayılan: true)

            // ------------------------------------------------------------------ gösterim kuralları
            interstitialMinIntervalSec = 15      // iki interstitial arası en kısa süre (varsayılan: 30)
            appOpenMinGapSec = 10                // son inter/rewarded sonrası app open için boşluk (varsayılan: 30)
            appOpenMinIntervalSec = 0            // iki app open arası en kısa süre; 0 = sınırsız (varsayılan: 0)
            sessionMaxInterstitials = 0          // oturum başına en fazla interstitial; 0 = sınırsız (varsayılan: 0)
            firstInterstitialDelaySec = 0        // açılıştan ilk interstitial'a bekleme (varsayılan: 0)

            // ------------------------------------------------------------------ yükleme, retry, süre
            retryBaseDelaySec = 8                // tüm ad unit'ler başarısız olunca ilk retry (varsayılan: 8)
            retryMaxDelaySec = 64                // retry gecikmesi tavanı (varsayılan: 64)
            maxBannerRetryCount = 5              // banner retry sınırı; 0 = sınırsız (varsayılan: 5)
            reuseBannersAcrossActivities = false // true: banner activity'ler arası paylaşılır (varsayılan: false)
            adTtlMinutes = 55                    // yüklü fullscreen reklamın ömrü (varsayılan: 55)

            // ------------------------------------------------------------------ test ve log
            testMode = BuildConfig.DEBUG         // test cihazları + UMP debug coğrafyası + debug log (varsayılan: false)
            debugLogging = true                  // testMode açıksa zaten açık (varsayılan: false)
            // GMA/UMP logundaki hashed id; boşsa gerçek reklam gelir (test ad unit'leri hariç) (varsayılan: boş)
            admobTestDeviceIds = emptyList()     // örn. listOf("33BE2250B43518CCDA7DE426D04EE231")

            // ------------------------------------------------------------------ gizlilik
            umpConsentEnabled = false            // true: consent alınana kadar SDK başlamaz (varsayılan: false)
            childDirected = null                 // COPPA; null = belirtilmemiş (varsayılan: null)
            doNotSell = null                     // US eyalet yasaları "do not sell"; null = belirtilmemiş (varsayılan: null)

            // ------------------------------------------------------------------ formatlar
            interstitial {
                enabled = true                   // (varsayılan: true)
                preload = true                   // açılışta ve gösterim sonrası otomatik yükleme (varsayılan: false)
                defaultPlacement = "inter_default"                          // (varsayılan: ilk eklenen)
                initialPreloadPlacements = listOf("inter_default", "inter_chain") // (varsayılan: boş = yalnızca default)

                addPlacement(
                    id = "inter_default",
                    adUnitId = TEST_INTERSTITIAL,
                    enabled = true,              // (varsayılan: true)
                    preload = true,              // false: yalnızca load() / show() ile yüklenir (varsayılan: true)
                    reloadAfterShow = true       // false: tek kullanımlık, retry de yok (varsayılan: true)
                )
                // Ad unit zinciri: sırayla denenir. İlk unit bilerek geçersiz; hata verip ikinciye geçtiği logda
                // görülür. Gerçekte: önce yüksek floor'lu, sonra tüm fiyatlar.
                addPlacement("inter_chain", listOf(DEMO_INVALID_AD_UNIT, TEST_INTERSTITIAL))
                // Yalnızca elle yüklenen placement örneği.
                addPlacement("inter_level_end", TEST_INTERSTITIAL, preload = false)
            }

            rewarded {
                preload = true
                addPlacement("rewarded_default", TEST_REWARDED)
            }

            appOpen {
                preload = true
                addPlacement("appopen_default", TEST_APP_OPEN)
            }

            banner {
                // Banner'lar SideAds.banner.show ile yüklenir; preload banner'ı etkilemez.
                addPlacement("banner_adaptive", TEST_BANNER_ADAPTIVE)
                addPlacement("banner_fixed", TEST_BANNER_FIXED)
            }
        }

        // Rewarded server-side verification; sunucu doğrulaması kullanmıyorsanız gerekmez.
        SideAds.rewarded.setServerSideVerification(userId = "demo-user-1", customData = "demo")

        // onReady: SDK hazır olunca (ya da 20 sn dolunca). Yüklemeler bunu beklemeden kuyruğa girer.
        SideAds.init(this, settings) { log("SideAds ready") }

        // Ön plana dönüşte otomatik app open.
        SideAds.appOpen.enableAutoShow(
            application = this,
            placementId = "appopen_default",     // (varsayılan: null = default placement)
            showOnColdStart = false,             // (varsayılan: false)
            excludedActivities = emptySet(),     // örn. setOf(SplashActivity::class.java)
            excludePredicate = null              // örn. ActivityPredicate { it is PurchaseActivity }
        )
    }

    /**
     * ÖRNEK, çağrılmıyor: JSON ile başlatma (ör. Firebase Remote Config). Aşağıdaki JSON okunan **tüm** anahtarları
     * içerir. `testMode`, `admobTestDeviceIds`, `childDirected`, `doNotSell` JSON'dan okunmaz; `localSettings`
     * bloğundan gelir ve her `SideAds.setSettings(json)` çağrısında yeniden uygulanır.
     */
    @Suppress("unused")
    private fun initWithJsonExample() {
        val remoteJson = """
            {
              "adsEnabled": true,
              "debugLogging": false,
              "umpConsentEnabled": false,
              "interstitialMinIntervalSec": 30,
              "appOpenMinGapSec": 30,
              "appOpenMinIntervalSec": 0,
              "sessionMaxInterstitials": 0,
              "firstInterstitialDelaySec": 0,
              "retryBaseDelaySec": 8,
              "retryMaxDelaySec": 64,
              "maxBannerRetryCount": 5,
              "reuseBannersAcrossActivities": false,
              "adTtlMinutes": 55,
              "interstitial": {
                "enabled": true,
                "preload": true,
                "defaultPlacement": "inter_default",
                "initialPreloadPlacements": ["inter_default"],
                "placements": {
                  "inter_default": { "adUnit": "$TEST_INTERSTITIAL", "enabled": true, "preload": true, "reloadAfterShow": true },
                  "inter_chain": { "adUnit": ["$DEMO_INVALID_AD_UNIT", "$TEST_INTERSTITIAL"] },
                  "inter_level_end": { "adUnit": "$TEST_INTERSTITIAL", "preload": false }
                }
              },
              "rewarded": {
                "preload": true,
                "defaultPlacement": "rewarded_default",
                "placements": { "rewarded_default": { "adUnit": "$TEST_REWARDED" } }
              },
              "appOpen": {
                "preload": true,
                "defaultPlacement": "appopen_default",
                "placements": { "appopen_default": { "adUnit": "$TEST_APP_OPEN" } }
              },
              "banner": {
                "defaultPlacement": "banner_adaptive",
                "placements": {
                  "banner_adaptive": { "adUnit": "$TEST_BANNER_ADAPTIVE" },
                  "banner_fixed": { "adUnit": "$TEST_BANNER_FIXED" }
                }
              }
            }
        """.trimIndent()

        SideAds.init(
            context = this,
            settingsJson = remoteJson,
            localSettings = {
                testMode = BuildConfig.DEBUG
                admobTestDeviceIds = emptyList()
                childDirected = null
                doNotSell = null
            },
            onReady = { log("SideAds ready (JSON)") }
        )
        // Remote Config yeniden fetch edilince: SideAds.setSettings(newJson) (localSettings otomatik uygulanır)
    }

    private fun log(message: String) {
        Log.d("Demo", message)
    }

    companion object {
        // Google'ın resmi test ad unit'leri.
        const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
        const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"
        const val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
        const val TEST_BANNER_ADAPTIVE = "ca-app-pub-3940256099942544/9214589741"
        const val TEST_BANNER_FIXED = "ca-app-pub-3940256099942544/6300978111"

        // Bilerek var olmayan ad unit: zincirin hata verip sıradakine geçtiğini göstermek için.
        const val DEMO_INVALID_AD_UNIT = "ca-app-pub-3940256099942544/1111111111"
    }
}
