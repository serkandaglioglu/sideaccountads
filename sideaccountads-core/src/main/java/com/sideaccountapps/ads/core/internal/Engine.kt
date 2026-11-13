package com.sideaccountapps.ads.core.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sideaccountapps.ads.core.ActivityPredicate
import com.sideaccountapps.ads.core.AdDetails
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.ConsentCallback
import com.sideaccountapps.ads.core.FormatSettings
import com.sideaccountapps.ads.core.LoadCallbacks
import com.sideaccountapps.ads.core.PlacementSettings
import com.sideaccountapps.ads.core.RevenueEvent
import com.sideaccountapps.ads.core.Reward
import com.sideaccountapps.ads.core.RewardCallback
import com.sideaccountapps.ads.core.ShowCallbacks
import com.sideaccountapps.ads.core.SideAdsSettings
import com.sideaccountapps.ads.core.mediation.AdMediator
import com.sideaccountapps.ads.core.mediation.FullscreenEvents
import com.sideaccountapps.ads.core.mediation.FullscreenLoadListener
import com.sideaccountapps.ads.core.mediation.MediatedFullscreenAd
import com.sideaccountapps.ads.core.mediation.ServerSideVerification

/**
 * Kütüphanenin motoru: başlatma, ayarlar, fullscreen yükleme/gösterim. Tüm durum main thread'de değişir;
 * public API'nin show/load çağrıları main thread'den yapılmalıdır.
 */
internal object Engine {

    @Volatile
    var settings: SideAdsSettings? = null
        private set

    /** JSON ile başlatmada verilen, her JSON güncellemesinde yeniden uygulanan blok. */
    var localSettings: (SideAdsSettings.Builder.() -> Unit)? = null

    /** Setter testler için açık (sahte mediator). */
    var mediator: AdMediator? = null

    val gate = InitGate(Main.timer)
    val chain = AdUnitChain(Main.timer)

    @Volatile
    var ssv: ServerSideVerification? = null

    private var appContext: Context? = null
    private var sdkStarted = false

    private class StoredAd(val ad: MediatedFullscreenAd, val adUnitId: String, val loadedAtMs: Long)

    private val ads = HashMap<String, StoredAd>()
    private val loading = HashSet<String>()

    /** Tek seferlik yükleme callback'leri; anahtarın bir sonraki yükleme sonucunda bir kez çağrılır. */
    private val waiters = HashMap<String, MutableList<LoadCallbacks>>()

    fun key(type: AdType, placementId: String) = "${type.name}/$placementId"

    // ------------------------------------------------------------------ başlatma ve ayarlar

    fun init(context: Context, newSettings: SideAdsSettings, onReady: Runnable?) {
        if (appContext != null) {
            Logger.w("SideAds.init called more than once; applying the new settings instead")
            setSettings(newSettings)
            onReady?.let { whenReady(it) }
            return
        }
        val app = context.applicationContext
        appContext = app
        settings = newSettings
        Logger.debugEnabled = newSettings.debugLogging
        Logger.i("SideAds initializing (adsEnabled=${newSettings.adsEnabled})")
        Pacing.startSession()
        OnlineWatcher.start(app)
        (app as? Application)?.let { ActivityWatcher.install(it) }
        mediator = MediatorLocator.find(app)?.also {
            ShowLockGuard.adActivityPrefixes = it.adActivityPrefixes
            Logger.i("Mediator: ${it.name}")
        }
        onReady?.let { whenReady(it) }
        startSdkIfAllowed()
    }

    /** SDK hazır olunca; reklamlar kapalıysa ya da mediator yoksa beklenecek bir şey olmadığından hemen. */
    private fun whenReady(callback: Runnable) {
        val s = settings
        if (s == null || !s.adsEnabled || mediator == null) callback.run() else gate.onReady(callback)
    }

    fun setSettings(newSettings: SideAdsSettings) {
        val old = settings ?: run {
            Logger.e("Settings update ignored: call SideAds.init first")
            return
        }
        settings = newSettings
        Logger.debugEnabled = newSettings.debugLogging
        Logger.i("Settings updated")
        BannerSlots.applySettings(newSettings)
        val ctx = appContext ?: return
        if (sdkStarted && (old.childDirected != newSettings.childDirected || old.doNotSell != newSettings.doNotSell)) {
            mediator?.let { m -> safely("${m.name} applyPrivacy") { m.applyPrivacy(ctx, newSettings) } }
        }
        if (!newSettings.adsEnabled) return
        if (sdkStarted) preloadAll() else startSdkIfAllowed()
    }

