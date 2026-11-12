package com.sideaccountapps.ads.core.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class InitGateTest {

    private val timer = FakeTimer()
    private val gate = InitGate(timer, timeoutMs = 20_000)

    @Test
    fun `requests made before init run once when the SDK is ready`() {
        var runs = 0
        var ready = 0
        gate.runWhenReady { runs++ }
        gate.runWhenReady { runs++ }
        gate.onReady { ready++ }

        gate.start {}
        assertEquals(0, runs)

        assertEquals(2, gate.open())
        assertEquals(2, runs)
        assertEquals(1, ready)

        // İkinci hazır sinyali hiçbir işi tekrar çalıştırmaz; sonraki işler hemen çalışır.
        assertEquals(0, gate.open())
        gate.runWhenReady { runs++ }
        gate.onReady { ready++ }
        assertEquals(3, runs)
        assertEquals(2, ready)
    }

    @Test
    fun `timeout releases waiting requests once and a late ready does not rerun them`() {
        var runs = 0
        var ready = 0
        var timeouts = 0
        gate.runWhenReady { runs++ }
        gate.onReady { ready++ }

        gate.start { timeouts++ }
        timer.advanceBy(19_999)
        assertEquals(0, runs)

        timer.advanceBy(1)
        assertEquals(1, timeouts)
        assertEquals(1, runs)
        assertEquals(1, ready)

        assertEquals(0, gate.open())
        assertEquals(1, runs)
        assertEquals(1, ready)
    }

    @Test
    fun `nothing times out before init starts`() {
        // Ör. UMP consent bekleniyor: SDK başlamadığı sürece istekler bekler.
        var runs = 0
        gate.runWhenReady { runs++ }

        timer.advanceBy(60_000)

        assertEquals(0, runs)
    }

    @Test
    fun `a job queued by another job during release runs too`() {
        val order = mutableListOf<String>()
        gate.runWhenReady {
            order += "first"
            gate.runWhenReady { order += "nested" }
        }

        gate.open()

        assertEquals(listOf("first", "nested"), order)
    }
}
