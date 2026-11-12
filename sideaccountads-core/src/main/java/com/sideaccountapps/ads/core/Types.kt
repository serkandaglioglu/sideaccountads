package com.sideaccountapps.ads.core

/** Desteklenen reklam formatları. [jsonKey], JSON ayarındaki format anahtarıdır. */
enum class AdType(internal val jsonKey: String) {
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    APP_OPEN("appOpen"),
    BANNER("banner")
}

/** Banner boyutları. */
enum class BannerSize {
    /** Sabit 320x50. */
    STANDARD,

    /** Ekran genişliğine uyan anchored adaptive banner (önerilen). */
    ADAPTIVE,

    /** 300x250 medium rectangle. */
    MREC
}

/**
 * Bir reklam olayının kimliği.
 *
 * @property adUnitId reklamı veren ad unit (zincirde hangisinin dolduğu).
 * @property adSourceName reklamı dolduran ağ (mediation'ın bildirdiği ad); bilinmiyorsa null.
 */
data class AdDetails(
    val type: AdType,
    val placementId: String,
    val adUnitId: String,
    val adSourceName: String?
)

/** Rewarded reklamın verdiği ödül. */
data class Reward(
    val amount: Int,
    val type: String
)

/**
 * Gösterim başına gelir (impression-level revenue). Analytics/MMP'ye iletmek için.
 *
 * @property value tahmini gelir, [currencyCode] para biriminde (USD varsayılmaz).
 * @property precision "PRECISE", "ESTIMATED", "PUBLISHER_PROVIDED" ya da "UNKNOWN".
 * @property adSourceName geliri getiren ağ; bilinmiyorsa null.
 */
data class RevenueEvent(
    val type: AdType,
    val placementId: String,
    val adUnitId: String,
    val value: Double,
    val currencyCode: String,
    val precision: String,
    val adSourceName: String?
)

/** Kütüphane log seviyeleri. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }
