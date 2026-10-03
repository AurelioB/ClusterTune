package com.aure.clustertune.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceHudVendorStateTest {
    @Test
    fun `AYN and Retroid identities are supported without matching arbitrary Android devices`() {
        assertTrue(isAynOrRetroidDevice(VendorDeviceIdentity("AYN", "AYN", "Thor")))
        assertTrue(isAynOrRetroidDevice(VendorDeviceIdentity("Moorechip", "Retroid", "Pocket 5")))
        assertFalse(isAynOrRetroidDevice(VendorDeviceIdentity("Google", "google", "Pixel 9")))
    }

    @Test
    fun `known vendor mode values use Thor labels and unknown values remain visible`() {
        assertEquals("Standard", vendorPerformanceModeLabel(0))
        assertEquals("Medium", vendorPerformanceModeLabel(1))
        assertEquals("High", vendorPerformanceModeLabel(2))
        assertEquals("Mode 7", vendorPerformanceModeLabel(7))

        assertEquals("Off", vendorFanModeLabel(0))
        assertEquals("Quiet", vendorFanModeLabel(1))
        assertEquals("Smart", vendorFanModeLabel(4))
        assertEquals("Sports", vendorFanModeLabel(5))
        assertEquals("Custom", vendorFanModeLabel(6))
        assertEquals("Mode 3", vendorFanModeLabel(3))
    }
}
