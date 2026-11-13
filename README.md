# sideaccountads

AdMob mediation tabanlı Android reklam kütüphanesi. Placement bazlı preload, retry, frekans kuralları, tek
fullscreen kuralı, app open ve banner cache'i hazır gelir. Uygulama kodu mediation SDK'sından bağımsızdır;
ileride aynı API ile AppLovin MAX mediation'a geçilebilir.

- **Formatlar:** interstitial, rewarded, app open, banner (320x50, anchored adaptive, MREC 300x250). Native yok.
- **Mediation:** AdMob mediation (legacy Google Mobile Ads SDK). Mediation ağları: AppLovin, Meta Audience Network,
  Mintegral, Pangle, Unity Ads, Liftoff Monetize, DT Exchange. Hangi ağın yarışacağı (bidding/waterfall) AdMob
  konsolunda ayarlanır; kodda ağ sırası yoktur.
- **Gereksinimler:** minSdk 24, compileSdk 36+, JDK 17. Kotlin kullanan uygulamalarda Kotlin 2.3+ (kütüphane
  Kotlin 2.4 ile derlenir). Public API Java'dan da çağrılabilir.

---

## 1. Kurulum

Uygulamaya **yalnızca bir mediator modülü** eklenir; core onunla birlikte gelir. Şu an tek mediator
`sideaccountads-admob`'dur.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Mintegral ve Pangle SDK'ları yalnızca kendi repolarında yayınlanır.
        maven { url = uri("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea") }
        maven { url = uri("https://artifact.bytedance.com/repository/pangle") }
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.serkandaglioglu.sideaccountads:sideaccountads-admob:<tag>")
}
```

`<tag>` GitHub'daki sürüm etiketidir (denemek için kısa commit hash'i ya da `main-SNAPSHOT` de olur).

> Repo seviyesindeki `com.github.serkandaglioglu:sideaccountads:<tag>` koordinatını kullanmayın. JitPack o
> koordinatı repodaki **tüm** modülleri içerecek şekilde üretir; ileride MAX modülü eklendiğinde iki mediator'ı
> birden çeker ve build (bilinçli olarak) kırılır.

`AndroidManifest.xml` (uygulama):

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

- `com.google.android.gms.permission.AD_ID` izni GMA'nın manifest'inden otomatik gelir. Yalnızca çocuklara
  yönelik bir uygulamada kaldırın: `<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />`.
- R8/ProGuard için ek kural gerekmez; gerekenler kütüphanenin ve SDK'ların AAR'larıyla gelir.

---

## 2. Başlatma

`Application.onCreate` içinde:

```kotlin
SideAds.init(this, sideAdsSettings {
    interstitialMinIntervalSec = 30
    umpConsentEnabled = true
    testMode = BuildConfig.DEBUG
    admobTestDeviceIds = listOf("33BE2250B43518CCDA7DE426D04EE231") // GMA/UMP logundaki hashed id

    interstitial {
        preload = true
        addPlacement("inter_default", "ca-app-pub-…/111")
        // Ad unit zinciri: önce yüksek floor'lu, dolmazsa tüm fiyatlar.
        addPlacement("inter_gameover", listOf("ca-app-pub-…/high_floor", "ca-app-pub-…/all_prices"))
        // Splash: tek kullanımlık; gösterimden sonra yeniden yüklenmez, hata olursa retry yok.
        addPlacement("inter_splash", "ca-app-pub-…/222", reloadAfterShow = false)
        // Yalnızca elle yüklenir (load() ya da hazır değilken show()).
        addPlacement("inter_level_end", "ca-app-pub-…/333", preload = false)
        initialPreloadPlacements = listOf("inter_default", "inter_splash")
    }
    rewarded {
        preload = true
        addPlacement("rewarded_default", "ca-app-pub-…/444")
    }
    appOpen {
        preload = true
        addPlacement("appopen_default", "ca-app-pub-…/555")
    }
    banner {
        addPlacement("banner_default", "ca-app-pub-…/666")
    }
}) {
    // İsteğe bağlı onReady: SDK hazır olunca (ya da 20 sn dolunca). Reklamlar kapalıysa hemen.
}
```

Java'dan aynı ayar `new SideAdsSettings.Builder()` ile kurulur.

- SDK arka planda başlar. Init bitene kadar yükleme istekleri kuyrukta bekler, init bitince bir kez çalışır
  (Google: mediation'da adaptörler hazır olsun diye init tamamlanmadan reklam yüklenmemeli).
- Init 20 sn içinde bitmezse uyarı loglanır ve bekleyen istekler yine çalışır.
- `umpConsentEnabled = true` ise SDK `SideAds.consent.request()` ile başlar: kayıtlı consent varsa çağrıldığı anda
  (ağ isteği ve form beklenmez), yoksa form cevaplanınca. UMP kayıtlı consent'i ancak bu çağrıdan sonra bildirdiği
  için init tek başına başlatamaz.

### Tüm ayarlar ve varsayılanları

| Alan | Varsayılan | Açıklama |
|---|---|---|
| `adsEnabled` | `true` | Global anahtar; `false` ise SDK hiç başlamaz. |
| `interstitialMinIntervalSec` | `30` | İki interstitial arası en kısa süre. |
| `appOpenMinGapSec` | `30` | Son interstitial/rewarded gösteriminden sonra app open için gereken süre. |
| `appOpenMinIntervalSec` | `0` | İki app open arası en kısa süre; 0 = sınırsız. |
| `sessionMaxInterstitials` | `0` | Oturum başına en fazla interstitial; 0 = sınırsız. |
| `firstInterstitialDelaySec` | `0` | Açılıştan ilk interstitial'a kadar bekleme. |
| `retryBaseDelaySec` / `retryMaxDelaySec` | `8` / `64` | Exponential backoff başlangıcı ve tavanı. |
| `maxBannerRetryCount` | `5` | Banner retry sınırı; 0 = sınırsız. |
| `reuseBannersAcrossActivities` | `false` | Banner'ı activity'ler arası paylaş (bkz. Notlar). |
| `adTtlMinutes` | `55` | Yüklü fullscreen reklamın ömrü. |
| `testMode` | `false` | Test cihazları + UMP debug coğrafyası (EEA) + debug log. **Yalnızca koddan.** |
| `debugLogging` | `false` | Debug logları (`testMode` açıkken her zaman açık). |
| `umpConsentEnabled` | `false` | UMP consent akışını kütüphane yönetsin. |
| `childDirected` | `null` | COPPA / çocuk muamelesi. **Yalnızca koddan.** |
| `doNotSell` | `null` | US eyalet gizlilik yasaları "do not sell". **Yalnızca koddan.** |
| `admobTestDeviceIds` | boş | GMA/UMP hashed test cihaz id'leri. **Yalnızca koddan.** |

Format blokları (`interstitial`, `rewarded`, `appOpen`, `banner`): `enabled` (true), `preload` (false),
`defaultPlacement` (ilk eklenen), `initialPreloadPlacements` (boş = yalnızca default) ve `addPlacement(...)`.
Placement: ad unit (tekil ya da sıralı liste), `enabled` (true), `preload` (true), `reloadAfterShow` (true).

### UMP consent (GDPR)

İlk activity'de (ör. splash) her açılışta:

```kotlin
SideAds.consent.request(this) { canRequestAds ->
    // Form gerekiyorsa gösterildi ve kapandı; consent varsa SDK başladı (kayıtlı consent'le bu callback'ten önce).
}
```

Ayarlar ekranı için: `SideAds.consent.requiresPrivacyOptions(context)` ve
`SideAds.consent.showPrivacyOptions(activity)`. Test için: `SideAds.consent.resetForTesting(context)`.
`testMode` açık ve `admobTestDeviceIds` verilmişse EEA debug coğrafyası uygulanır (form AB dışında da görünür).

### Remote Config ile JSON

```kotlin
SideAds.init(this, remoteJson, localSettings = {
    testMode = BuildConfig.DEBUG
    admobTestDeviceIds = listOf("…")
    childDirected = false
    doNotSell = userOptedOut
})