    private fun startSdkIfAllowed() {
        val s = settings ?: return
        val ctx = appContext ?: return
        val m = mediator ?: return
        if (sdkStarted) return
        if (!s.adsEnabled) {
            Logger.w("Ads are disabled by settings; the ad SDK is not started")
            return
        }
        if (s.umpConsentEnabled && !UmpConsent.canRequestAds(ctx)) {
            // UMP, süreçte requestConsentInfoUpdate çağrılana kadar kayıtlı consent'i de bildirmez.
            Logger.i("UMP consent not confirmed yet; the ad SDK starts once SideAds.consent.request() confirms it")
            return
        }
        sdkStarted = true
        if (s.testMode && s.admobTestDeviceIds.isEmpty()) {
            Logger.w(
                "testMode=true but admobTestDeviceIds is empty! Unless this device is an emulator or registered " +
                    "as a test device in the AdMob console, your own ad units serve real ads here and clicking " +
                    "them counts as invalid traffic. Copy the id from the GMA log line " +
                    "setTestDeviceIds(Arrays.asList(\"...\"))"
            )
        }
        safely("${m.name} applyPrivacy") { m.applyPrivacy(ctx, s) }
        gate.start { Logger.w("${m.name} SDK init did not finish within 20s; running waiting requests anyway") }
        Logger.i("Starting ${m.name} SDK; ad requests wait until it is ready")
        try {
            m.initialize(ctx, s) {
                Main.run {
                    if (gate.isOpen) {
                        Logger.i("${m.name} SDK init finished after the timeout")
                    } else {
                        val waiting = gate.open()
                        Logger.i("${m.name} SDK ready; running $waiting waiting request(s)")
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e("${m.name} SDK init threw", t)
            gate.open()
        }
        preloadAll()
    }

    fun requestConsent(activity: Activity, callback: ConsentCallback?) {
        val s = settings ?: run {
            Logger.e("consent.request called before SideAds.init")
            callback?.onComplete(false)
            return
        }
        if (!s.umpConsentEnabled) {
            startSdkIfAllowed()
            callback?.onComplete(true)
            return
        }
        UmpConsent.gather(activity, s.testMode, s.admobTestDeviceIds) { canRequestAds ->
            Main.run {
                Logger.i("Consent flow finished, canRequestAds=$canRequestAds")
                if (canRequestAds) startSdkIfAllowed()
                callback?.onComplete(canRequestAds)
            }
        }
        // Önceki oturumun consent'i artık canRequestAds'e yansıdı: ağ isteğini ve formu beklemeden başla (Google UMP örneği).
        startSdkIfAllowed()
    }

    fun openDebugger(activity: Activity) {
        val m = mediator ?: run {
            Logger.e("openDebugger: no mediator module")
            return
        }
        safely("${m.name} openDebugger") { m.openDebugger(activity) }
    }

    private fun preloadAll() {
        val s = settings ?: return
        for (type in listOf(AdType.INTERSTITIAL, AdType.REWARDED, AdType.APP_OPEN)) {
            if (!s.isFormatEnabled(type)) continue
            s.format(type)?.initialPreloadPlacements()?.forEach { startLoad(type, it.id) }
        }
    }

    // ------------------------------------------------------------------ fullscreen yükleme

    /** Format/placement kapalıysa ya da yoksa null (nedeni loglanır). */
    fun resolve(s: SideAdsSettings, type: AdType, placementId: String?): Pair<FormatSettings, PlacementSettings>? {
        if (!s.isFormatEnabled(type)) {
            Logger.d("$type is disabled by settings")
            return null
        }
        val fs = s.format(type) ?: return null
        val p = fs.placement(placementId)
        if (p == null || !p.enabled) {
            Logger.w("$type placement '${placementId ?: fs.defaultPlacementId}' not found or disabled")
            return null
        }
        return fs to p
    }

    fun load(type: AdType, placementId: String?) {
        val s = settings ?: run {
            Logger.e("load called before SideAds.init")
            return
        }
        val (_, p) = resolve(s, type, placementId) ?: return
        Main.run { startLoad(type, p.id) }
    }

    /**
     * Yükler ve sonucu [callbacks]'e bir kez bildirir. Geçerli reklam varsa hemen onLoaded. Sonucun ne zaman geleceği
     * belli değilse (bağlantı yok, SDK consent yüzünden başlamadı) hemen onLoadFailed; yükleme yine kuyruğa girer.
     * Böylece callback (ve tuttuğu activity) süresiz bekletilmez.
     */
    fun load(type: AdType, placementId: String?, callbacks: LoadCallbacks) =
        Main.run { loadFor(type, placementId, callbacks) }

    private fun loadFor(type: AdType, placementId: String?, callbacks: LoadCallbacks) {
        val s = settings ?: run {
            Logger.e("load called before SideAds.init")
            return callbacks.onLoadFailed("SideAds.init has not been called")
        }
        val (fs, p) = resolve(s, type, placementId)
            ?: return callbacks.onLoadFailed("$type placement '${placementId ?: "default"}' is disabled or not configured")
        if (validAd(type, fs, p) != null) return callbacks.onLoaded()
        val key = key(type, p.id)
        // Sonuç senkron gelebilir: callback yüklemeden önce kaydedilir.
        waiters.getOrPut(key) { ArrayList() } += callbacks
        startLoad(type, p.id)
        val blocker = when {
            !OnlineWatcher.isOnline -> "offline; the load starts when the connection returns"
            !sdkStarted -> "the ad SDK has not started (UMP consent not confirmed)"
            else -> return
        }
        if (waiters[key]?.remove(callbacks) == true) callbacks.onLoadFailed(blocker)
    }

    private fun answerWaiters(key: String, error: String?) {
        waiters.remove(key)?.forEach { if (error == null) it.onLoaded() else it.onLoadFailed(error) }
    }

    private fun startLoad(type: AdType, placementId: String) {
        val s = settings ?: return
        val (fs, p) = resolve(s, type, placementId) ?: return
        val key = key(type, p.id)
        // Geçerli reklam varken ya da yükleme sürerken yeni istek atılmaz.
        if (validAd(type, fs, p) != null) {
            Logger.d("$key already loaded; skipping")
            return
        }
        if (key in loading) {
            Logger.d("$key is already loading; skipping")
            return
        }
        val m = mediator ?: run {
            Dispatch.event { it.onLoadFailed(type, p.id, "no mediator module") }
            answerWaiters(key, "no mediator module")
            return
        }
        if (!OnlineWatcher.isOnline) {
            Logger.d("Offline; $key will load when the connection returns")
            OnlineWatcher.runWhenOnline(key) { startLoad(type, p.id) }
            return
        }
        loading += key
        if (!gate.isOpen) Logger.d("$key waits for SDK init")
        // Bekleme boyunca loading işareti kalır: aynı placement için ikinci istek kuyruğa girmez.
        gate.runWhenReady {
            // Bazı adaptörler (Unity, DT Exchange) fullscreen yüklemede Activity context istiyor.
            val context: Context = ActivityWatcher.current ?: appContext!!
            chain.run(key, p.adUnitIds, fullscreenLoader(m, context, type, key), discard = { it.destroy() }) { result ->
                onLoadResult(type, p.id, result)
            }
        }
    }

    private fun onLoadResult(type: AdType, placementId: String, result: ChainResult<MediatedFullscreenAd>) {
        val key = key(type, placementId)
        loading -= key
        when (result) {
            is ChainResult.Loaded -> {
                Backoff.reset(key)
                ads.put(key, StoredAd(result.value, result.adUnitId, SystemClock.elapsedRealtime()))?.ad?.destroy()
                val details = AdDetails(type, placementId, result.adUnitId, result.value.adSourceName)
                Logger.i("Loaded $key from ${result.adUnitId} (source: ${details.adSourceName})")
                Dispatch.event { it.onLoaded(details) }
                answerWaiters(key, null)
            }

            is ChainResult.Failed -> {
                Logger.w("All ad units failed for $key: ${result.error}")
                Dispatch.event { it.onLoadFailed(type, placementId, result.error) }
                answerWaiters(key, result.error)
                // Tek kullanımlık (reloadAfterShow=false) ve otomatik preload dışı placement'larda retry yok.
                val s = settings ?: return
                val fs = s.format(type) ?: return
                val p = fs.placement(placementId) ?: return
                if (fs.reloadsAfterShow(p)) {
                    Backoff.schedule(key, s.retryBaseDelaySec, s.retryMaxDelaySec) { startLoad(type, placementId) }
                }
            }
        }
    }

    /** Geçerli reklam; süresi dolmuşsa atılır ve placement otomatik preload'daysa yenisi yüklenir. */
    private fun validAd(type: AdType, fs: FormatSettings, p: PlacementSettings): StoredAd? {
        val key = key(type, p.id)
        val stored = ads[key] ?: return null
        val ttlMs = (settings?.adTtlMinutes ?: 0) * 60_000L
        val expired = SystemClock.elapsedRealtime() - stored.loadedAtMs > ttlMs
        if (!expired && stored.ad.isReady) return stored
        ads.remove(key)
        stored.ad.destroy()
        Logger.d("$key ${if (expired) "expired" else "is no longer ready"}; dropped")
        if (fs.autoPreloads(p)) Main.post { startLoad(type, p.id) }
        return null
    }

    fun isReady(type: AdType, placementId: String?): Boolean {
        val s = settings ?: return false
        if (!s.isFormatEnabled(type)) return false
        val fs = s.format(type) ?: return false
        val p = fs.placement(placementId)?.takeIf { it.enabled } ?: return false
        return validAd(type, fs, p) != null
    }

    // ------------------------------------------------------------------ fullscreen gösterim

    fun show(
        type: AdType,
        activity: Activity,
        placementId: String?,
        ignoreTimeRules: Boolean,
        callbacks: ShowCallbacks?,
        onReward: RewardCallback?
    ): Boolean {
        val s = settings ?: run {
            Logger.e("show called before SideAds.init")
            return false
        }
        val (fs, p) = resolve(s, type, placementId) ?: return false
        ShowLock.heldType?.let {
            Logger.d("Another fullscreen ad ($it) is showing; $type denied")
            return false
        }
        val denial = when (type) {
            AdType.INTERSTITIAL -> Pacing.interstitialDenial(s, ignoreTimeRules)
            AdType.APP_OPEN -> Pacing.appOpenDenial(s)
            else -> null
        }
        if (denial != null) {
            Logger.d("$type denied: $denial")
            return false
        }
        val key = key(type, p.id)
        val stored = validAd(type, fs, p) ?: run {
            Logger.d("$key not ready; triggering a load")
            startLoad(type, p.id)
            return false
        }
        val session = ShowSession(type, AdDetails(type, p.id, stored.adUnitId, stored.ad.adSourceName), stored.ad, callbacks, onReward)
        session.ticket = ShowLock.tryAcquire(type, p.id, session::forceReleased) ?: return false
        ShowLockGuard.onShowRequested(activity)
        ads.remove(key)
        Logger.d("Showing $key (${stored.adUnitId})")
        return try {
            stored.ad.show(activity, if (type == AdType.REWARDED) ssv else null, session)
            true
        } catch (t: Throwable) {
            Logger.e("$key show threw", t)
            session.onShowFailed(-1, t.message ?: "show threw")
            false
        }
    }

    private fun reloadAfterShow(type: AdType, placementId: String) {
        val fs = settings?.format(type) ?: return
        val p = fs.placement(placementId) ?: return
        if (fs.reloadsAfterShow(p)) startLoad(type, placementId)
    }

    /**
     * Bir gösterim oturumu. Tam olarak bir kez biter: dismiss, show-failed ya da kilidin zorla açılması; ilk gelen
     * kazanır, sonradan gelen terminal callback yalnızca loglanır. Ödül ve gelir bu kuralın dışındadır (geç gelse de
     * iletilir).
     */
    private class ShowSession(
        val adType: AdType,
        val details: AdDetails,
        val ad: MediatedFullscreenAd,
        val callbacks: ShowCallbacks?,
        val onReward: RewardCallback?
    ) : FullscreenEvents {
        var ticket = 0L
        private var terminated = false
        private var shown = false
        private val label = Engine.key(details.type, details.placementId)

        private fun terminate(): Boolean {
            if (terminated) return false
            terminated = true
            return true
        }

        override fun onShown() = Main.run {
            if (terminated) {
                Logger.d("$label late onShown ignored")
                return@run
            }
            shown = true
            when (adType) {
                AdType.INTERSTITIAL -> Pacing.onInterstitialShown()
                AdType.REWARDED -> Pacing.onRewardedShown()
                AdType.APP_OPEN -> Pacing.onAppOpenShown()
                AdType.BANNER -> Unit
            }
            Logger.i("Shown $label (source: ${details.adSourceName})")
            Dispatch.event { it.onShown(details) }
            callbacks?.onShown()
        }

        override fun onShowFailed(code: Int, message: String) = Main.run {
            ShowLock.release(ticket)
            if (!terminate()) {
                Logger.d("$label late onShowFailed ignored: [$code] $message")
                return@run
            }
            val error = "[$code] $message"
            Logger.w("$label show failed: $error")
            Dispatch.event { it.onShowFailed(details, error) }
            callbacks?.onShowFailed(error)
            ad.destroy()
            Engine.reloadAfterShow(adType, details.placementId)
        }

        override fun onDismissed() = Main.run {
            ShowLock.release(ticket)
            if (!terminate()) {
                Logger.d("$label late onDismissed ignored")
                return@run
            }
            Logger.d("Dismissed $label")
            Dispatch.event { it.onDismissed(details) }
            callbacks?.onDismissed()
            ad.destroy()
            Engine.reloadAfterShow(adType, details.placementId)
        }

        override fun onClicked() = Main.run {
            if (!terminated) Dispatch.event { it.onClicked(details) }
        }

        override fun onRewarded(amount: Int, type: String) = Main.run {
            if (terminated) Logger.w("$label reward arrived after the session ended; delivering it anyway")
            val reward = Reward(amount, type)
            Dispatch.event { it.onRewarded(details, reward) }
            onReward?.onReward(reward)
        }

        override fun onPaid(value: Double, currencyCode: String, precision: String, adSourceName: String?) {
            Dispatch.revenue(
                RevenueEvent(
                    adType, details.placementId, details.adUnitId, value, currencyCode, precision,
                    adSourceName ?: details.adSourceName
                )
            )
        }

        /** Kilit dismiss gelmeden zorla açıldı: uygulamaya tek bir terminal callback gider. */
        fun forceReleased(reason: String) {
            if (!terminate()) return
            ad.onForceReleased()
            if (shown) {
                Logger.w("$label treated as dismissed after the lock was force-released")
                Dispatch.event { it.onDismissed(details) }
                callbacks?.onDismissed()
            } else {
                val error = "force-released before display: $reason"
                Logger.w("$label show failed: $error")
                Dispatch.event { it.onShowFailed(details, error) }
                callbacks?.onShowFailed(error)
            }
            Engine.reloadAfterShow(adType, details.placementId)
        }
    }

    private inline fun safely(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logger.e("$label threw", t)
        }
    }
}

/**
 * Zincirin bir adımında mediator'dan tek bir fullscreen ad unit'i yükler. [deliver] sonucu main thread'e taşır
 * (testlerde doğrudan çalıştırır).
 */
internal fun fullscreenLoader(
    mediator: AdMediator,
    context: Context,
    type: AdType,
    label: String,
    deliver: (() -> Unit) -> Unit = Main::run
) = AdUnitChain.Loader<MediatedFullscreenAd> { step ->
    Logger.d("Loading $label from ${step.adUnitId} (step ${step.position})")
    try {
        mediator.loadFullscreen(context, type, step.adUnitId, object : FullscreenLoadListener {
            override fun onLoaded(ad: MediatedFullscreenAd) = deliver { step.succeed(ad) }
            override fun onFailed(code: Int, message: String) = deliver { step.fail(code, message) }
        })
    } catch (t: Throwable) {
        step.fail(-1, "${mediator.name} load threw: ${t.message}")
    }
}

/** Uygulama ön plana döndüğünde app open'ı otomatik gösterir; gösterim [Engine.show] kurallarıyla yapılır. */
internal object AppOpenAutopilot : DefaultLifecycleObserver {

    private var enabled = false
    private var observing = false
    private var coldStart = true
    private var placementId: String? = null
    private var showOnColdStart = false
    private var excludedActivities: Set<Class<out Activity>> = emptySet()
    private var excludePredicate: ActivityPredicate? = null

    fun enable(
        application: Application,
        placementId: String?,
        showOnColdStart: Boolean,
        excludedActivities: Set<Class<out Activity>>,
        excludePredicate: ActivityPredicate?
    ) {
        this.placementId = placementId
        this.showOnColdStart = showOnColdStart
        this.excludedActivities = excludedActivities
        this.excludePredicate = excludePredicate
        enabled = true
        ActivityWatcher.install(application)
        if (!observing) {
            observing = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        Logger.i("App open auto-show enabled (placement=${placementId ?: "default"})")
    }

    fun disable() {
        enabled = false
        Logger.i("App open auto-show disabled")
    }

    override fun onStart(owner: LifecycleOwner) {
        val cold = coldStart
        coldStart = false
        if (!enabled) return
        if (cold && !showOnColdStart) {
            Logger.d("App open auto-show: cold start skipped (showOnColdStart=false)")
            return
        }
        val activity = ActivityWatcher.current ?: return
        if (excludedActivities.any { it.isInstance(activity) }) {
            Logger.d("App open auto-show: ${activity.javaClass.simpleName} is excluded")
            return
        }
        if (excludePredicate?.test(activity) == true) {
            Logger.d("App open auto-show: excluded by predicate")
            return
        }
        Engine.show(AdType.APP_OPEN, activity, placementId, ignoreTimeRules = false, callbacks = null, onReward = null)
    }
}
