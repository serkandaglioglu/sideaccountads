package com.sideaccountapps.ads.admob

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.mediation.AdaptiveBannerWidth
import com.sideaccountapps.ads.core.mediation.BannerEvents
import com.sideaccountapps.ads.core.mediation.FullscreenEvents
import com.sideaccountapps.ads.core.mediation.FullscreenLoadListener
import com.sideaccountapps.ads.core.mediation.MediatedBanner
import com.sideaccountapps.ads.core.mediation.MediatedFullscreenAd
import com.sideaccountapps.ads.core.mediation.MediatorLog
import com.sideaccountapps.ads.core.mediation.ServerSideVerification

/** GMA ile tek bir ad unit'in yüklenmesi ve gösterilmesi. */
internal object AdMobAds {

    fun loadFullscreen(context: Context, type: AdType, adUnitId: String, listener: FullscreenLoadListener) {
        val request = AdRequest.Builder().build()
        when (type) {
            AdType.INTERSTITIAL -> InterstitialAd.load(context, adUnitId, request, object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) = listener.onLoaded(GmaInterstitial(ad))
                override fun onAdFailedToLoad(error: LoadAdError) = failed(adUnitId, error, listener::onFailed)
            })

            AdType.REWARDED -> RewardedAd.load(context, adUnitId, request, object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) = listener.onLoaded(GmaRewarded(ad))
                override fun onAdFailedToLoad(error: LoadAdError) = failed(adUnitId, error, listener::onFailed)
            })

            AdType.APP_OPEN -> AppOpenAd.load(context, adUnitId, request, object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) = listener.onLoaded(GmaAppOpen(ad))
                override fun onAdFailedToLoad(error: LoadAdError) = failed(adUnitId, error, listener::onFailed)
            })

            AdType.BANNER -> listener.onFailed(-1, "banner is not a fullscreen type")
        }
    }

    fun loadBanner(context: Context, host: ViewGroup, size: BannerSize, adUnitId: String, events: BannerEvents): MediatedBanner {
        // Bazı adaptörler banner için Activity context istiyor; core uygun context'i verir.
        val adView = AdView(context)
        adView.adUnitId = adUnitId
        adView.setAdSize(adSize(context, host, size))
        adView.onPaidEventListener = OnPaidEventListener { value ->
            events.onPaid(value.valueMicros / 1_000_000.0, value.currencyCode, precision(value), sourceName(adView.responseInfo))
        }
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() = events.onLoaded()
            override fun onAdFailedToLoad(error: LoadAdError) = failed(adUnitId, error, events::onFailed)
            override fun onAdClicked() = events.onClicked()
        }
        // Önce hiyerarşiye ekle sonra yükle; core yükleme başarılı olunca görünür yapar.
        adView.visibility = View.INVISIBLE
        host.addView(
            adView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
        )
        adView.loadAd(AdRequest.Builder().build())
        return object : MediatedBanner {
            private var destroyed = false
            override val view: View = adView
            override val adSourceName: String? get() = sourceName(adView.responseInfo)
            override fun pause() = adView.pause()
            override fun resume() = adView.resume()
            override fun destroy() {
                if (destroyed) return
                destroyed = true
                (adView.parent as? ViewGroup)?.removeView(adView)
                adView.destroy()
            }
        }
    }

    private fun adSize(context: Context, host: ViewGroup, size: BannerSize): AdSize = when (size) {
        BannerSize.STANDARD -> AdSize.BANNER
        BannerSize.MREC -> AdSize.MEDIUM_RECTANGLE
        // Ekranın değil container'ın genişliği: kenar boşluklu kartta reklam taşıp kırpılmasın.
        // 25.0'dan beri önerilen anchored adaptive boyut (eski anchored metotlar deprecated).
        BannerSize.ADAPTIVE -> AdSize.getLargeAnchoredAdaptiveBannerAdSize(
            context, (AdaptiveBannerWidth.px(host) / context.resources.displayMetrics.density).toInt()
        )
    }

    /** Hatanın kodu ve mesajı sonuca gider; her ağın hatası ve gecikmesi debug loglanır. */
    private fun failed(adUnitId: String, error: LoadAdError, report: (Int, String) -> Unit) {
        error.responseInfo?.adapterResponses?.forEach { response ->
            val result = response.adError?.let { "[${it.code}] ${it.message}" } ?: "no error"
            MediatorLog.d(
                "  $adUnitId <- ${response.adSourceName.ifBlank { response.adapterClassName }}: $result (${response.latencyMillis} ms)"
            )
        }
        report(error.code, error.message)
    }

    /** Reklamı dolduran ağ, GMA'nın bildirdiği adıyla; bilinmiyorsa null. */
    fun sourceName(info: ResponseInfo?): String? =
        info?.loadedAdapterResponseInfo?.adSourceName?.takeIf { it.isNotBlank() }

    fun precision(value: AdValue): String = when (value.precisionType) {
        AdValue.PrecisionType.PRECISE -> "PRECISE"
        AdValue.PrecisionType.ESTIMATED -> "ESTIMATED"
        AdValue.PrecisionType.PUBLISHER_PROVIDED -> "PUBLISHER_PROVIDED"
        else -> "UNKNOWN"
    }
}

