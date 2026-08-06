package com.aure.clustertune.ui.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CtNumericFieldTest {
    @Test
    fun numericDigitsRemovesNonDigitsAndHonorsLimit() {
        assertEquals("123", numericDigits("a1-2 34", maxDigits = 3))
    }

    @Test
    fun numericDigitsAllowsTemporarilyEmptyInput() {
        assertEquals("", numericDigits("-", maxDigits = 3))
    }

    @Test
    fun numericDigitsTreatsNegativeLimitAsZero() {
        assertEquals("", numericDigits("123", maxDigits = -1))
    }
}
