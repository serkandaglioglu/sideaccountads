package com.sideaccountapps.ads.core

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.sideaccountapps.ads.core.internal.Backoff
import com.sideaccountapps.ads.core.internal.BannerSlots
import com.sideaccountapps.ads.core.internal.Logger
import com.sideaccountapps.ads.core.mediation.MediatedBanner

/**
 * Bir placement'ın banner'ı. Reklam kütüphanenin yarattığı kalıcı bir host view'a yüklenir; aynı activity +
 * placement + boyut için tekrar [SideAds.banner] `show` çağrılırsa yeni istek atılmaz, host yeni container'a taşınır.
 *
 * Activity bir LifecycleOwner ise (ComponentActivity/AppCompatActivity) pause/resume/destroy otomatiktir; değilse
 * [pause], [resume] ve [destroy] elle çağrılır. Host window'dan ayrılınca otomatik yenileme her durumda durur.
 */
class BannerSlot internal constructor(
    context: Context,
    val placementId: String,
    val size: BannerSize
) {
    internal val host = FrameLayout(context)

    /** Backoff ve bağlantı bekleme anahtarı; her slot'un kendi retry sayacı vardır. */
    internal val key = "BANNER/$placementId#${nextId++}"

    internal var current: MediatedBanner? = null
        private set

    /** Yüklenmekte olan (sonuçlanmamış) banner; destroy'da iptal edilir. */
    internal var inFlight: MediatedBanner? = null

    /** Yükleme zinciri ya da planlı bir retry sürüyor mu? */
    internal var loading = false

    var isDestroyed = false
        private set

    /** Gösterilen bir reklam var mı? */
    val isLoaded: Boolean get() = current != null

    private var owner: LifecycleOwner? = null
    private var destroyWithOwner = true

    private val ownerResumed: Boolean
        get() = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = resume()
        override fun onPause(owner: LifecycleOwner) = pause()
        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (this@BannerSlot.owner === owner) this@BannerSlot.owner = null
            // Paylaşılan modda slot yaşar; yalnızca ölen activity'nin view ağacından çıkar.
            if (destroyWithOwner) destroy() else detachFromParent()
        }
    }

    init {
        // Görünmeyen banner yenilenmez.
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!isDestroyed && ownerResumed) resume()
            }

            override fun onViewDetachedFromWindow(v: View) = pause()
        })
    }

    /** Otomatik yenilemeyi durdurur. */
    fun pause() {
        val banner = current ?: return
        banner.pause()
        Logger.d("Banner '$placementId' paused")
    }

    /** Otomatik yenilemeyi sürdürür (host bir window'a bağlıysa). */
    fun resume() {
        val banner = current ?: return
        if (!host.isAttachedToWindow) return
        banner.resume()
        Logger.d("Banner '$placementId' resumed")
    }

    /** Banner'ı ve bekleyen isteği bırakır, host'u container'ından çıkarır. */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        loading = false
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = null
        Backoff.reset(key)
        inFlight?.destroy()
        inFlight = null
        current?.destroy()
        current = null
        host.removeAllViews()
        detachFromParent()
        BannerSlots.onSlotDestroyed(this)
    }

    internal fun bindTo(owner: LifecycleOwner, destroyWithOwner: Boolean) {
        this.destroyWithOwner = destroyWithOwner
        if (this.owner === owner) return
        this.owner?.lifecycle?.removeObserver(lifecycleObserver)
        this.owner = owner
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    /** Host'u eski yerinden çıkarıp [container]'a taşır. */
    internal fun moveTo(container: ViewGroup) {
        if (isDestroyed || host.parent === container) return
        detachFromParent()
        container.removeAllViews()
        val params = if (container is FrameLayout) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            )
        } else {
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        container.addView(host, params)
    }

    /** Host, destroy olan [activity]'nin view ağacındaysa ayırır (paylaşılan modda sızıntı önlemi). */
    internal fun detachIfInside(activity: Activity) {
        if (host.parent != null && host.rootView === activity.window?.peekDecorView()) detachFromParent()
    }

    /** Yeni yüklenen banner'ı gösterir, eskisini bırakır. */
    internal fun showLoaded(banner: MediatedBanner) {
        if (isDestroyed) {
            banner.destroy()
            return
        }
        current?.destroy()
        current = banner
        banner.view.visibility = View.VISIBLE
        if (!host.isAttachedToWindow || !ownerResumed) banner.pause()
    }

    private fun detachFromParent() {
        (host.parent as? ViewGroup)?.removeView(host)
    }

    private companion object {
        var nextId = 0
    }
}
