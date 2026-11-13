package com.sideaccountapps.ads.core.internal

import android.content.Context
import android.content.ContextWrapper
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.LoadCallbacks
import com.sideaccountapps.ads.core.SideAds
import com.sideaccountapps.ads.core.SideAdsListener
import com.sideaccountapps.ads.core.internal.FakeMediator.Outcome
import com.sideaccountapps.ads.core.sideAdsSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Tek seferlik yükleme callback'leri, gerçek [Engine] ve sahte mediator ile. JVM'de Looper olmadığından Main.run
 * hemen çalışır; sahte mediator senkron cevap verir. Engine tekil olduğundan her test kendi placement'ını kullanır.
 */
class LoadCallbacksTest {

    private class Recorder : LoadCallbacks {
        val events = mutableListOf<String>()

        override fun onLoaded() {
            events += "loaded"
        }

        override fun onLoadFailed(error: String) {
            events += "failed: $error"
        }
    }

    @After
    fun restore() {
        SideAds.listener = null
        OnlineWatcher.isOnline = true
    }

    @Test
    fun `answers at once when the ad is already loaded`() {
        mediator.respond("ready_unit", Outcome.Load)
        SideAds.interstitial.load("ready")
        val callbacks = Recorder()

        SideAds.interstitial.load("ready", callbacks)

        assertEquals(listOf("loaded"), callbacks.events)
        assertEquals(1, mediator.requested.count { it == "ready_unit" })
    }

    @Test
    fun `every caller gets the running load's result exactly once`() {
        mediator.respond("slow_unit", Outcome.Silent)
        val first = Recorder()
        val second = Recorder()

        SideAds.interstitial.load("slow", first)
        SideAds.interstitial.load("slow", second)
        assertTrue(first.events.isEmpty())
        assertEquals(1, mediator.requested.count { it == "slow_unit" })

        mediator.completeLate("slow_unit")
        assertEquals(listOf("loaded"), first.events)
        assertEquals(listOf("loaded"), second.events)
    }

    @Test
    fun `a failure reaches the caller once and the app listener stays in place`() {
        val appEvents = mutableListOf<String>()
        val appListener = object : SideAdsListener {
            override fun onLoadFailed(type: AdType, placementId: String, error: String) {
                appEvents += placementId
            }
        }
        SideAds.listener = appListener
        mediator.respond("bad_unit", Outcome.Fail(3, "No fill"))
        val callbacks = Recorder()

        SideAds.interstitial.load("bad", callbacks)

        assertEquals(listOf("failed: bad_unit: [3] No fill"), callbacks.events)
        assertEquals(listOf("bad"), appEvents)
        assertSame(appListener, SideAds.listener)
    }

    @Test
    fun `disabled or unknown placement fails at once without a request`() {
        val disabled = Recorder()
        val unknown = Recorder()

        SideAds.interstitial.load("off", disabled)
        SideAds.interstitial.load("missing", unknown)

        assertEquals(listOf("failed: INTERSTITIAL placement 'off' is disabled or not configured"), disabled.events)
        assertEquals(listOf("failed: INTERSTITIAL placement 'missing' is disabled or not configured"), unknown.events)
        assertTrue(mediator.requested.none { it == "off_unit" })
    }

    @Test
    fun `offline fails at once instead of holding the callback`() {
        mediator.respond("offline_unit", Outcome.Load)
        OnlineWatcher.isOnline = false
        val callbacks = Recorder()

        SideAds.interstitial.load("offline", callbacks)

        assertEquals(listOf("failed: offline; the load starts when the connection returns"), callbacks.events)
        assertTrue(mediator.requested.none { it == "offline_unit" })
    }

    companion object {
        private val mediator = FakeMediator()

        @BeforeClass
        @JvmStatic
        fun startEngine() {
            val context = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            }
            // Format preload'u kapalı: yükleme yalnızca testlerin isteğiyle.
            val settings = sideAdsSettings {
                interstitial {
                    addPlacement("ready", "ready_unit")
                    addPlacement("slow", "slow_unit")
                    addPlacement("bad", "bad_unit")
                    addPlacement("offline", "offline_unit")
                    addPlacement("off", "off_unit", enabled = false)
                }
            }
            Engine.init(context, settings, null)
            // JVM'de manifest okunamaz, mediator bulunmaz: sahtesi konur, ayar yeniden uygulanınca SDK başlar.
            Engine.mediator = mediator
            Engine.setSettings(settings)
        }
    }
}
