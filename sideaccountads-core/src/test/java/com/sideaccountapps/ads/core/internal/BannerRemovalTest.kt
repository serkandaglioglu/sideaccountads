package com.sideaccountapps.ads.core.internal

import android.app.Activity
import android.content.ContextWrapper
import com.sideaccountapps.ads.core.BannerCallbacks
import com.sideaccountapps.ads.core.BannerRemovalReason
import com.sideaccountapps.ads.core.BannerSize
import com.sideaccountapps.ads.core.BannerSlot
import com.sideaccountapps.ads.core.sideAdsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kaldırılan banner'ın callback'ine nedeniyle tam bir kez haber gider. View'lar JVM'de işlevsiz stub'dır. */
class BannerRemovalTest {

    private val activity = Activity()
    private val removals = mutableListOf<String>()

    private fun slot(placementId: String) =
        BannerSlot(ContextWrapper(null), placementId, BannerSize.ADAPTIVE).also { slot ->
            slot.callbacks = object : BannerCallbacks {
                override fun onRemoved(reason: BannerRemovalReason) {
                    removals += "$placementId: $reason"
                }
            }
            BannerSlots.put(activity, slot, sharedMode = false)
        }

    @Test
    fun `destroy reports DESTROYED once`() {
        val slot = slot("p")

        slot.destroy()
        slot.destroy()

        assertEquals(listOf("p: DESTROYED"), removals)
    }

    @Test
    fun `settings that turn a placement off remove only its banner`() {
        val kept = slot("kept")
        val dropped = slot("dropped")

        BannerSlots.applySettings(sideAdsSettings {
            banner {
                addPlacement("kept", "unit")
                addPlacement("dropped", "unit", enabled = false)
            }
        })

        assertEquals(listOf("dropped: DISABLED_BY_SETTINGS"), removals)
        assertTrue(dropped.isDestroyed)
        assertFalse(kept.isDestroyed)
        kept.destroy()
    }

    @Test
    fun `a finished activity removes its banners`() {
        slot("first")
        slot("second")

        BannerSlots.onActivityDestroyed(activity)

        assertEquals(listOf("first: ACTIVITY_DESTROYED", "second: ACTIVITY_DESTROYED"), removals.sorted())
    }
}
