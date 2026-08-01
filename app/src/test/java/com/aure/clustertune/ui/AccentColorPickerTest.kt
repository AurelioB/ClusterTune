package com.aure.clustertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccentColorPickerTest {
    @Test fun primaryColorsConvertToHsv() {
        assertEquals(HsvColor(0f, 1f, 1f), argbToHsv(0xFFFF0000.toInt()))
        assertEquals(HsvColor(120f, 1f, 1f), argbToHsv(0xFF00FF00.toInt()))
        assertEquals(HsvColor(240f, 1f, 1f), argbToHsv(0xFF0000FF.toInt()))
    }

    @Test fun hsvRoundTripsRgb() {
        listOf(0xFF3F51B5.toInt(), 0xFF00FF00.toInt(), 0xFFFF8800.toInt()).forEach { color ->
            val roundTrip = hsvToOpaqueArgb(argbToHsv(color).hue, argbToHsv(color).saturation, argbToHsv(color).value)
            assertTrue(abs(((roundTrip ushr 16) and 255) - ((color ushr 16) and 255)) <= 1)
            assertTrue(abs(((roundTrip ushr 8) and 255) - ((color ushr 8) and 255)) <= 1)
            assertTrue(abs((roundTrip and 255) - (color and 255)) <= 1)
        }
    }

    @Test fun hexParsingAndFormatting() {
        assertEquals(0xFF12ABC3.toInt(), parseHexColor("#12aBc3"))
        assertEquals(0xFF12ABC3.toInt(), parseHexColor("12aBc3"))
        assertEquals("#12ABC3", formatHexColor(0x0012ABC3))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#1234567"))
        assertNull(parseHexColor("#12zz56"))
    }

    @Test fun hueAndComponentClamping() {
        assertEquals(350f, normalizeHue(-10f), 0f)
        assertEquals(0f, normalizeHue(360f), 0f)
        assertEquals(1f, hsvToOpaqueArgb(0f, 2f, 2f).let { argbToHsv(it).saturation }, 0f)
        assertEquals(0xFFFF0000.toInt(), hsvToOpaqueArgb(360f, 1f, 1f))
    }

    @Test fun alphaIsAlwaysOpaque() {
        assertEquals(0xFF, hsvToOpaqueArgb(20f, .5f, .5f) ushr 24)
        assertEquals(0xFF123456.toInt(), parseHexColor("123456"))
    }
}
