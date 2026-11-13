package com.sideaccountapps.ads.core.internal

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.lifecycle.LifecycleOwner
import com.sideaccountapps.ads.core.AdDetails
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.BannerCallbacks
import com.sideaccountapps.ads.core.BannerRemovalReason
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.BannerSlot
import com.sideaccountapps.ads.core.RevenueEvent
import com.sideaccountapps.ads.core.SideAdsSettings
import com.sideaccountapps.ads.core.mediation.AdaptiveBannerWidth
import com.sideaccountapps.ads.core.mediation.BannerEvents
import com.sideaccountapps.ads.core.mediation.MediatedBanner
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Banner gösterimi ve ad unit zinciriyle yüklenmesi. */
internal object BannerFlow {

    fun show(
        activity: Activity,
        container: ViewGroup,
        size: BannerSize,
        placementId: String?,
        callbacks: BannerCallbacks?
    ): BannerSlot? {
        val s = Engine.settings ?: run {
            Logger.e("banner.show called before SideAds.init")
            return null
        }
        val (_, p) = Engine.resolve(s, AdType.BANNER, placementId) ?: return null
        if (Engine.mediator == null) {
            Logger.e("banner.show: no mediator module")
            return null
        }
        val shared = s.reuseBannersAcrossActivities
        // Bazı adaptörler banner için Activity context istiyor; paylaşılan modda bilinçli olarak application context.
        val context: Context = if (shared) activity.applicationContext else activity

        BannerSlots.get(activity, p.id, shared)?.let { cached ->
            if (cached.size == size) {
                Logger.d("Reusing the banner of '${p.id}' (no new request)")
                cached.callbacks = callbacks
                cached.moveTo(container)
                (activity as? LifecycleOwner)?.let { cached.bindTo(it, destroyWithOwner = !shared) }
                // Retry sınırına takılıp boş kalmışsa: sayacı sıfırla, yeniden başlat.
                if (!cached.isLoaded && !cached.loading) start(context, cached)
                // Yeni container'ın sahibi reklamın zaten yüklü olduğunu öğrensin.
                if (cached.isLoaded) callbacks?.onLoaded()
                return cached
            }
            Logger.d("Banner size changed for '${p.id}' (${cached.size} -> $size); replacing it")
            cached.destroy()
        }

        val slot = BannerSlot(context, p.id, size)
        slot.callbacks = callbacks
        (activity as? LifecycleOwner)?.let { slot.bindTo(it, destroyWithOwner = !shared) }
        BannerSlots.put(activity, slot, shared)
        slot.moveTo(container)
        start(context, slot)
        return slot
    }

    /** Yeni bir gösterme çağrısı retry sayacını sıfırlar. */
    private fun start(context: Context, slot: BannerSlot) {
        Backoff.reset(slot.key)
        slot.loading = true
        attempt(WeakReference(context), slot)
    }

    private fun alive(contextRef: WeakReference<Context>, slot: BannerSlot): Boolean {
        val context = contextRef.get() ?: return false
        val activity = context as? Activity
        return !slot.isDestroyed && (activity == null || (!activity.isFinishing && !activity.isDestroyed))
    }

    private fun attempt(contextRef: WeakReference<Context>, slot: BannerSlot) {
        if (!alive(contextRef, slot)) {
            slot.loading = false
            return
        }
        if (!OnlineWatcher.isOnline) {
            Logger.d("Offline; banner '${slot.placementId}' will load when the connection returns")
            OnlineWatcher.runWhenOnline(slot.key) { attempt(contextRef, slot) }
            return
        }
        if (!Engine.gate.isOpen) Logger.d("Banner '${slot.placementId}' waits for SDK init")
        Engine.gate.runWhenReady { whenLaidOut(slot) { load(contextRef, slot) } }
    }

