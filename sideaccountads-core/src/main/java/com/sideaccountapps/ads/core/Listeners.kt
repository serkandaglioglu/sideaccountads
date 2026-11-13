package com.sideaccountapps.ads.core

import android.app.Activity

/**
 * Tüm reklam olaylarını alan genel listener. Her metot isteğe bağlıdır; hepsi main thread'de çağrılır.
 * [SideAds.listener] ile kaydedilir.
 */
interface SideAdsListener {
    /** Reklam yüklendi (banner'da otomatik yenilemeler dahil). */
    fun onLoaded(ad: AdDetails) {}

    /**
     * Placement'ın tüm ad unit'leri hata verdi. [error] her ad unit'in hata kodunu ve mesajını içerir.
     */
    fun onLoadFailed(type: AdType, placementId: String, error: String) {}

    /** Fullscreen reklam ekrana geldi. */
    fun onShown(ad: AdDetails) {}

    /** Fullscreen reklam gösterilemedi. */
    fun onShowFailed(ad: AdDetails, error: String) {}

    /** Fullscreen reklam kapandı. */
    fun onDismissed(ad: AdDetails) {}

    /** Reklama tıklandı. */
    fun onClicked(ad: AdDetails) {}

    /** Rewarded reklam ödülü kazanıldı. */
    fun onRewarded(ad: AdDetails, reward: Reward) {}
}

/**
 * Tek bir gösterime özel callback'ler; genel [SideAdsListener]'a ek olarak çağrılır.
 * Örn. splash'te "reklam kapanınca ana ekrana geç" akışı. Her metot isteğe bağlıdır, main thread'de çağrılır.
 * Bir gösterim tam olarak bir kez [onDismissed] ya da [onShowFailed] ile biter.
 */
interface ShowCallbacks {
    /** Reklam ekrana geldi. */
    fun onShown() {}

    /** Reklam kapandı (ya da kilit zorla açıldı ve reklam gösterilmişti). */
    fun onDismissed() {}

    /** Reklam gösterilemedi; [error] nedeni içerir. */
    fun onShowFailed(error: String) {}
}

/**
 * Tek bir yükleme isteğinin sonucu (ör. `SideAds.interstitial.load(placementId, callbacks)`); genel
 * [SideAdsListener]'a ek olarak çağrılır ve onu değiştirmeyi gerektirmez. Tam olarak bir kez [onLoaded] ya da
 * [onLoadFailed] gelir. Her metot isteğe bağlıdır, main thread'de çağrılır.
 */
interface LoadCallbacks {
    /** Reklam yüklendi (ya da zaten yüklüydü); gösterilebilir. */
    fun onLoaded() {}

    /** Reklam yüklenemedi; [error] nedeni içerir. */
    fun onLoadFailed(error: String) {}
}

/**
 * Tek bir banner'ın olayları (`SideAds.banner.show(…, callbacks)`); genel [SideAdsListener]'a ek olarak çağrılır.
 * Örn. container'ı saran kartı reklam gelince göstermek, banner kaldırılınca gizlemek. Her metot isteğe bağlıdır,
 * main thread'de çağrılır.
 */
interface BannerCallbacks {
    /** Reklam yüklendi (otomatik yenilemeler dahil); container dolu. */
    fun onLoaded() {}

    /** Tüm ad unit'ler hata verdi; retry sürebilir. */
    fun onLoadFailed(error: String) {}

    /** Banner kaldırıldı ve container boşaldı; bu banner bir daha olay üretmez. */
    fun onRemoved(reason: BannerRemovalReason) {}
}

/** Rewarded ödülü. Main thread'de çağrılır. */
fun interface RewardCallback {
    /** Kullanıcı ödülü kazandı. */
    fun onReward(reward: Reward)
}

/** Gösterim başına gelir listener'ı. Main thread'de çağrılır. */
fun interface RevenueListener {
    /** Bir gösterim gelir bildirdi. */
    fun onRevenue(event: RevenueEvent)
}

/** Kütüphane loglarını uygulamaya iletir (ör. uyarıları analytics'e göndermek için). */
fun interface LogListener {
    /** Kütüphane bir log satırı yazdı (seviyesi debug ayarından bağımsız iletilir). */
    fun onLog(level: LogLevel, message: String)
}

/** Consent akışının sonucu: reklam istenebilir mi. Main thread'de çağrılır. */
fun interface ConsentCallback {
    /** Akış bitti; [canRequestAds] reklam istenip istenemeyeceğini söyler. */
    fun onComplete(canRequestAds: Boolean)
}

/** Activity bazlı karar (ör. app open otomatik gösteriminde dinamik hariç tutma). */
fun interface ActivityPredicate {
    /** [activity] için koşul sağlanıyor mu? */
    fun test(activity: Activity): Boolean
}