/** Üç GMA fullscreen türünün ortak kısmı: callback'ler, gelir ve kaynak adı. */
private abstract class GmaFullscreenAd : MediatedFullscreenAd {

    protected abstract val responseInfo: ResponseInfo?

    override val adSourceName: String? get() = AdMobAds.sourceName(responseInfo)

    protected fun callbacks(events: FullscreenEvents) = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() = events.onShown()
        override fun onAdFailedToShowFullScreenContent(error: AdError) = events.onShowFailed(error.code, error.message)
        override fun onAdDismissedFullScreenContent() = events.onDismissed()
        override fun onAdClicked() = events.onClicked()
    }

    protected fun paid(events: FullscreenEvents) = OnPaidEventListener { value ->
        events.onPaid(value.valueMicros / 1_000_000.0, value.currencyCode, AdMobAds.precision(value), adSourceName)
    }

    protected fun consumed(events: FullscreenEvents) {
        MediatorLog.w("AdMob ad was already shown or destroyed")
        events.onShowFailed(-1, "ad already consumed")
    }
}

private class GmaInterstitial(private var ad: InterstitialAd?) : GmaFullscreenAd() {
    private val info = ad?.responseInfo
    override val responseInfo get() = info

    override fun show(activity: Activity, ssv: ServerSideVerification?, events: FullscreenEvents) {
        val current = ad ?: return consumed(events)
        ad = null
        current.fullScreenContentCallback = callbacks(events)
        current.onPaidEventListener = paid(events)
        current.show(activity)
    }

    override fun destroy() {
        ad = null
    }
}

private class GmaRewarded(private var ad: RewardedAd?) : GmaFullscreenAd() {
    private val info = ad?.responseInfo
    override val responseInfo get() = info

    override fun show(activity: Activity, ssv: ServerSideVerification?, events: FullscreenEvents) {
        val current = ad ?: return consumed(events)
        ad = null
        if (ssv != null) {
            current.setServerSideVerificationOptions(
                ServerSideVerificationOptions.Builder().apply {
                    ssv.userId?.let { setUserId(it) }
                    ssv.customData?.let { setCustomData(it) }
                }.build()
            )
        }
        current.fullScreenContentCallback = callbacks(events)
        current.onPaidEventListener = paid(events)
        current.show(activity) { reward -> events.onRewarded(reward.amount, reward.type) }
    }

    override fun destroy() {
        ad = null
    }
}

private class GmaAppOpen(private var ad: AppOpenAd?) : GmaFullscreenAd() {
    private val info = ad?.responseInfo
    override val responseInfo get() = info

    override fun show(activity: Activity, ssv: ServerSideVerification?, events: FullscreenEvents) {
        val current = ad ?: return consumed(events)
        ad = null
        current.fullScreenContentCallback = callbacks(events)
        current.onPaidEventListener = paid(events)
        current.show(activity)
    }

    override fun destroy() {
        ad = null
    }
}