    /**
     * ADAPTIVE genişliği container'dan alınır: host henüz layout olmadıysa (ör. show() onCreate'te çağrıldı) yükleme
     * pencerenin ilk layout'una kadar ertelenir. Host o layout'ta da ölçülmediyse (GONE container) mediator pencere
     * genişliğini kullanır ([AdaptiveBannerWidth]).
     */
    private fun whenLaidOut(slot: BannerSlot, action: () -> Unit) {
        val host = slot.host
        if (slot.size != BannerSize.ADAPTIVE || (host.isLaidOut && host.width > 0)) return action()
        Logger.d("Banner '${slot.placementId}' waits for the layout of its container")
        host.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var done = false

            override fun onGlobalLayout() {
                if (done) return
                done = true
                // Detached host'un observer'ı attach'te pencereninkine taşınır; kaldırma o anki observer'dan yapılır.
                host.viewTreeObserver.removeOnGlobalLayoutListener(this)
                action()
            }
        })
        // Pencere layout'unu çoktan bitirmiş olabilir (ör. GONE container, SDK init'i beklendi): bir layout iste.
        // Kökten istenir: GONE dal hiç layout olmadığından host'un isteği oradan yukarı iletilmez.
        host.rootView.requestLayout()
    }

    /**
     * Host her layout olduğunda: daha dar bir container'a taşınan (ya da daralan) ADAPTIVE reklam taşar ve kırpılır;
     * bırakılır, yeni genişlikle yeniden yüklenir. Genişleyen container'da reklam ortalanmış kalır (yeni istek yok).
     */
    fun onHostLaidOut(slot: BannerSlot) {
        val host = slot.host
        val width = host.width - host.paddingLeft - host.paddingRight
        if (slot.size != BannerSize.ADAPTIVE || !slot.isLoaded || slot.loading) return
        if (width <= 0 || width >= slot.requestedWidthPx) return
        Logger.i("Banner '${slot.placementId}' is wider than its container ($width < ${slot.requestedWidthPx} px); reloading")
        // Layout sırasında view ağacı değiştirilmez.
        Main.post {
            if (slot.isDestroyed || slot.loading) return@post
            slot.dropCurrent()
            start(host.context, slot)
        }
    }

    private fun load(contextRef: WeakReference<Context>, slot: BannerSlot) {
        val s = Engine.settings
        val m = Engine.mediator
        val p = s?.format(AdType.BANNER)?.placement(slot.placementId)
        if (s == null || m == null || p == null || !alive(contextRef, slot)) {
            slot.loading = false
            return
        }
        Engine.chain.run(
            label = "BANNER/${slot.placementId}",
            adUnitIds = p.adUnitIds,
            loader = AdUnitChain.Loader<MediatedBanner> { step ->
                val context = contextRef.get() ?: run {
                    step.fail(-1, "context released")
                    return@Loader
                }
                var width = ""
                if (slot.size == BannerSize.ADAPTIVE) {
                    slot.requestedWidthPx = AdaptiveBannerWidth.px(slot.host)
                    width = ", ${(slot.requestedWidthPx / slot.host.resources.displayMetrics.density).toInt()}dp wide"
                }
                Logger.d("Loading banner '${slot.placementId}' from ${step.adUnitId} (step ${step.position}$width)")
                var firstResult = true
                var handle: MediatedBanner? = null
                // Olaylar sıraya konur: senkron gelen callback de handle atandıktan sonra işlenir.
                val events = object : BannerEvents {
                    override fun onLoaded() = Main.post {
                        val banner = handle ?: return@post
                        if (firstResult) {
                            firstResult = false
                            step.succeed(banner)
                        } else if (slot.current === banner) {
                            Logger.d("Banner '${slot.placementId}' refreshed (source: ${banner.adSourceName})")
                            Dispatch.event { it.onLoaded(details(slot, step.adUnitId, banner)) }
                            slot.callbacks?.onLoaded()
                        }
                    }

                    override fun onFailed(code: Int, message: String) = Main.post {
                        if (firstResult) {
                            firstResult = false
                            step.fail(code, message)
                        } else {
                            Logger.d("Banner '${slot.placementId}' refresh failed: [$code] $message")
                        }
                    }

                    override fun onClicked() = Main.post {
                        val banner = handle ?: return@post
                        Dispatch.event { it.onClicked(details(slot, step.adUnitId, banner)) }
                    }

                    override fun onPaid(value: Double, currencyCode: String, precision: String, adSourceName: String?) {
                        Dispatch.revenue(
                            RevenueEvent(AdType.BANNER, slot.placementId, step.adUnitId, value, currencyCode, precision, adSourceName)
                        )
                    }
                }
                val banner = try {
                    m.loadBanner(context, slot.host, slot.size, step.adUnitId, events)
                } catch (t: Throwable) {
                    step.fail(-1, "${m.name} banner load threw: ${t.message}")
                    return@Loader
                }
                handle = banner
                slot.inFlight = banner
                // Hata veren ya da cevapsız kalan view destroy edilir, sıradaki ad unit'e geçilir.
                step.onAbandon {
                    banner.destroy()
                    if (slot.inFlight === banner) slot.inFlight = null
                }
            },
            discard = { it.destroy() },
            isActive = { alive(contextRef, slot) }
        ) { result -> onResult(contextRef, slot, s, result) }
    }

    private fun onResult(
        contextRef: WeakReference<Context>,
        slot: BannerSlot,
        s: SideAdsSettings,
        result: ChainResult<MediatedBanner>
    ) {
        slot.inFlight = null
        when (result) {
            is ChainResult.Loaded -> {
                slot.loading = false
                Backoff.reset(slot.key)
                slot.showLoaded(result.value)
                val details = details(slot, result.adUnitId, result.value)
                Logger.i("Banner '${slot.placementId}' loaded from ${result.adUnitId} (source: ${details.adSourceName})")
                Dispatch.event { it.onLoaded(details) }
                slot.callbacks?.onLoaded()
            }

            is ChainResult.Failed -> {
                Logger.w("All banner ad units failed for '${slot.placementId}': ${result.error}")
                Dispatch.event { it.onLoadFailed(AdType.BANNER, slot.placementId, result.error) }
                slot.callbacks?.onLoadFailed(result.error)
                val scheduled = Backoff.schedule(
                    slot.key, s.retryBaseDelaySec, s.retryMaxDelaySec, maxAttempts = s.maxBannerRetryCount
                ) { attempt(contextRef, slot) }
                if (!scheduled) slot.loading = false
            }
        }
    }

    private fun details(slot: BannerSlot, adUnitId: String, banner: MediatedBanner) =
        AdDetails(AdType.BANNER, slot.placementId, adUnitId, banner.adSourceName)
}

