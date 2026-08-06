package com.aure.clustertune.overlay

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBackHandlingTest {

    @Test
    fun `back release dismisses overlay`() {
        assertTrue(
            shouldDismissOverlayOnKeyEvent(
                keyCode = KeyEvent.KEYCODE_BACK,
                action = KeyEvent.ACTION_UP,
                canceled = false,
            ),
        )
    }

    @Test
    fun `back press is consumed before release without dismissing`() {
        assertFalse(
            shouldDismissOverlayOnKeyEvent(
                keyCode = KeyEvent.KEYCODE_BACK,
                action = KeyEvent.ACTION_DOWN,
                canceled = false,
            ),
        )
    }

    @Test
    fun `canceled back release does not dismiss overlay`() {
        assertFalse(
            shouldDismissOverlayOnKeyEvent(
                keyCode = KeyEvent.KEYCODE_BACK,
                action = KeyEvent.ACTION_UP,
                canceled = true,
            ),
        )
    }

    @Test
    fun `unrelated key does not dismiss overlay`() {
        assertFalse(
            shouldDismissOverlayOnKeyEvent(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_UP,
                canceled = false,
            ),
        )
    }
}
