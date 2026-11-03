package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdGainTest {
    private val values = AdGain.VALUES
    private val def = AdGain.DEF

    @Test fun everyOptionMapsToItsOwnFactor() {
        for (v in values) assertEquals(v / 100f, AdGain.factorOf(v, values, def), 0f)
    }

    @Test fun strayValuesFallBackToTheDefault() {
        for (v in listOf(0, 1, 2, 3, 4, 6, 33, 99, 101, -25, Int.MAX_VALUE, Int.MIN_VALUE))
            assertEquals(def / 100f, AdGain.factorOf(v, values, def), 0f)
    }

    @Test fun defaultIs25AndAnOption() {
        assertEquals(25, def)
        assertTrue(def in values)
        assertEquals(listOf(100, 50, 25, 15, 10, 5), values)
        assertEquals(values.size, AdGain.OPTIONS.size)
    }

    @Test fun normalLeavesGainAlone() {
        for (raw in listOf(0.0001f, 0.03f, 0.5f, 1f, 2f)) assertEquals(raw, AdGain.scaled(raw, 1f), 0f)
    }

    @Test fun zeroNegativeAndNanPassThrough() {
        assertEquals(0f, AdGain.scaled(0f, 0.25f), 0f)
        assertEquals(-1f, AdGain.scaled(-1f, 0.25f), 0f)
        assertTrue(AdGain.scaled(Float.NaN, 0.25f).isNaN())
    }

    @Test fun fullGainIsScaledByTheFactor() {
        assertEquals(0.5f, AdGain.scaled(1f, 0.5f), 1e-6f)
        assertEquals(0.25f, AdGain.scaled(1f, 0.25f), 1e-6f)
        assertEquals(0.15f, AdGain.scaled(1f, 0.15f), 1e-6f)
        assertEquals(0.1f, AdGain.scaled(1f, 0.1f), 1e-6f)
        assertEquals(0.05f, AdGain.scaled(1f, 0.05f), 1e-6f)
    }

    @Test fun neverGoesBelowTheFloor() {
        // 0.5 * 0.05 = 0.025, below the 0.03 floor.
        assertEquals(AdGain.MIN_GAIN, AdGain.scaled(0.5f, 0.05f), 1e-6f)
        assertEquals(AdGain.MIN_GAIN, AdGain.scaled(0.1f, 0.1f), 1e-6f)
    }

    @Test fun neverRaisesAnAlreadyQuietTrack() {
        // Twitch's own value is already under the floor: keep it, never turn it up.
        for (raw in listOf(0.001f, 0.01f, 0.029f)) for (f in values.map { it / 100f })
            assertTrue(AdGain.scaled(raw, f) <= raw)
    }

    @Test fun scaledIsNeverLouderAndMonotonic() {
        val raws = (1..200).map { it / 100f }
        val fs = values.map { it / 100f }.sortedDescending()
        for (raw in raws) {
            var prev = Float.MAX_VALUE
            for (f in fs) {
                val g = AdGain.scaled(raw, f)
                assertTrue("raw=$raw f=$f g=$g", g <= raw)
                assertTrue("raw=$raw f=$f not monotonic", g <= prev)
                prev = g
            }
        }
    }

    @Test fun decibels() {
        assertEquals(0f, AdGain.dbOf(1f), 0f)
        assertEquals(0f, AdGain.dbOf(1.5f), 0f)
        assertEquals(0f, AdGain.dbOf(0f), 0f)
        assertEquals(-6.02f, AdGain.dbOf(0.5f), 0.01f)
        assertEquals(-12.04f, AdGain.dbOf(0.25f), 0.01f)
        assertEquals(-16.48f, AdGain.dbOf(0.15f), 0.01f)
        assertEquals(-20f, AdGain.dbOf(0.1f), 0.01f)
        assertEquals(-26.02f, AdGain.dbOf(0.05f), 0.01f)
    }
}
