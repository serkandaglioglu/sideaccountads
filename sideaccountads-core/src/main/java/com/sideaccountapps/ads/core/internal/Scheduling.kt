package com.sideaccountapps.ads.core.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.sideaccountapps.ads.core.SideAdsSettings
import kotlin.math.min

/** Bağlantıyı izler; bağlantı yokken bekletilen işleri bağlantı gelince çalıştırır. */
internal object OnlineWatcher {

    /** Setter testler için açık. */
    @Volatile
    var isOnline = true

    private var started = false
    private val waiting = LinkedHashMap<String, () -> Unit>()

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        isOnline = currentlyOnline(cm)
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = !isOnline
                    isOnline = true
                    if (wasOffline) Main.run { flush() }
                }

                override fun onLost(network: Network) {
                    isOnline = currentlyOnline(cm)
                }
            })
        } catch (e: Exception) {
            Logger.w("Connectivity callback could not be registered: ${e.message}")
        }
    }

    /** Bağlantı gelince bir kez çalışır; aynı anahtarla yeniden verilirse eskisinin yerine geçer. */
    fun runWhenOnline(key: String, action: () -> Unit) {
        waiting[key] = action
    }

    fun cancel(key: String) {
        waiting.remove(key)
    }

    private fun flush() {
        if (waiting.isEmpty()) return
        Logger.d("Connection restored; running ${waiting.size} waiting load(s)")
        val actions = waiting.values.toList()
        waiting.clear()
        actions.forEach { it() }
    }

    private fun currentlyOnline(cm: ConnectivityManager): Boolean = try {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true
    }
}

/**
 * Yükleme tamamen başarısız olunca exponential backoff ile retry (base, 2×base, … en fazla max).
 * Bağlantı yokken sayaç artmaz; retry bağlantı gelince çalışır.
 */
internal object Backoff {

    private val attempts = HashMap<String, Int>()
    private val scheduled = HashMap<String, () -> Unit>()

    /**
     * @param maxAttempts 0 = sınırsız.
     * @return retry planlandıysa (ya da zaten planlıysa) true; sınıra ulaşıldıysa false.
     */
    fun schedule(key: String, baseSec: Int, maxSec: Int, maxAttempts: Int = 0, action: () -> Unit): Boolean {
        if (key in scheduled) return true
        val attempt = attempts[key] ?: 0
        if (maxAttempts in 1..attempt) {
            Logger.w("Retry limit ($maxAttempts) reached for $key; giving up until the next request")
            return false
        }
        attempts[key] = attempt + 1
        val delaySec = min(baseSec.toLong() shl attempt.coerceAtMost(20), maxSec.toLong()).coerceAtLeast(1)
        Logger.d("Retry #${attempt + 1} for $key in ${delaySec}s")
        scheduled[key] = Main.timer.after(delaySec * 1000) {
            scheduled.remove(key)
            if (OnlineWatcher.isOnline) {
                action()
            } else {
                Logger.d("Retry for $key postponed until the connection returns")
                OnlineWatcher.runWhenOnline(key, action)
            }
        }
        return true
    }

    /** Başarıda ya da yeni istekte: sayacı sıfırlar, bekleyen retry'ı iptal eder. */
    fun reset(key: String) {
        attempts.remove(key)
        scheduled.remove(key)?.invoke()
        OnlineWatcher.cancel(key)
    }
}

/** Gösterim sıklığı kuralları. */
internal object Pacing {

    private var sessionStartMs = SystemClock.elapsedRealtime()
    private var lastInterstitialMs = 0L
    private var lastInterstitialOrRewardedMs = 0L
    private var lastAppOpenMs = 0L
    private var sessionInterstitials = 0

    fun startSession() {
        sessionStartMs = SystemClock.elapsedRealtime()
        lastInterstitialMs = 0L
        lastInterstitialOrRewardedMs = 0L
        lastAppOpenMs = 0L
        sessionInterstitials = 0
    }

    /** null = gösterilebilir; değilse ret nedeni. [ignoreTimeRules] süre kontrollerini atlar, oturum sınırını değil. */
    fun interstitialDenial(settings: SideAdsSettings, ignoreTimeRules: Boolean): String? {
        if (settings.sessionMaxInterstitials in 1..sessionInterstitials) {
            return "session limit of ${settings.sessionMaxInterstitials} interstitials reached"
        }
        if (ignoreTimeRules) return null
        val now = SystemClock.elapsedRealtime()
        val sinceStartSec = (now - sessionStartMs) / 1000
        if (sinceStartSec < settings.firstInterstitialDelaySec) {
            return "first interstitial delay: ${sinceStartSec}s < ${settings.firstInterstitialDelaySec}s"
        }
        if (lastInterstitialMs > 0) {
            val sinceLastSec = (now - lastInterstitialMs) / 1000
            if (sinceLastSec < settings.interstitialMinIntervalSec) {
                return "interstitial min interval: ${sinceLastSec}s < ${settings.interstitialMinIntervalSec}s"
            }
        }
        return null
    }

    fun appOpenDenial(settings: SideAdsSettings): String? {
        val now = SystemClock.elapsedRealtime()
        if (lastInterstitialOrRewardedMs > 0) {
            val sinceSec = (now - lastInterstitialOrRewardedMs) / 1000
            if (sinceSec < settings.appOpenMinGapSec) {
                return "app open min gap after interstitial/rewarded: ${sinceSec}s < ${settings.appOpenMinGapSec}s"
            }
        }
        if (settings.appOpenMinIntervalSec > 0 && lastAppOpenMs > 0) {
            val sinceSec = (now - lastAppOpenMs) / 1000
            if (sinceSec < settings.appOpenMinIntervalSec) {
                return "app open min interval: ${sinceSec}s < ${settings.appOpenMinIntervalSec}s"
            }
        }
        return null
    }

    fun onInterstitialShown() {
        val now = SystemClock.elapsedRealtime()
        lastInterstitialMs = now
        lastInterstitialOrRewardedMs = now
        sessionInterstitials++
    }

    fun onRewardedShown() {
        lastInterstitialOrRewardedMs = SystemClock.elapsedRealtime()
    }

    fun onAppOpenShown() {
        lastAppOpenMs = SystemClock.elapsedRealtime()
    }
}
