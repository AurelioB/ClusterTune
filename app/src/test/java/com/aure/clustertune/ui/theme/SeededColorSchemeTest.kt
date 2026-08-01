package com.aure.clustertune.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class SeededColorSchemeTest {
    @Test
    fun sameSeedProducesSameScheme() {
        val first = seededColorScheme(seedColor = 0xFF00639A.toInt(), darkTheme = false)
        val second = seededColorScheme(seedColor = 0xFF00639A.toInt(), darkTheme = false)

        assertEquals(first.primary, second.primary)
        assertEquals(first.onPrimary, second.onPrimary)
        assertEquals(first.surface, second.surface)
        assertEquals(first.onSurface, second.onSurface)
        assertEquals(first.outline, second.outline)
    }

    @Test
    fun lightAndDarkSchemesRemainDistinct() {
        val light = seededColorScheme(seedColor = 0xFF8E24AA.toInt(), darkTheme = false)
        val dark = seededColorScheme(seedColor = 0xFF8E24AA.toInt(), darkTheme = true)

        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.primary, dark.primary)
    }

    @Test
    fun generatedContentColorsHaveReadableContrast() {
        listOf(false, true).forEach { darkTheme ->
            val scheme = seededColorScheme(0xFF3F51B5.toInt(), darkTheme)
            val pairs = listOf(
                scheme.onSurface to scheme.surface,
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onError to scheme.error,
            )

            pairs.forEach { (content, container) ->
                assertTrue(
                    "Expected readable contrast for darkTheme=$darkTheme",
                    contrastRatio(content, container) >= 4.5f,
                )
            }
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (max(firstLuminance, secondLuminance) + 0.05f) /
            (min(firstLuminance, secondLuminance) + 0.05f)
    }
}