// Fetch sonrası: localSettings bloğu otomatik yeniden uygulanır; JSON okunamazsa false döner, ayar korunur.
SideAds.setSettings(newJson)
```

`testMode`, `admobTestDeviceIds`, `childDirected` ve `doNotSell` JSON'dan **okunmaz** (JSON'da olsalar da yok
sayılır); `localSettings` bloğundan gelir. Blok JSON'dan sonra uygulanır, yani koddaki değer her zaman kazanır.
Bilinmeyen anahtarlar yok sayılır. İlk JSON okunamazsa kütüphane yalnızca `localSettings` ile (placement'sız) başlar.

```json
{
  "adsEnabled": true,
  "debugLogging": false,
  "umpConsentEnabled": true,
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
    "initialPreloadPlacements": ["inter_default", "inter_splash"],
    "placements": {
      "inter_default":   { "adUnit": "ca-app-pub-…/111" },
      "inter_gameover":  { "adUnit": ["ca-app-pub-…/high_floor", "ca-app-pub-…/all_prices"] },
      "inter_splash":    { "adUnit": "ca-app-pub-…/222", "reloadAfterShow": false },
      "inter_level_end": { "adUnit": "ca-app-pub-…/333", "preload": false, "enabled": true }
    }
  },
  "rewarded": { "preload": true, "defaultPlacement": "rewarded_default",
                "placements": { "rewarded_default": { "adUnit": "ca-app-pub-…/444" } } },
  "appOpen":  { "preload": true, "defaultPlacement": "appopen_default",
                "placements": { "appopen_default": { "adUnit": "ca-app-pub-…/555" } } },
  "banner":   { "defaultPlacement": "banner_default",
                "placements": { "banner_default": { "adUnit": "ca-app-pub-…/666" } } }
}
```

`adUnit` tek bir id ya da sırayla denenecek id dizisidir. JSON nesnelerinde sıra garanti olmadığından
`defaultPlacement`'ı JSON'da açıkça verin.

### Çalışma anında kısmi değişiklik

```kotlin
SideAds.editSettings { appOpenMinIntervalSec = 60 }
SideAds.editSettings { interstitial { preload = false } }
```

Verilmeyen alanlar korunur. Sonraki bir JSON güncellemesi ayarı sıfırdan kurduğu için bu değişiklikleri ezer;
kalıcı olması gerekenleri `localSettings`'te verin. Aynı şey `SideAds.settings?.newBuilder()` ile elle de yapılır.

---

## 3. Kullanım

Gösterim çağrıları main thread'den yapılır. `show` gösterim başladıysa `true` döner; reklam hazır değilse
`false` döner ve yükleme tetikler. Placement verilmezse format'ın default placement'ı kullanılır.

### Interstitial

```kotlin
SideAds.interstitial.load("inter_level_end")          // preload'lı placement'larda gerekmez
SideAds.interstitial.isReady()                         // Boolean
SideAds.interstitial.show(activity)                    // frekans kurallarıyla
SideAds.interstitial.show(activity, "inter_gameover")
SideAds.interstitial.showIgnoringTimeRules(activity)   // en kısa aralık ve ilk gecikme atlanır
```

### Rewarded

```kotlin
SideAds.rewarded.show(activity) { reward ->
    // ödülü ver: reward.amount, reward.type
}
SideAds.rewarded.setServerSideVerification(userId, customData)  // SSV kullanıyorsanız
```

### App open

```kotlin
SideAds.appOpen.show(activity)                         // elle

