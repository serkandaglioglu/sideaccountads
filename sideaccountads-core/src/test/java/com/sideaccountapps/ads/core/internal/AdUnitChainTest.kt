package com.sideaccountapps.ads.core.internal

import android.content.ContextWrapper
import com.sideaccountapps.ads.core.AdType
import com.sideaccountapps.ads.core.internal.FakeMediator.Outcome
import com.sideaccountapps.ads.core.mediation.MediatedFullscreenAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdUnitChainTest {

    private val timer = FakeTimer()
    private val chain = AdUnitChain(timer)
    private val mediator = FakeMediator()
    private val results = mutableListOf<ChainResult<MediatedFullscreenAd>>()

    private fun load(vararg adUnitIds: String) {
        val label = "INTERSTITIAL/test"
        // Engine'deki yükleyicinin aynısı; yalnızca sonuç main thread'e taşınmadan teslim edilir.
        val loader = fullscreenLoader(mediator, ContextWrapper(null), AdType.INTERSTITIAL, label) { it() }
        chain.run(label, adUnitIds.toList(), loader, discard = { it.destroy() }) { results += it }
    }

    @Test
    fun `stops at the first ad unit that loads`() {
        mediator.respond("high", Outcome.Load)
        mediator.respond("all", Outcome.Load)

        load("high", "all")

        assertEquals(listOf("high"), mediator.requested)
        assertEquals("high", (results.single() as ChainResult.Loaded).adUnitId)
    }

    @Test
    fun `moves to the next ad unit when one fails`() {
        mediator.respond("high", Outcome.Fail(3, "No fill"))
        mediator.respond("all", Outcome.Load)

        load("high", "all")

        assertEquals(listOf("high", "all"), mediator.requested)
        assertEquals("all", (results.single() as ChainResult.Loaded).adUnitId)
    }

    @Test
    fun `gives a single failure with every ad unit error when all fail`() {
        mediator.respond("high", Outcome.Fail(3, "No fill"))
        mediator.respond("all", Outcome.Fail(2, "Network error"))

        load("high", "all")

        assertEquals(listOf("high", "all"), mediator.requested)
        val error = (results.single() as ChainResult.Failed).error
        assertTrue(error, "high: [3] No fill" in error)
        assertTrue(error, "all: [2] Network error" in error)
    }

    @Test
    fun `unanswered request fails after the safety timeout and its late result is discarded`() {
        mediator.respond("high", Outcome.Silent)
        mediator.respond("all", Outcome.Load)

        load("high", "all")
        timer.advanceBy(NO_RESPONSE_MS - 1)
        assertEquals(listOf("high"), mediator.requested)
        assertTrue(results.isEmpty())

        timer.advanceBy(1)
        assertEquals(listOf("high", "all"), mediator.requested)
        assertEquals("all", (results.single() as ChainResult.Loaded).adUnitId)

        val late = mediator.completeLate("high")
        assertTrue(late.destroyed)
        assertEquals(1, results.size)
    }

    @Test
    fun `answered requests do not trip the safety timeout`() {
        mediator.respond("only", Outcome.Load)

        load("only")
        timer.advanceBy(10 * NO_RESPONSE_MS)

        assertEquals(1, results.size)
        assertFalse(mediator.delivered.single().destroyed)
    }

    @Test
    fun `abandoned step runs its cleanup, a successful one does not`() {
        val cleaned = mutableListOf<String>()
        val loader = AdUnitChain.Loader<String> { step ->
            if (step.adUnitId == "bad") step.fail(1, "bad request")
            step.onAbandon { cleaned += step.adUnitId }
            if (step.adUnitId == "good") step.succeed("ad")
        }

        chain.run("BANNER/test", listOf("bad", "good"), loader, discard = {}) {}

        assertEquals(listOf("bad"), cleaned)
    }

    @Test
    fun `inactive chain stops without a result`() {
        mediator.respond("high", Outcome.Fail(3, "No fill"))
        var active = true
        val label = "BANNER/test"
        val loader = fullscreenLoader(mediator, ContextWrapper(null), AdType.BANNER, label) {
            active = false // ör. banner ilk hatadan sonra destroy edildi
            it()
        }

        chain.run(label, listOf("high", "all"), loader, discard = {}, isActive = { active }) { results += it }

        assertEquals(listOf("high"), mediator.requested)
        assertTrue(results.isEmpty())
    }
}
