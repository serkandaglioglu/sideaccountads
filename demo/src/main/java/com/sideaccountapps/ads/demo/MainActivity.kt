package com.sideaccountapps.ads.demo

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.BannerCallbacks
import com.sideaccountapps.ads.core.BannerRemovalReason
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.SideAds

class MainActivity : ComponentActivity() {

    private lateinit var bannerCard: View
    private lateinit var bannerContainer: FrameLayout

    // Kart reklam gelince görünür, banner kaldırılınca (ayar, destroy, activity) gizlenir.
    // GONE değil INVISIBLE: gizliyken de ölçülür, ADAPTIVE banner kartın genişliğinde istenir.
    private val bannerCallbacks = object : BannerCallbacks {
        override fun onLoaded() {
            bannerCard.visibility = View.VISIBLE
        }

        override fun onRemoved(reason: BannerRemovalReason) {
            bannerCard.visibility = View.INVISIBLE
            toast("Banner kaldırıldı: $reason")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bannerCard = findViewById(R.id.bannerCard)
        bannerContainer = findViewById(R.id.bannerContainer)

        // Consent SplashActivity'de istendi. Container henüz layout olmadı: ADAPTIVE yükleme ilk layout'u bekler.
        showBanner(BannerSize.ADAPTIVE, "banner_adaptive")

        onClick(R.id.btnInterstitial) {
            if (!SideAds.interstitial.show(this)) toast("Interstitial hazır değil ya da kurallar engelledi")
        }
        onClick(R.id.btnInterstitialNow) {
            if (!SideAds.interstitial.showIgnoringTimeRules(this)) toast("Interstitial hazır değil")
        }
        onClick(R.id.btnInterstitialChain) {
            if (!SideAds.interstitial.show(this, "inter_chain")) toast("Zincir placement'ı hazır değil ya da kurallar engelledi")
        }
        onClick(R.id.btnRewarded) {
            val shown = SideAds.rewarded.show(this) { reward -> toast("Ödül: ${reward.amount} ${reward.type}") }
            if (!shown) toast("Rewarded hazır değil")
        }
        onClick(R.id.btnAppOpen) {
            if (!SideAds.appOpen.show(this)) toast("App open hazır değil ya da kurallar engelledi")
        }
        // Aynı placement + boyut tekrar istenirse yeni istek atılmaz, yüklü banner geri gelir.
        onClick(R.id.btnBannerAdaptive) { showBanner(BannerSize.ADAPTIVE, "banner_adaptive") }
        onClick(R.id.btnBannerStandard) { showBanner(BannerSize.STANDARD, "banner_fixed") }
        onClick(R.id.btnBannerMrec) { showBanner(BannerSize.MREC, "banner_fixed") }
        // Ayarla kapatılan banner destroy edilir; kart onRemoved(DISABLED_BY_SETTINGS) ile gizlenir.
        onClick(R.id.btnBannerToggle) {
            val enabled = SideAds.settings?.isPlacementEnabled(AdType.BANNER, "banner_adaptive") == true
            SideAds.editSettings {
                banner { addPlacement("banner_adaptive", DemoApp.TEST_BANNER_ADAPTIVE, enabled = !enabled) }
            }
            toast(if (enabled) "banner_adaptive kapatıldı" else "banner_adaptive açıldı")
        }
        onClick(R.id.btnDebugger) { SideAds.openDebugger(this) }
    }

    private fun showBanner(size: BannerSize, placementId: String) {
        // Consent yoksa SDK başlamaz; banner boşuna istenmez.
        if (!SideAds.consent.canRequestAds(this)) return toast("Consent yok; banner istenmedi")
        if (SideAds.banner.show(this, bannerContainer, size, placementId, bannerCallbacks) == null) {
            toast("$placementId kapalı")
        }
    }

    private fun onClick(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
