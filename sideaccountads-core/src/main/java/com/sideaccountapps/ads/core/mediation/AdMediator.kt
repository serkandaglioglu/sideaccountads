package com.sideaccountapps.ads.core.mediation

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.SideAdsSettings
import com.sideaccountapps.ads.core.internal.Logger

/**
 * Mediator modüllerinin (AdMob, ileride MAX) core'a sunduğu arayüz. Uygulamalar kullanmaz.
 *
 * Mediator, modülünün manifest'indeki `com.sideaccountapps.ads.MEDIATOR` meta-data'sında sınıf adıyla bildirilir
 * ve public no-arg constructor ile yaratılır. Core tüm metotları main thread'den çağırır; callback'ler herhangi
 * bir thread'den gelebilir. Zincir, retry, frekans, fullscreen kilidi ve cache core'dadır: mediator yalnızca
 * SDK'yı başlatır ve tek bir ad unit'i yükler/gösterir.
 */
interface AdMediator {

    /** Loglarda görünen ad, ör. "AdMob". */
    val name: String

    /**
     * Mediation SDK'sının ve ağların fullscreen reklam activity'lerinin paket önekleri. Fullscreen kilidi
     * bekçisi bir activity'nin reklam mı yoksa uygulamanın mı olduğunu bununla anlar.
     */
    val adActivityPrefixes: List<String>

    /**
     * Test cihazlarını ve gizlilik bayraklarını (childDirected, doNotSell) SDK'lara iletir. [initialize]'dan hemen
     * önce ve bu alanlar çalışma anında değişince çağrılır.
     */
    fun applyPrivacy(context: Context, settings: SideAdsSettings)

    /**
     * SDK'yı başlatır. Adaptörler ilk isteğe hazır olunca [onReady] bir kez çağrılır; o zamana kadar core yükleme
     * istemez. Mediator'a özgü ayarlar [SideAdsSettings.mediatorSettings]'tedir.
     */
    fun initialize(context: Context, settings: SideAdsSettings, onReady: () -> Unit)

    /**
     * Tek bir fullscreen ad unit'ini yükler ve sonucu [listener]'a tam bir kez bildirir. [context] mümkünse o anki
     * Activity'dir (bazı adaptörler istiyor), değilse application context.
     */
    fun loadFullscreen(context: Context, type: AdType, adUnitId: String, listener: FullscreenLoadListener)

    /**
     * Banner view'ını yaratır, [host]'a görünmez (INVISIBLE) ekler ve yüklemeyi başlatır; handle'ı hemen döner.
     * Her yükleme sonucu (ilki ve otomatik yenilemeler) [events]'e bildirilir. Core, ilk sonucu hata olan ya da
     * cevapsız kalan handle'ı [MediatedBanner.destroy] ile bırakır.
     */
    fun loadBanner(
        context: Context,
        host: ViewGroup,
        size: BannerSize,
        adUnitId: String,
        events: BannerEvents
    ): MediatedBanner

    /** Mediation test/debug ekranını açar (AdMob'da Ad Inspector). */
    fun openDebugger(activity: Activity)
}

/** Yüklenmiş bir fullscreen reklam. */
interface MediatedFullscreenAd {

    /** Reklamı dolduran ağ; bilinmiyorsa null. */
    val adSourceName: String?

    /** Hâlâ gösterilebilir mi (SDK kendisi geçersiz saymış olabilir). */
    val isReady: Boolean get() = true

    /**
     * Reklamı gösterir. Olaylar [events]'e; terminal olarak tam bir kez [FullscreenEvents.onDismissed] ya da
     * [FullscreenEvents.onShowFailed] gelir. [ssv] yalnızca rewarded için verilir.
     */
    fun show(activity: Activity, ssv: ServerSideVerification?, events: FullscreenEvents)

    /** Kaynakları bırakır. Gösterilmeden atılırken ve gösterim bittikten sonra çağrılır; birden çok kez çağrılabilir. */
    fun destroy()

    /**
     * Gösterim sürerken fullscreen kilidi dismiss gelmeden zorla açıldı. Mediator "gösterimde" saydığı durumu
     * düşürmelidir (ör. ad unit başına tek nesne kullanan SDK'larda). Reklam nesnesi burada destroy edilmez;
     * reklam activity'si hâlâ altta yaşıyor olabilir.
     */
    fun onForceReleased() {}
}

/** Fullscreen yükleme sonucu; tam bir kez çağrılır. */
interface FullscreenLoadListener {
    /** Reklam yüklendi. */
    fun onLoaded(ad: MediatedFullscreenAd)

    /** Yükleme başarısız; SDK'nın hata kodu ve mesajı. */
    fun onFailed(code: Int, message: String)
}

/** Fullscreen gösterim olayları. */
interface FullscreenEvents {
    /** Reklam ekrana geldi. */
    fun onShown()

    /** Gösterim başarısız (terminal). */
    fun onShowFailed(code: Int, message: String)

    /** Reklam kapandı (terminal). */
    fun onDismissed()

    /** Reklama tıklandı. */
    fun onClicked()

    /** Rewarded ödülü kazanıldı. */
    fun onRewarded(amount: Int, type: String)

    /** Gösterim geliri. [value] [currencyCode] para birimindedir. */
    fun onPaid(value: Double, currencyCode: String, precision: String, adSourceName: String?)
}

/** Yüklenen ya da yüklenmekte olan banner. */
interface MediatedBanner {
    /** Banner view'ı (mediator onu host'a ekler). */
    val view: View

    /** O an gösterilen reklamı dolduran ağ (her yenilemede değişebilir); bilinmiyorsa null. */
    val adSourceName: String?

    /** Otomatik yenilemeyi durdurur (görünmeyen banner yenilenmez). */
    fun pause()

    /** Otomatik yenilemeyi sürdürür. */
    fun resume()

    /** Bekleyen isteği iptal eder, view'ı parent'ından çıkarır ve kaynakları bırakır; birden çok kez çağrılabilir. */
    fun destroy()
}

/** Banner olayları. [onLoaded] / [onFailed] ilk yüklemede ve her otomatik yenilemede gelir. */
interface BannerEvents {
    /** Bir yükleme (ilk ya da yenileme) başarılı. */
    fun onLoaded()

    /** Bir yükleme (ilk ya da yenileme) başarısız. */
    fun onFailed(code: Int, message: String)

    /** Banner'a tıklandı. */
    fun onClicked()

    /** Gösterim geliri. */
    fun onPaid(value: Double, currencyCode: String, precision: String, adSourceName: String?)
}

/** Rewarded server-side verification parametreleri. */
class ServerSideVerification(val userId: String?, val customData: String?)

/** Mediator modüllerinin core'un logger'ına (aynı tag, aynı log listener) yazması için. */
object MediatorLog {
    /** Debug log (yalnızca debug açıkken logcat'e yazılır). */
    @JvmStatic
    fun d(message: String) = Logger.d(message)

    /** Bilgi logu (yalnızca debug açıkken logcat'e yazılır). */
    @JvmStatic
    fun i(message: String) = Logger.i(message)

    /** Uyarı logu. */
    @JvmStatic
    fun w(message: String) = Logger.w(message)

    /** Hata logu. */
    @JvmStatic
    @JvmOverloads
    fun e(message: String, throwable: Throwable? = null) = Logger.e(message, throwable)
}
