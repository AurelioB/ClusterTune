package com.aure.clustertune.ui.designsystem.preview

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aure.clustertune.ui.designsystem.theme.ClusterTuneTheme

/** Deterministic preview wrapper; it never reads OEM wallpaper or system dynamic color. */
@Composable
internal fun ClusterTunePreview(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) {
        darkColorScheme(primary = Color(0xFFB7C8FF))
    } else {
        lightColorScheme(primary = Color(0xFF415F91))
    }
    ClusterTuneTheme(colorScheme = scheme, content = content)
}

@Composable
internal fun ClusterTuneLightPreview(content: @Composable () -> Unit) =
    ClusterTunePreview(darkTheme = false, content = content)

@Composable
internal fun ClusterTuneDarkPreview(content: @Composable () -> Unit) =
    ClusterTunePreview(darkTheme = true, content = content)