// Ön plana dönüşte otomatik (Application.onCreate):
SideAds.appOpen.enableAutoShow(
    application = this,
    showOnColdStart = false,
    excludedActivities = setOf(SplashActivity::class.java, PurchaseActivity::class.java),
    excludePredicate = ActivityPredicate { activity -> /* dinamik engel */ false }
)
SideAds.appOpen.disableAutoShow()
```

### Banner

```kotlin
val slot = SideAds.banner.show(
    activity = this,
    container = findViewById(R.id.bannerContainer),
    size = BannerSize.ADAPTIVE,        // STANDARD | ADAPTIVE | MREC
    placementId = null                 // null = default placement
)
// Activity bir LifecycleOwner ise (ComponentActivity/AppCompatActivity) pause/resume/destroy otomatik.
// Değilse: slot?.pause() / slot?.resume() / slot?.destroy()

SideAds.banner.destroy()               // tüm banner'lar
SideAds.banner.destroy("banner_default")
```

### Ayıklama (debug) ekranı

```kotlin
SideAds.openDebugger(activity)         // AdMob Ad Inspector
```

### Listener'lar

Hepsi main thread'de çağrılır.

```kotlin
SideAds.listener = object : SideAdsListener {
    override fun onLoaded(ad: AdDetails) {}
    override fun onLoadFailed(type: AdType, placementId: String, error: String) {}
    override fun onShown(ad: AdDetails) {}
    override fun onShowFailed(ad: AdDetails, error: String) {}
    override fun onDismissed(ad: AdDetails) {}
    override fun onClicked(ad: AdDetails) {}
    override fun onRewarded(ad: AdDetails, reward: Reward) {}
}

