package cc.star0.xposed.hyperstylus.ui.components

import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Test

/** Validate user input against charging safety limits and negative signal thresholds. */
class ThresholdSelectionTest {
    @Test
    fun crossingChargeHandlesKeepsRequiredGap() {
        val selection = ThresholdSelection(0..100, 5, 5)
        assertEquals(75..80, selection.constrain(75..80, 79f..80f))
        assertEquals(75..80, selection.constrain(75..80, 75f..77f))
    }

    @Test
    fun outOfBoundsDragsStayWithinChargeLimits() {
        val selection = ThresholdSelection(0..100, 5, 5)
        assertEquals(0..100, selection.constrain(50..80, -40f..150f))
        assertEquals(95..100, selection.constrain(50..80, 150f..160f))
    }

    @Test
    fun signalStepsWorkAcrossNegativeValuesAndUnalignedInput() {
        val selection = ThresholdSelection(-120..-20, 5, 5)
        assertEquals(-80..-60, selection.constrain(-80..-60, -82f..-59f))
        assertEquals(-70..-60, selection.adjust(-73..-60, true, 1))
        assertEquals(-75..-60, selection.adjust(-73..-60, true, -1))
    }

    @Test
    fun accessibilityAtBoundsCannotMoveOutsideRange() {
        val selection = ThresholdSelection(0..100, 5, 5)
        assertEquals(0..100, selection.adjust(0..100, true, -1))
        assertEquals(0..100, selection.adjust(0..100, false, 1))
    }

    @Test
    fun repeatedAccessibilityAdjustmentsKeepSafeChargeRange() {
        val selection = ThresholdSelection(0..100, 5, 5)
        var value = 50..80
        repeat(40) { value = selection.adjust(value, true, 1) }
        assertTrue(value.first >= 0 && value.last <= 100 && value.last - value.first >= 5)
        assertEquals(75..80, value)
    }
}
