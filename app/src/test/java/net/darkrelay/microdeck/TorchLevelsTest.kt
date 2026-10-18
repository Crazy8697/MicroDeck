package net.darkrelay.microdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchLevelsTest {

    @Test fun missingMaxFallsBackToOnOff() {
        val t = TorchLevels.from(null, 10)
        assertEquals(TorchLevels.ON_OFF, t)
        assertFalse(t.adjustable)
    }

    @Test fun maxOfOneIsOnOff() {
        assertFalse(TorchLevels.from(1, 1).adjustable)
    }

    @Test fun zeroOrNegativeMaxIsOnOff() {
        assertEquals(TorchLevels.ON_OFF, TorchLevels.from(0, 0))
        assertEquals(TorchLevels.ON_OFF, TorchLevels.from(-3, 2))
    }

    @Test fun deviceDefaultIsUsed() {
        val t = TorchLevels.from(45, 21)
        assertTrue(t.adjustable)
        assertEquals(45, t.max)
        assertEquals(21, t.default)
    }

    @Test fun missingOrBadDefaultBecomesMiddle() {
        assertEquals(5, TorchLevels.from(10, null).default)
        assertEquals(5, TorchLevels.from(10, 0).default)
        assertEquals(5, TorchLevels.from(10, 11).default)
        assertEquals(2, TorchLevels.from(3, 99).default)
    }

    @Test fun clampKeepsLevelsInRange() {
        val t = TorchLevels.from(8, 4)
        assertEquals(1, t.clamp(-5))
        assertEquals(1, t.clamp(0))
        assertEquals(4, t.clamp(4))
        assertEquals(8, t.clamp(8))
        assertEquals(8, t.clamp(44))
    }

    @Test fun onOffClampsEverythingToOne() {
        assertEquals(1, TorchLevels.ON_OFF.clamp(21))
        assertEquals(1, TorchLevels.ON_OFF.clamp(0))
    }

    @Test fun sliderMappingRoundTrips() {
        val t = TorchLevels.from(45, 21)
        for (level in 1..45) assertEquals(level, t.fromProgress(t.toProgress(level)))
        assertEquals(20, t.toProgress(21))
        assertEquals(0, t.toProgress(-7))
        assertEquals(44, t.toProgress(100))
        assertEquals(45, t.fromProgress(999))
        assertEquals(1, t.fromProgress(-1))
    }
}
