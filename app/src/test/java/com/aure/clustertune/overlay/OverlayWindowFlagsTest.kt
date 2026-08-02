package com.aure.clustertune.overlay

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayWindowFlagsTest {

    @Test
    fun modalRetainsFocusAndCommonLayoutFlags() {
        val flags = modalWindowFlags()

        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertCommonFlagsPresent(flags)
    }

    private fun assertCommonFlagsPresent(flags: Int) {
        val commonFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        assertEquals(commonFlags, flags and commonFlags)
    }
}
