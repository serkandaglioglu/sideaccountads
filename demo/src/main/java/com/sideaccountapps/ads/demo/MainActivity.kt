package com.sideaccountapps.ads.demo

import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.SideAds

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val bannerContainer = findViewById<FrameLayout>(R.id.bannerContainer)

        // Gerçek uygulamada ilk activity'de (splash) çağrılır: gerekiyorsa consent formu gösterilir, sonra SDK başlar.
        // Demo'da umpConsentEnabled = false olduğundan form çıkmaz.
        SideAds.consent.request(this)

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
        onClick(R.id.btnBannerAdaptive) {
            SideAds.banner.show(this, bannerContainer, BannerSize.ADAPTIVE, "banner_adaptive")
        }
        onClick(R.id.btnBannerStandard) {
            SideAds.banner.show(this, bannerContainer, BannerSize.STANDARD, "banner_fixed")
        }
        onClick(R.id.btnBannerMrec) {
            SideAds.banner.show(this, bannerContainer, BannerSize.MREC, "banner_fixed")
        }
        onClick(R.id.btnDebugger) { SideAds.openDebugger(this) }
    }

    private fun onClick(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
