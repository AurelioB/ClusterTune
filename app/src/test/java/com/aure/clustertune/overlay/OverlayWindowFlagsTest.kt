package com.aure.clustertune.overlay

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OverlayWindowFlagsTest {

    @Test
    fun modalKeepsTheUnderlyingAppFocusedAndRetainsCommonLayoutFlags() {
        val flags = modalWindowFlags()

        assertEquals(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        assertCommonFlagsPresent(flags)
    }

    @Test
    fun performanceHudKeepsTheUnderlyingAppFocusedButAcceptsSliderTouches() {
        val flags = performanceHudWindowFlags()

        assertEquals(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        assertFalse(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertCommonFlagsPresent(flags)
    }

    @Test
    fun performanceHudDefaultsToTwelveDpTopStartMargins() {
        val config = PerformanceHudWindowConfig()

        assertEquals(12, config.horizontalMarginDp)
        assertEquals(12, config.verticalMarginDp)
    }

    private fun assertCommonFlagsPresent(flags: Int) {
        val commonFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        assertEquals(commonFlags, flags and commonFlags)
    }
}
