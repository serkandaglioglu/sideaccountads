package com.sideaccountapps.ads.core.internal

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.SideAdsSettings
import com.sideaccountapps.ads.core.mediation.AdMediator
import com.sideaccountapps.ads.core.mediation.BannerEvents
import com.sideaccountapps.ads.core.mediation.FullscreenEvents
import com.sideaccountapps.ads.core.mediation.FullscreenLoadListener
import com.sideaccountapps.ads.core.mediation.MediatedBanner
import com.sideaccountapps.ads.core.mediation.MediatedFullscreenAd
import com.sideaccountapps.ads.core.mediation.ServerSideVerification

/** Elle ilerleyen zamanlayıcı. */
internal class FakeTimer : Timer {
    private class Task(val dueAt: Long, val seq: Int, val run: () -> Unit)

    private var now = 0L
    private var seq = 0
    private val tasks = mutableListOf<Task>()

    override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
        val entry = Task(now + delayMs, seq++, task)
        tasks += entry
        return { tasks.remove(entry) }
    }

    fun advanceBy(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.filter { it.dueAt <= target }.minWithOrNull(compareBy({ it.dueAt }, { it.seq })) ?: break
            tasks.remove(next)
            now = next.dueAt
            next.run()
        }
        now = target
    }
}

class FakeAd(val adUnitId: String) : MediatedFullscreenAd {
    var destroyed = false
    override val adSourceName: String = "FakeNetwork"
    override fun show(activity: Activity, ssv: ServerSideVerification?, events: FullscreenEvents) = Unit
    override fun destroy() {
        destroyed = true
    }
}

/** Ad unit başına senaryolu sahte mediator: yükler, hata verir ya da hiç cevap vermez. */
class FakeMediator : AdMediator {
    sealed class Outcome {
        object Load : Outcome()
        class Fail(val code: Int, val message: String) : Outcome()
        object Silent : Outcome()
    }

    private val outcomes = HashMap<String, Outcome>()
    private val silent = HashMap<String, FullscreenLoadListener>()
    val requested = mutableListOf<String>()
    val delivered = mutableListOf<FakeAd>()

    fun respond(adUnitId: String, outcome: Outcome) {
        outcomes[adUnitId] = outcome
    }

    /** Cevap vermemiş bir isteği sonradan başarıyla tamamlar. */
    fun completeLate(adUnitId: String): FakeAd {
        val ad = FakeAd(adUnitId)
        delivered += ad
        silent.remove(adUnitId)!!.onLoaded(ad)
        return ad
    }

    override val name = "Fake"
    override val adActivityPrefixes = listOf("com.example.fake.")
    override fun applyPrivacy(context: Context, settings: SideAdsSettings) = Unit
    override fun initialize(context: Context, settings: SideAdsSettings, onReady: () -> Unit) = onReady()

    override fun loadFullscreen(context: Context, type: AdType, adUnitId: String, listener: FullscreenLoadListener) {
        requested += adUnitId
        when (val outcome = outcomes[adUnitId] ?: Outcome.Fail(3, "No fill")) {
            Outcome.Load -> listener.onLoaded(FakeAd(adUnitId).also { delivered += it })
            is Outcome.Fail -> listener.onFailed(outcome.code, outcome.message)
            Outcome.Silent -> silent[adUnitId] = listener
        }
    }

    override fun loadBanner(
        context: Context,
        host: ViewGroup,
        size: BannerSize,
        adUnitId: String,
        events: BannerEvents
    ): MediatedBanner = error("not used")

    override fun openDebugger(activity: Activity) = Unit
}
