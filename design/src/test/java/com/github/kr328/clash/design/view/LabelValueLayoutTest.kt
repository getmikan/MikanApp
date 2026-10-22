package com.github.kr328.clash.design.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelValueLayoutTest {
    @Test
    fun theValueMovesBelowOnlyWhenTheTwoDoNotFit() {
        assertFalse(valueApart(room = 300, labelWidth = 120, valueWidth = 160, gap = 20))
        assertTrue(valueApart(room = 299, labelWidth = 120, valueWidth = 160, gap = 20))
        // A large font: "Премиум +" and "осталось 15,00 ГиБ" on the narrow Home card.
        assertTrue(valueApart(room = 520, labelWidth = 240, valueWidth = 290, gap = 18))
        assertFalse(valueApart(room = null, labelWidth = 5000, valueWidth = 5000, gap = 20))
    }

    @Test
    fun sideBySideTheValueEndsTheLineOnTheLabelsBaseline() {
        val label = Measured(width = 120, height = 40, baseline = 30)
        val value = Measured(width = 160, height = 32, baseline = 24)

        val at = placeLabelValue(label, value, apart = false, width = 400, indent = 36, gap = 20, lineGap = 4)
        assertEquals(LabelValuePlacement(labelTop = 0, valueStart = 240, valueTop = 6, width = 300, height = 40), at)

        // A value whose baseline sits lower moves the label down instead.
        val tall = Measured(width = 160, height = 48, baseline = 40)
        val down = placeLabelValue(label, tall, apart = false, width = 400, indent = 36, gap = 20, lineGap = 4)
        assertEquals(10, down.labelTop)
        assertEquals(0, down.valueTop)
        assertEquals(50, down.height)
    }

    @Test
    fun sideBySideTheValueNeverCoversTheLabel() {
        val at = placeLabelValue(Measured(120, 40, 30), Measured(160, 32, 24), apart = false, width = 0, indent = 0, gap = 20, lineGap = 4)
        assertEquals(140, at.valueStart)
    }

    @Test
    fun apartTheValueStartsUnderTheLabelsText() {
        val label = Measured(width = 240, height = 40, baseline = 30)
        val value = Measured(width = 290, height = 32, baseline = 24)

        val at = placeLabelValue(label, value, apart = true, width = 520, indent = 36, gap = 18, lineGap = 4)
        assertEquals(LabelValuePlacement(labelTop = 0, valueStart = 36, valueTop = 44, width = 326, height = 76), at)
    }

    @Test
    fun withoutBaselinesTheTwoAreCentred() {
        val at = placeLabelValue(Measured(120, 40, -1), Measured(160, 20, -1), apart = false, width = 400, indent = 0, gap = 20, lineGap = 4)
        assertEquals(0, at.labelTop)
        assertEquals(10, at.valueTop)
    }

    @Test
    fun withoutAValueTheLabelStandsAlone() {
        val at = placeLabelValue(Measured(120, 40, 30), null, apart = false, width = 400, indent = 36, gap = 20, lineGap = 4)
        assertEquals(LabelValuePlacement(0, 0, 0, 120, 40), at)
    }
}