SideAds.revenueListener = RevenueListener { event ->
    // event.value (event.currencyCode cinsinden), event.precision, event.adSourceName, event.adUnitId
    // Firebase / Adjust / AppsFlyer'a buradan iletin. Banner dahil tüm formatlar.
}

SideAds.logListener = LogListener { level, message -> /* uyarıları analytics'e gönderin */ }
```

`AdDetails.adSourceName` ve `RevenueEvent.adSourceName` reklamı dolduran ağın adıdır (mediation'ın bildirdiği
şekliyle); `adUnitId` zincirde hangi ad unit'in dolduğunu gösterir.

Tek gösterime özel callback'ler genel listener'a ek olarak verilebilir; gösterim tam bir kez `onDismissed` ya da
`onShowFailed` ile biter:

```kotlin
SideAds.interstitial.show(activity, "inter_gameover", object : ShowCallbacks {
    override fun onDismissed() = openNextLevel()
    override fun onShowFailed(error: String) = openNextLevel()
})
```

### Süre sınırlı splash örneği

Splash'te en fazla ne kadar bekleneceğine uygulama karar verir. Kütüphanede yükleme zaman aşımı yoktur;
`onLoaded` / `onLoadFailed` ile ve kendi zamanlayıcınızla beklenir:

```kotlin
class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var appListener: SideAdsListener? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SideAds.consent.request(this) { waitForAd() }
    }

    private fun waitForAd() {
        if (SideAds.interstitial.isReady(SPLASH)) return showAdThenContinue()
        // Splash süresince yükleme olaylarını dinle; uygulamanın kendi listener'ına da ilet.
        appListener = SideAds.listener
        SideAds.listener = object : SideAdsListener {
            override fun onLoaded(ad: AdDetails) {
                appListener?.onLoaded(ad)
                if (ad.type == AdType.INTERSTITIAL && ad.placementId == SPLASH) showAdThenContinue()
            }

            override fun onLoadFailed(type: AdType, placementId: String, error: String) {
                appListener?.onLoadFailed(type, placementId, error)
                if (type == AdType.INTERSTITIAL && placementId == SPLASH) continueToMain()
            }
        }
        SideAds.interstitial.load(SPLASH)              // preload kapalıysa
        handler.postDelayed(::continueToMain, 5_000)   // en fazla 5 sn
    }

    private fun showAdThenContinue() {
        if (done) return
        restoreListener()
        val shown = SideAds.interstitial.showIgnoringTimeRules(this, SPLASH, object : ShowCallbacks {
            override fun onDismissed() = continueToMain()
            override fun onShowFailed(error: String) = continueToMain()
        })
        if (shown) handler.removeCallbacksAndMessages(null) else continueToMain()
    }

    private fun continueToMain() {
        if (done) return
        done = true
        restoreListener()
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun restoreListener() {
        if (SideAds.listener !== appListener) SideAds.listener = appListener
    }

    private companion object {
        const val SPLASH = "inter_splash"
    }
}
```

Splash placement'ını `reloadAfterShow = false` ile tanımlayın (tek kullanımlık; boşa istek atılmaz) ve
`excludedActivities`'e ekleyerek app open'ın splash'te otomatik açılmasını önleyin.

---

## 4. Davranış kuralları

- **Kapatma hiyerarşisi:** `adsEnabled` → format `enabled` → placement `enabled`.
- **Ad unit zinciri:** Placement'ın ad unit'leri sırayla denenir; SDK "yüklendi" derse durulur, hata verirse
  sıradakine geçilir. Zaman aşımı yoktur. Hepsi hata verirse uygulamaya tek bir `onLoadFailed` gider; mesajda
  her ad unit'in hata kodu ve mesajı bulunur (ayrıca debug logda her ağın hatası ve gecikmesi). Banner'da hata
  veren view destroy edilip sıradakine geçilir.
- **Emniyet:** Bir istekten hiç cevap gelmezse (normal akışta olmaz) 90 sn sonra o adım hata sayılır ve sıradakine
  geçilir; sonradan gelen sonuç atılır. Placement oturum boyunca "yükleniyor" kalmaz.
- **Preload (3 kademe):** format `preload` ana anahtardır; `initialPreloadPlacements` açılışta yüklenecekleri
  belirler (boşsa yalnızca default); placement `preload = false` ise otomatik preload'a hiç katılmaz (ne açılışta
  ne gösterim sonrası), yalnızca `load()` ile ya da hazır değilken yapılan `show()` ile yüklenir.
- **Gösterim sonrası:** Gösterim bitince ya da gösterim hatasında placement `reloadAfterShow`'a göre yeniden
  yüklenir. Geçerli reklam varken ya da yükleme sürerken yeni istek atılmaz.
- **Retry:** Tüm zincir başarısız olursa exponential backoff (`retryBaseDelaySec` → `retryMaxDelaySec`).
  Otomatik preload dışındaki ve `reloadAfterShow = false` olan tek kullanımlık placement'larda retry yoktur.
  Banner retry'ı `maxBannerRetryCount` ile sınırlıdır; yeni `banner.show` çağrısı sayacı sıfırlar.
- **Offline:** Bağlantı yokken yükleme bekletilir, bağlantı gelince tetiklenir; offline'dayken retry sayacı artmaz.
- **Ömür:** `adTtlMinutes` dolan reklam atılır; placement otomatik preload'daysa yenisi yüklenir.
- **Frekans:** interstitial'da `interstitialMinIntervalSec`, `sessionMaxInterstitials`, `firstInterstitialDelaySec`
  (`showIgnoringTimeRules` süre kontrollerini atlar, oturum sınırını değil); app open'da `appOpenMinGapSec` ve
  `appOpenMinIntervalSec`. Kurallar otomatik app open'da da aynıdır.
- **Tek fullscreen:** Bir fullscreen reklam açıkken ikinci `show` reddedilir.
- **Kilit bekçisi:** Kilit normalde reklamın kapanma (ya da gösterim hatası) callback'iyle açılır. Reklamın üstüne
  uygulamanın kendi activity'si gelirse (ör. reklam açıkken bildirime dokunuldu) bu callback hiç gelmez; kilit kısa
  bir grace ve doğrulama süresinin sonunda zorla açılır. Reklam activity'si önde değilken kilit en fazla 5 dk tutulur
  (reklam öndeyse, ör. uzun rewarded ya da tıklama sonrası tarayıcı, süre uzar). Zorla açılışta uygulamaya tek bir
  terminal callback gider: gösterildiyse `onDismissed`, gösterilmediyse `onShowFailed`. Sonradan gelen gerçek
  callback'ler yok sayılır; geç gelen ödül yine iletilir. Reklam activity'si paket önekinden tanınır (GMA ve yedi
  ağın fullscreen activity paketleri). Bildirim intent'lerinde `CLEAR_TOP` yerine launcher bayrakları
  (`NEW_TASK | RESET_TASK_IF_NEEDED`) kullanmak sorunu baştan önler.
- **Banner:** Reklam kütüphanenin yarattığı kalıcı bir host view'a yüklenir; cache (activity, placement)
  bazlıdır. Aynı placement aynı boyutla tekrar istenirse yeni istek atılmaz, host yeni container'a taşınır; boyut
  değişirse eskisi destroy edilip yenisi yüklenir. Host window'dan ayrılınca ya da activity pause'da otomatik
  yenileme durur, geri gelince devam eder. Activity destroy olunca banner'ları temizlenir; ayarlarla kapatılan
  banner'lar destroy edilir.
- **Init:** İstekler SDK init'i bitene kadar kuyrukta bekler (en fazla 20 sn, sonra uyarıyla devam).
- **Context:** Fullscreen yüklemelerinde öndeki activity kullanılır (Unity ve DT Exchange interstitial'ı Activity
  context olmadan yüklemiyor); banner view'ı activity ile yaratılır.
- Tüm callback'ler main thread'de çağrılır.

### Gizlilik bayraklarının iletilmesi

`childDirected` ve `doNotSell` init'ten önce ve `setSettings`/`editSettings` ile değiştiklerinde uygulanır.
`null` hiçbir şey göndermez.

- **childDirected:** GMA `RequestConfiguration`'a yaş sinyali (`setAgeRestrictedTreatment`) ve Unity adaptörü için
  çocuk muamelesi bayrağı olarak gider. Yedi adaptör de bu sinyali kendi ağına iletir (AppLovin çocuk
  kullanıcılarda kendini devre dışı bırakır).
- **doNotSell:** GMA bunu ağlara iletmediği için her ağın kendi API'si çağrılır:

| Hedef | `doNotSell = true` |
|---|---|
| Google talebi | RDP (`gad_rdp`) |
| AppLovin | `AppLovinPrivacySettings.setDoNotSell(true)` |
| Meta | Limited Data Use (`AdSettings.setDataProcessingOptions(["LDU"], 0, 0)`) |
| Mintegral | `setDoNotTrackStatus(true)` (GMA init'inden sonra) |
| Pangle | `PangleMediationAdapter.setPAConsent(NO_CONSENT)` |
| Unity Ads | `MetaData "privacy.consent" = false` |
| Liftoff | `VunglePrivacySettings.setCCPAStatus(false)` |
| DT Exchange | US privacy string `1YYN` (`false` ise `1YNN`) |

GDPR consent'i UMP'den TCF / Google Additional Consent ile adaptörlere geçer; ek kod gerekmez.

---

## 5. Mimari

```
sideaccountads-core    tüm davranış + public API + UMP; reklam SDK'sı içermez
        ▲
        │ AdMediator arayüzü (com.sideaccountapps.ads.core.mediation)
        │
