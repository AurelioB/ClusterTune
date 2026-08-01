package com.aure.clustertune.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aure.clustertune.ui.designsystem.theme.ClusterTuneTheme
import com.aure.clustertune.ui.designsystem.theme.clusterTuneColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun themeProvidesMaterialAndSemanticColorsToContent() {
        val scheme = lightColorScheme(
            primary = Color.Magenta,
            surface = Color.Cyan,
        )
        var observedPrimary = Color.Unspecified
        var observedRowSurface = Color.Unspecified

        composeRule.setContent {
            ClusterTuneTheme(colorScheme = scheme) {
                observedPrimary = MaterialTheme.colorScheme.primary
                observedRowSurface = MaterialTheme.clusterTuneColors.rowSurface
                Text("Themed content")
            }
        }

        composeRule.onNodeWithText("Themed content").assertExists()
        composeRule.runOnIdle {
            assertEquals(Color.Magenta, observedPrimary)
            assertEquals(Color.Cyan, observedRowSurface)
        }
    }
}
