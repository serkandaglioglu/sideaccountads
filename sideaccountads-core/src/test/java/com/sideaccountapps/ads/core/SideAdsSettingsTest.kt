package com.sideaccountapps.ads.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideAdsSettingsTest {

    @Test
    fun `placement is usable only when every switch above it is on`() {
        val settings = sideAdsSettings {
            interstitial {
                addPlacement("on", "unit")
                addPlacement("off", "unit", enabled = false)
            }
            rewarded {
                enabled = false
                addPlacement("rewarded", "unit")
            }
        }

        assertTrue(settings.isPlacementEnabled(AdType.INTERSTITIAL)) // default placement: "on"
        assertTrue(settings.isPlacementEnabled(AdType.INTERSTITIAL, "on"))
        assertFalse(settings.isPlacementEnabled(AdType.INTERSTITIAL, "off"))
        assertFalse(settings.isPlacementEnabled(AdType.INTERSTITIAL, "missing"))
        assertFalse(settings.isPlacementEnabled(AdType.REWARDED, "rewarded")) // format kapalı
        assertFalse(settings.isPlacementEnabled(AdType.APP_OPEN)) // format bloğu yok
        assertFalse(settings.newBuilder().apply { adsEnabled = false }.build().isPlacementEnabled(AdType.INTERSTITIAL, "on"))
    }
}
