package com.sideaccountapps.ads.core.internal

import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.SideAdsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {

    private fun SideAdsSettings.units(type: AdType, placementId: String) =
        format(type)!!.placement(placementId)!!.adUnitIds

    @Test
    fun `ad unit is a single id or an ordered array`() {
        val settings = SettingsJson.parse(
            """
            {"interstitial": {"placements": {
              "single": {"adUnit": "only"},
              "chain":  {"adUnit": ["high", "", "all"]},
              "empty":  {"adUnit": []},
              "none":   {}
            }}}
            """,
            null
        )

        assertEquals(listOf("only"), settings.units(AdType.INTERSTITIAL, "single"))
        assertEquals(listOf("high", "all"), settings.units(AdType.INTERSTITIAL, "chain"))
        assertEquals(emptyList<String>(), settings.units(AdType.INTERSTITIAL, "empty"))
        assertEquals(emptyList<String>(), settings.units(AdType.INTERSTITIAL, "none"))
    }

    @Test
    fun `local settings are applied after the json and again on every json update`() {
        val local: SideAdsSettings.Builder.() -> Unit = {
            testMode = true
            admobTestDeviceIds = listOf("HASHED_ID")
            childDirected = true
            doNotSell = true
        }
        val first = """{"interstitialMinIntervalSec": 45, "testMode": false, "childDirected": false, "doNotSell": false}"""
        val update = """{"interstitialMinIntervalSec": 90}"""

        // SideAds.init(json, localSettings) ve SideAds.setSettings(json) aynı yolu kullanır.
        for ((json, interval) in listOf(first to 45, update to 90)) {
            val settings = SettingsJson.parseOrNull(json, local)!!
            assertEquals(interval, settings.interstitialMinIntervalSec)
            assertTrue(settings.testMode)
            assertTrue(settings.debugLogging) // testMode debug log'u açar
            assertEquals(listOf("HASHED_ID"), settings.admobTestDeviceIds)
            assertEquals(true, settings.childDirected)
            assertEquals(true, settings.doNotSell)
        }
    }

    @Test
    fun `local only fields and unknown keys in json are ignored`() {
        val settings = SettingsJson.parse(
            """
            {
              "testMode": true, "admobTestDeviceIds": ["X"], "childDirected": true, "doNotSell": true,
              "networkOrder": "admob,applovin", "loadTimeoutSec": 8, "applovinSdkKey": "KEY", "somethingNew": 1,
              "interstitial": {"networkOrder": ["admob"], "placements": {
                "p": {"adUnit": "u", "loadTimeoutSec": 5, "networkOrder": "admob"}
              }}
            }
            """,
            null
        )

        assertFalse(settings.testMode)
        assertEquals(emptyList<String>(), settings.admobTestDeviceIds)
        assertNull(settings.childDirected)
        assertNull(settings.doNotSell)
        assertTrue(settings.mediatorSettings.isEmpty())
        assertEquals(listOf("u"), settings.units(AdType.INTERSTITIAL, "p"))
    }

    // Not: JSON nesnesinde sıra garanti olmadığından (masaüstü org.json HashMap kullanır) "ilk placement default olur"
    // kuralı burada test edilmez; README JSON'da defaultPlacement'ı açıkça vermeyi önerir.
    @Test
    fun `missing keys keep the defaults and defaultPlacement is honored`() {
        val settings = SettingsJson.parse(
            """
            {"banner": {"placements": {"b1": {"adUnit": "x"}, "b2": {"adUnit": "y", "preload": false}}},
             "appOpen": {"preload": true, "defaultPlacement": "a2",
                         "placements": {"a1": {"adUnit": "x"}, "a2": {"adUnit": "y", "reloadAfterShow": false}}}}
            """,
            null
        )
        val defaults = SideAdsSettings.Builder().build()

        assertEquals(defaults.interstitialMinIntervalSec, settings.interstitialMinIntervalSec)
        assertEquals(defaults.adTtlMinutes, settings.adTtlMinutes)
        assertFalse(settings.format(AdType.BANNER)!!.preload)
        val appOpen = settings.format(AdType.APP_OPEN)!!
        assertEquals("a2", appOpen.defaultPlacementId)
        assertFalse(appOpen.placement(null)!!.reloadAfterShow)
        assertNull(settings.format(AdType.REWARDED))
    }

    @Test
    fun `invalid json gives null`() {
        assertNull(SettingsJson.parseOrNull("{not json", null))
    }
}
