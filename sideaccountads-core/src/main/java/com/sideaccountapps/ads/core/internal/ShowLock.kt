package com.sideaccountapps.ads.core.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.sideaccountapps.ads.core.AdType
import java.lang.ref.WeakReference

/**
 * Aynı anda yalnızca bir fullscreen reklam gösterilir. Kilit bilet tabanlıdır: geç gelen eski bir callback ya da
 * zaman aşımı kararı, arada başlamış yeni bir gösterimin kilidini açamaz.
 *
 * Kilit normalde gösterimin dismiss / show-failed callback'iyle açılır. Callback hiç gelmezse (reklamın üstüne
 * uygulamanın kendi activity'si açıldı) [ShowLockGuard] ya da [MAX_HOLD_MS] zaman aşımı kilidi zorla açar ve
 * gösterimi başlatan oturuma haber verir.
 */
internal object ShowLock {

    /** Reklam activity'si önde değilken kilidin en uzun tutulma süresi. Reklam öndeyse süre uzatılır. */
    const val MAX_HOLD_MS = 5 * 60_000L

    @Volatile
    var heldType: AdType? = null
        private set

    @Volatile
    var ticket = 0L
        private set

    private var heldPlacementId: String? = null
    private var acquiredAtMs = 0L
    private var ticketCounter = 0L
    private var onForceReleased: ((String) -> Unit)? = null

    val isHeld: Boolean get() = heldType != null

    fun heldForMs(): Long = if (isHeld) SystemClock.elapsedRealtime() - acquiredAtMs else 0L

    /** Kilit boşsa alır ve bilet döner; doluysa null. [onForceReleased] yalnızca zorla açılışta çağrılır. */
    fun tryAcquire(type: AdType, placementId: String, onForceReleased: (String) -> Unit): Long? {
        val acquired = synchronized(this) {
            if (heldType != null) return null
            heldType = type
            heldPlacementId = placementId
            acquiredAtMs = SystemClock.elapsedRealtime()
            this.onForceReleased = onForceReleased
            ticket = ++ticketCounter
            ticket
        }
        scheduleTimeout(acquired)
        return acquired
    }

    /** Yalnızca kilidi tutan bilet açabilir. */
    fun release(ticket: Long) = synchronized(this) {
        if (this.ticket == ticket && heldType != null) clear()
    }

    /** Kilit hâlâ [ticket]'ta ise açar ve gösterim oturumuna bildirir. Açıldıysa true. */
    fun forceRelease(ticket: Long, reason: String): Boolean {
        val type: AdType
        val placementId: String?
        val listener: ((String) -> Unit)?
        synchronized(this) {
            if (this.ticket != ticket) return false
            type = heldType ?: return false
            placementId = heldPlacementId
            listener = onForceReleased
            clear()
        }
        Logger.w("Fullscreen lock force-released ($type/$placementId): $reason")
        Main.run { listener?.invoke(reason) }
        return true
    }

    private fun clear() {
        heldType = null
        heldPlacementId = null
        acquiredAtMs = 0L
        ticket = 0L
        onForceReleased = null
    }

    private fun scheduleTimeout(ticket: Long) {
        Main.timer.after(MAX_HOLD_MS) {
            if (this.ticket != ticket || !isHeld) return@after
            if (ShowLockGuard.adActivityInForeground) {
                // Gerçek reklam sürüyor (uzun rewarded, tıklama sonrası tarayıcı): kilidi açmak ikinci bir
                // fullscreen'e ve erken yeniden yüklemeye yol açar.
                Logger.d("Fullscreen lock held ${heldForMs() / 1000}s but the ad activity is still in front; extending")
                scheduleTimeout(ticket)
            } else {
                forceRelease(ticket, "held longer than ${MAX_HOLD_MS / 1000}s without a dismiss callback")
            }
        }
    }
}

/**
 * Kilidin sonsuza dek dolu kalmasını önleyen lifecycle bekçisi.
 *
 * Senaryo: interstitial ekrandayken bildirimden uygulamanın kendi activity'si reklamın üstüne açılır; reklam
 * kapanmadığı için dismiss callback'i hiç gelmez. Kural: kilit tutulurken reklam olmayan bir activity resume olursa
 * kilit zorla açılır. Karar hemen verilmez: show() sonrası [GRACE_MS] içinde (reklam activity'si henüz açılmamış
 * olabilir) grace sonuna, sonrasında [CONFIRM_MS] sonuna ertelenir (Android alttaki activity'yi kapanan reklamı
 * destroy etmeden önce resume eder; bazı SDK'lar dismiss'i onDestroy'da bildirir). Gösterimi başlatan activity
 * hiçbir zaman reklam sayılmaz.
 */
internal object ShowLockGuard {

    private const val GRACE_MS = 1_500L
    private const val CONFIRM_MS = 1_000L

    /** Mediator'ın bildirdiği reklam activity paket önekleri. */
    var adActivityPrefixes: List<String> = emptyList()

    private var hostRef: WeakReference<Activity>? = null
    private var lastAdRef: WeakReference<Activity>? = null

    /**
     * Süreçte son resume olan activity bir reklam activity'siyse ve henüz destroy edilmediyse true. Reklama tıklayıp
     * tarayıcıya gidildiğinde (süreçte hiçbir activity resume olmaz) true kalır.
     */
    @Volatile
    var adActivityInForeground = false
        private set

    fun isAdActivityClass(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return adActivityPrefixes.any { name.startsWith(it) }
    }

    fun onShowRequested(host: Activity) {
        hostRef = WeakReference(host)
    }

    fun onResumed(activity: Activity) {
        val isAd = hostRef?.get() !== activity && isAdActivityClass(activity)
        adActivityInForeground = isAd
        if (isAd) {
            lastAdRef = WeakReference(activity)
            return
        }
        if (!ShowLock.isHeld) return
        val delay = maxOf(CONFIRM_MS, GRACE_MS - ShowLock.heldForMs())
        val ticket = ShowLock.ticket
        val name = activity.javaClass.simpleName
        Main.timer.after(delay) {
            if (adActivityInForeground || !ShowLock.isHeld || ShowLock.ticket != ticket) return@after
            ShowLock.forceRelease(
                ticket,
                "$name resumed over the ad and no dismiss callback arrived within ${delay}ms " +
                    "(held ${ShowLock.heldForMs() / 1000}s)"
            )
        }
    }

    fun onDestroyed(activity: Activity) {
        if (lastAdRef?.get() === activity) {
            lastAdRef = null
            adActivityInForeground = false
        }
    }
}

/**
 * Tek ActivityLifecycleCallbacks: öndeki (reklam olmayan) activity'yi tutar ve olayları bekçiye, banner cache'ine
 * dağıtır.
 */
internal object ActivityWatcher : Application.ActivityLifecycleCallbacks {

    private var installed = false
    private var currentRef: WeakReference<Activity>? = null

    /** Öndeki reklam olmayan activity; yoksa null. Fullscreen yüklemeleri ve app open otomatik gösterimi kullanır. */
    val current: Activity?
        get() = currentRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (!ShowLockGuard.isAdActivityClass(activity)) currentRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (!ShowLockGuard.isAdActivityClass(activity)) currentRef = WeakReference(activity)
        ShowLockGuard.onResumed(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
        ShowLockGuard.onDestroyed(activity)
        BannerSlots.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
