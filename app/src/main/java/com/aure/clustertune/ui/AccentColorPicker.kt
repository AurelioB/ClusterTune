package com.aure.clustertune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.res.stringResource
import com.aure.clustertune.R
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class HsvColor(val hue: Float, val saturation: Float, val value: Float)

internal fun normalizeHue(hue: Float): Float {
    val normalized = hue % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

internal fun clampSaturation(saturation: Float): Float = saturation.coerceIn(0f, 1f)
internal fun clampValue(value: Float): Float = value.coerceIn(0f, 1f)

internal fun hsvToOpaqueArgb(hue: Float, saturation: Float, value: Float): Int {
    val h = normalizeHue(hue)
    val s = clampSaturation(saturation)
    val v = clampValue(value)
    val chroma = v * s
    val x = chroma * (1f - abs((h / 60f % 2f) - 1f))
    val match = v - chroma
    val (r, g, b) = when (h) {
        in 0f..<60f -> Triple(chroma, x, 0f)
        in 60f..<120f -> Triple(x, chroma, 0f)
        in 120f..<180f -> Triple(0f, chroma, x)
        in 180f..<240f -> Triple(0f, x, chroma)
        in 240f..<300f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    return (0xFF shl 24) or
        (((r + match) * 255f).roundToInt().coerceIn(0, 255) shl 16) or
        (((g + match) * 255f).roundToInt().coerceIn(0, 255) shl 8) or
        ((b + match) * 255f).roundToInt().coerceIn(0, 255)
}

internal fun argbToHsv(argb: Int): HsvColor {
    val red = ((argb ushr 16) and 0xFF) / 255f
    val green = ((argb ushr 8) and 0xFF) / 255f
    val blue = (argb and 0xFF) / 255f
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val hue = when {
        delta == 0f -> 0f
        maximum == red -> normalizeHue(60f * ((green - blue) / delta))
        maximum == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return HsvColor(hue, if (maximum == 0f) 0f else delta / maximum, maximum)
}

internal fun parseHexColor(text: String): Int? {
    val value = text.trim().removePrefix("#")
    if (value.length != 6 || value.any { it !in "0123456789abcdefABCDEF" }) return null
    return (value.toLongOrNull(16)?.toInt() ?: return null) or (0xFF shl 24)
}

internal fun formatHexColor(argb: Int): String = "#%06X".format(java.util.Locale.ROOT, argb and 0xFFFFFF)

private fun sanitizeHexInput(input: String): String {
    val hadHash = input.trimStart().startsWith("#")
    val digits = input.filter { it in "0123456789abcdefABCDEF" }.take(6)
    return (if (hadHash) "#" else "") + digits
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentColorPickerDialog(initialColor: Int, onDismiss: () -> Unit, onColorSelected: (Int) -> Unit) {
    var hsv by remember(initialColor) { mutableStateOf(argbToHsv(initialColor)) }
    var previewColor by remember(initialColor) { mutableIntStateOf(hsvToOpaqueArgb(hsv.hue, hsv.saturation, hsv.value)) }
    var hex by remember(initialColor) { mutableStateOf(formatHexColor(previewColor)) }
    val validHex = parseHexColor(hex) != null

    fun updateHsv(next: HsvColor) {
        hsv = HsvColor(normalizeHue(next.hue), clampSaturation(next.saturation), clampValue(next.value))
        previewColor = hsvToOpaqueArgb(hsv.hue, hsv.saturation, hsv.value)
        hex = formatHexColor(previewColor)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier.widthIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val preview = Color(previewColor)
                val previewDescription = stringResource(R.string.settings_color_preview, hex)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.padding(top = 8.dp).width(72.dp).height(56.dp).semantics {
                            contentDescription = previewDescription
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = preview,
                    ) {}
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { input ->
                            hex = sanitizeHexInput(input)
                            parseHexColor(hex)?.let { parsed ->
                                val parsedHsv = argbToHsv(parsed)
                                val hue = if (parsedHsv.saturation == 0f) hsv.hue else parsedHsv.hue
                                updateHsv(parsedHsv.copy(hue = hue))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.settings_hex_color)) },
                        singleLine = true,
                        isError = hex.isNotEmpty() && !validHex,
                        supportingText = { if (hex.isNotEmpty() && !validHex) Text(stringResource(R.string.settings_enter_hex_digits)) },
                    )
                }
                val planeShape = RoundedCornerShape(12.dp)
                val planeDescription = stringResource(R.string.settings_saturation_brightness_plane)
                val planeState = stringResource(
                    R.string.settings_saturation_brightness_state,
                    (hsv.saturation * 100).roundToInt(),
                    (hsv.value * 100).roundToInt(),
                )
                val increaseSaturation = stringResource(R.string.settings_increase_saturation)
                val decreaseSaturation = stringResource(R.string.settings_decrease_saturation)
                val increaseBrightness = stringResource(R.string.settings_increase_brightness)
                val decreaseBrightness = stringResource(R.string.settings_decrease_brightness)
                Box(
                    modifier = Modifier.fillMaxWidth().height(124.dp).clip(planeShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, planeShape)
                        .semantics {
                            contentDescription = planeDescription
                            stateDescription = planeState
                            customActions = listOf(
                                CustomAccessibilityAction(increaseSaturation) {
                                    updateHsv(hsv.copy(saturation = hsv.saturation + .05f))
                                    true
                                },
                                CustomAccessibilityAction(decreaseSaturation) {
                                    updateHsv(hsv.copy(saturation = hsv.saturation - .05f))
                                    true
                                },
                                CustomAccessibilityAction(increaseBrightness) {
                                    updateHsv(hsv.copy(value = hsv.value + .05f))
                                    true
                                },
                                CustomAccessibilityAction(decreaseBrightness) {
                                    updateHsv(hsv.copy(value = hsv.value - .05f))
                                    true
                                }
                            )
                        }
                        .pointerInput(hsv.hue) {
                            fun setFrom(position: Offset) {
                                updateHsv(hsv.copy(saturation = (position.x / size.width).coerceIn(0f, 1f), value = (1f - position.y / size.height).coerceIn(0f, 1f)))
                            }
                            detectTapGestures { setFrom(it) }
                        }
                        .pointerInput(hsv.hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                updateHsv(hsv.copy(saturation = (change.position.x / size.width).coerceIn(0f, 1f), value = (1f - change.position.y / size.height).coerceIn(0f, 1f)))
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxWidth().height(124.dp)) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToOpaqueArgb(hsv.hue, 1f, 1f)))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val point = Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height)
                        drawCircle(Color.White, 9.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                        drawCircle(Color.Black, 7.dp.toPx(), point, style = Stroke(1.dp.toPx()))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hueDescription = stringResource(R.string.settings_hue)
                    val hueState = stringResource(R.string.settings_hue_state, hsv.hue.roundToInt())
                    Box(modifier = Modifier.weight(1f).height(48.dp)) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(8.dp).align(Alignment.Center)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Brush.horizontalGradient((0..360 step 30).map { Color(hsvToOpaqueArgb(it.toFloat(), 1f, 1f)) })),
                        )
                        val interactionSource = remember { MutableInteractionSource() }
                        val sliderColors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                        )
                        Slider(
                            value = hsv.hue,
                            onValueChange = { updateHsv(hsv.copy(hue = it)) },
                            valueRange = 0f..360f,
                            modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                                contentDescription = hueDescription
                                stateDescription = hueState
                            },
                            colors = sliderColors,
                            interactionSource = interactionSource,
                            thumb = {
                                SliderDefaults.Thumb(
                                    interactionSource = interactionSource,
                                    colors = sliderColors,
                                    thumbSize = DpSize(width = 4.dp, height = 24.dp),
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onColorSelected(parseHexColor(hex)!!) },
                enabled = validHex,
            ) {
                Text(stringResource(R.string.settings_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
