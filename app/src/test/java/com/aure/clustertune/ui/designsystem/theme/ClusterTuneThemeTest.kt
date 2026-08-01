package com.aure.clustertune.ui.designsystem.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTuneThemeTest {
    @Test
    fun semanticColorsAliasMaterialRoles() {
        val scheme = lightColorScheme()
        val colors = ClusterTuneColors.from(scheme)
        assertEquals(scheme.surface, colors.rowSurface)
        assertEquals(scheme.secondaryContainer, colors.selectedRowSurface)
        assertEquals(scheme.outlineVariant, colors.divider)
        // Compose stores packed color channels at 8-bit precision.
        assertEquals(0.32f, colors.overlayScrim.alpha, 0.002f)
    }

    @Test
    fun customSchemeIsPreserved() {
        val scheme = lightColorScheme(surface = Color.Red)
        assertEquals(Color.Red, ClusterTuneColors.from(scheme).rowSurface)
    }

    @Test
    fun semanticSurfacesKeepReadableContentInBothThemes() {
        val schemes = listOf(
            androidx.compose.material3.lightColorScheme(),
            androidx.compose.material3.darkColorScheme(),
        )
        schemes.forEach { scheme ->
            val colors = ClusterTuneColors.from(scheme)
            assertTrue(contrastRatio(scheme.onSurface, colors.rowSurface) >= 3f)
            assertTrue(contrastRatio(scheme.onSurfaceVariant, colors.modalSurface) >= 2f)
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val first = foreground.luminance() + 0.05f
        val second = background.luminance() + 0.05f
        return maxOf(first, second) / minOf(first, second)
    }
}
