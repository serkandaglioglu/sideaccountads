package com.sideaccountapps.ads.demo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.sideaccountapps.ads.core.LoadCallbacks
import com.sideaccountapps.ads.core.ShowCallbacks
import com.sideaccountapps.ads.core.SideAds

/**
 * README'deki süre sınırlı splash örneği: consent, en fazla 5 sn reklam bekleme, reklam kapanınca ana ekran.
 * Global listener'a dokunmaz; yükleme sonucu tek seferlik callback'le gelir.
 */
class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var waiting = false
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "SideAds Demo"
            gravity = Gravity.CENTER
        })
        // Gerekiyorsa consent formu gösterilir, consent varsa SDK başlar.
        SideAds.consent.request(this) { waitForAd() }
        // Kayıtlı consent request() döner dönmez bilinir: ağ isteğini ve formu beklemeden devam et.
        if (SideAds.consent.canRequestAds(this)) waitForAd()
    }

    private fun waitForAd() {
        if (waiting || done) return
        waiting = true
        handler.postDelayed(::continueToMain, 5_000) // en fazla 5 sn
        // Zaten yüklüyse onLoaded hemen; yüklenemiyorsa (bağlantı yok, consent yok, placement kapalı) onLoadFailed hemen.
        SideAds.interstitial.load(SPLASH, object : LoadCallbacks {
            override fun onLoaded() = showAdThenContinue()
            override fun onLoadFailed(error: String) = continueToMain()
        })
    }

    private fun showAdThenContinue() {
        if (done) return
        val shown = SideAds.interstitial.showIgnoringTimeRules(this, SPLASH, object : ShowCallbacks {
            override fun onDismissed() = continueToMain()
            override fun onShowFailed(error: String) = continueToMain()
        })
        if (shown) handler.removeCallbacksAndMessages(null) else continueToMain()
    }

    private fun continueToMain() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        // Kullanıcı splash'ten geri çıktıysa geç gelen sonuç ana ekranı açmasın.
        done = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val SPLASH = "inter_splash"
    }
}