/**
 * Canlı banner slot'larının cache'i: (activity, placement) bazlı; paylaşılan modda yalnızca placement bazlı.
 * Activity destroy olunca girdileri temizlenir ([ActivityWatcher]); WeakHashMap ek güvenlik ağıdır.
 */
internal object BannerSlots {

    private val byActivity = WeakHashMap<Activity, MutableMap<String, BannerSlot>>()
    private val shared = HashMap<String, BannerSlot>()

    fun get(activity: Activity, placementId: String, sharedMode: Boolean): BannerSlot? {
        val slot = if (sharedMode) shared[placementId] else byActivity[activity]?.get(placementId)
        return slot?.takeIf { !it.isDestroyed }
    }

    fun put(activity: Activity, slot: BannerSlot, sharedMode: Boolean) {
        if (sharedMode) shared[slot.placementId] = slot
        else byActivity.getOrPut(activity) { HashMap() }[slot.placementId] = slot
    }

    fun onSlotDestroyed(slot: BannerSlot) {
        shared.entries.removeAll { it.value === slot }
        byActivity.values.forEach { map -> map.entries.removeAll { it.value === slot } }
    }

    fun onActivityDestroyed(activity: Activity) {
        byActivity.remove(activity)?.values?.toList()?.forEach { it.destroy(BannerRemovalReason.ACTIVITY_DESTROYED) }
        shared.values.toList().forEach { it.detachIfInside(activity) }
    }

    /** [placementId] null ise tüm banner'ları, değilse yalnızca o placement'ınkileri destroy eder. */
    fun destroy(placementId: String?) {
        val victims = all().filter { placementId == null || it.placementId == placementId }
        if (victims.isNotEmpty()) Logger.d("Destroying ${victims.size} banner(s) (placement=${placementId ?: "all"})")
        victims.forEach { it.destroy() }
    }

    /** Ayar güncellemesinde kapatılan banner'ları destroy eder (container'ları boşalır, callback'lere haber gider). */
    fun applySettings(settings: SideAdsSettings) {
        val victims = all().filter { !settings.isPlacementEnabled(AdType.BANNER, it.placementId) }
        if (victims.isNotEmpty()) Logger.i("Settings disabled ${victims.size} banner(s); destroying them")
        victims.forEach { it.destroy(BannerRemovalReason.DISABLED_BY_SETTINGS) }
    }

    private fun all(): List<BannerSlot> = shared.values.toList() + byActivity.values.flatMap { it.values.toList() }
}
