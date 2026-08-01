package com.aure.clustertune.ui.designsystem.component

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aure.clustertune.R

private val MaterialSymbolsRounded = FontFamily(
    Font(
        resId = R.font.material_symbols_rounded_200,
        weight = FontWeight.W200,
    ),
)

/** Shared icon entry point for font-backed Material Symbols and vector icons. */
@Composable
internal fun CtIcon(
    symbol: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Text(
        text = symbol,
        modifier = modifier.clearAndSetSemantics {
            if (contentDescription != null) {
                this.contentDescription = contentDescription
            }
        },
        color = tint,
        fontFamily = MaterialSymbolsRounded,
        fontWeight = FontWeight.W200,
        fontSize = size.value.sp,
        lineHeight = size.value.sp,
        textAlign = TextAlign.Center,
        style = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

@Composable
internal fun CtIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