sideaccountads-admob   AdMobMediator + GMA + 7 Google mediation adaptörü
(ileride) sideaccountads-max   AppLovin MAX mediator'ı
```

- **Core** placement, preload, zincir, retry, frekans, fullscreen kilidi, app open, banner cache, listener'lar ve
  consent'i yönetir. Reklam yükleme/gösterme, banner view'ı, SDK init'i, gelir verisi, gizlilik bayraklarının
  iletilmesi ve debug ekranı `AdMediator` arayüzüyle mediator modülüne yaptırılır.
- **Mediator'ın bulunması:** Her mediator modülü kendi manifest'ine aynı adlı
  `com.sideaccountapps.ads.MEDIATOR` meta-data'sını kendi sınıf adıyla koyar; core bunu init'te okur. Uygulama
  hiçbir kod yazmaz.
- **İki mediator asla birlikte olmaz:** iki modül eklenirse aynı meta-data iki farklı değer alır ve manifest
  birleştirme hatası build'i kırar. Ayrıca mediator modülleri yayınlarında ortak bir Gradle capability
  (`com.sideaccountapps.ads:sideaccountads-mediator`) ilan eder; iki mediator aynı bağımlılık grafiğine girerse
  Gradle çözümleme aşamasında durur.
- **Her mediator kendi SDK ve adaptör sürümlerini sabitler.** UMP core'dadır (MAX da UMP'nin yazdığı consent
  bilgisini okur).
- **Ileride MAX'e geçiş:** Gradle satırı (`sideaccountads-max`), ad unit id'leri ve MAX'e özgü ayarlar
  (`localSettings`'te) değişir; uygulama kodu aynı kalır. MAX'e özgü ayarlar (SDK key, test cihazları) MAX modülünün
  getireceği tipli uzantılarla core'daki genel `mediatorSettings` alanına yazılır; core değişmez.

---

## 6. AdMob konsolu kurulumu

1. **Uygulama ve ad unit'ler:** AdMob'da uygulamayı ve her placement için ad unit'leri oluşturun. Zincir
   kullanacaksanız aynı format için iki ad unit açın (ör. yüksek eCPM floor'lu ve floor'suz) ve placement'a bu
   sırayla verin.
2. **Mediation group'lar:** Mediation > Create mediation group; format ve platform (Android) seçin, ad unit'leri
   gruba ekleyin. Ad Sources bölümünde Bidding (ve gerekiyorsa Waterfall) kartından ağları ekleyin. Hangi ağların
   yarışacağı burada belirlenir.
3. **Ad source eşlemesi (mapping):** Her ağın kendi panelinde uygulamayı ve placement'ları oluşturup AdMob'daki
   eşlemeye girin:

| Ağ | AdMob'a girilenler (bidding) |
|---|---|
| AppLovin | SDK Key (waterfall'da ayrıca Zone ID) |
| Meta Audience Network | Placement ID (Meta ile bidding ortaklığı gerekir) |
| Mintegral | App ID, App Key, Placement ID, Ad Unit ID |
| Pangle | App ID, Ad Placement ID |
| Unity Ads | Game ID, Placement ID |
| Liftoff Monetize | Application ID, Placement Reference ID |
| DT Exchange | Application ID, Placement ID |

4. **AppLovin SDK key:** Koda yazılmaz; AdMob'daki AppLovin ad source ayarına girilir. Adaptör key'i AdMob'dan alır.
5. **app-ads.txt:** Geliştirici sitenizdeki `app-ads.txt` dosyasına Google'ın satırını ve her ağın verdiği
   satırları ekleyin (her ağın Google entegrasyon sayfasında "Update your app-ads.txt" bölümü).
6. **UMP mesajı:** Privacy & messaging altında GDPR (ve US eyalet) mesajını oluşturun; reklam partnerleri
   listesine şunları ekleyin: AppLovin Corp., Meta, Mobvista/Mintegral, Pangle DSP, Unity Ads, Liftoff,
   DT Exchange (Fyber Monetization). Kütüphanede `umpConsentEnabled = true` yapın.
7. **Test:** Cihazı test cihazı olarak kaydedin (`admobTestDeviceIds` + `testMode`). Ad Inspector'ı
   `SideAds.openDebugger(activity)` ile açın: her ad unit için mediation zincirini görün ve "single ad source
   testing" ile her ağı tek başına deneyin. Ağların kendi panellerindeki test modunu da açabilirsiniz; yayından
   önce kapatın.

---

## 7. Demo

`demo` modülü Google'ın resmi test App ID'si ve test ad unit'leriyle çalışır; ayarların tamamı varsayılanlarıyla
`DemoApp.kt` içinde yorumludur (JSON örneği dahil).

```bash
./gradlew :demo:installDebug
adb logcat -s SideAds Demo
```

Logcat'te görülenler:

- İstekler init'i bekler (`… waits for SDK init`). Init bitince `AdMob SDK ready; running N waiting request(s)` ve
  adaptör durumları (`Adapter …: READY (… ms)`, hazır olmayanlar uyarı olarak). Google'ın test App ID'sinde
  yapılandırılmamış bir adaptör (ör. Liftoff) 30 sn beklediği için çoğunlukla 20 sn uyarısı
  (`… did not finish within 20s; running waiting requests anyway`) ve ardından `… init finished after the timeout` görülür.
- Her placement için `Loaded … from …`. `inter_chain` placement'ının ilk ad unit'i bilerek geçersizdir: hata verip
  (`… failed (step 1/2): [3] …`) ikinci ad unit'e geçtiği görülür.
- Butonlarla her format, banner boyutları ve Ad Inspector denenebilir; gösterim, kapanma, gelir ve ödül olayları
  `Demo` tag'iyle loglanır.

---

## 8. Notlar

- **Sürümler** (hepsi sabit; JitPack'ten yayınlandığı için tekrarlanabilir build şart):

| Bileşen | Sürüm |
|---|---|
| Google Mobile Ads SDK (legacy) | 25.5.0 |
| UMP | 4.0.0 |
| AppLovin adaptörü | 13.7.0.0 (AppLovin SDK 13.7.0) |
| Meta adaptörü | 6.22.0.1 (Audience Network 6.22.0) |
| Mintegral adaptörü | 17.2.21.0 |
| Pangle adaptörü | 8.4.0.5.0 |
| Unity adaptörü + Unity Ads | 4.21.0.0 + 4.21.0 (Google'ın Unity adaptörü SDK'yı kendisi getirmez) |
| Liftoff Monetize adaptörü | 7.7.9.0 |
| DT Exchange adaptörü | 8.5.1.0 |

  GMA'yı yükseltirken adaptörleri de yükseltin; her adaptörün Google sayfasındaki Gradle örneği uyumlu GMA
  sürümünü gösterir.
- **`reuseBannersAcrossActivities = true`:** Banner'lar application context ile yaratılır. Bazı adaptörler banner
  için Activity context istediğinden (ör. Unity) bu modda o ağlardan banner dolmayabilir; varsayılan `false`.
- **Test cihazı:** `testMode` tek başına test reklamı getirmez. `admobTestDeviceIds` boşsa kendi ad unit'leriniz
  gerçek reklam gösterir (kütüphane uyarı loglar); kendi reklamınıza tıklamak invalid traffic sayılır. Emülatörler
  ve AdMob konsolunda test cihazı olarak eklenen cihazlar kendiliğinden test cihazıdır; Google'ın örnek ad
  unit'leri (demo) her cihazda test reklamı döner.
- **Çocuklara yönelik uygulama:** `childDirected = true` verin ve `AD_ID` iznini kaldırın.
- Mediator'ın init'te kullandığı alanların (test cihazları, mediator ayarları) çalışma anında değişmesinin etkisi
  yoktur; gizlilik bayrakları ise değişince yeniden uygulanır.
- **Init süresi:** GMA'nın init callback'i tüm adaptörlerin init'ini bekler (adaptör başına en fazla 30 sn). AdMob'da
  bir ağ için yapılandırma yoksa o adaptör zaman aşımına kadar bekleyebilir (Google'ın test App ID'siyle demo'da
  Liftoff böyle). Kütüphane bu durumda 20 sn sonra uyarıyla devam eder; adaptör durumları init bitince loglanır.
