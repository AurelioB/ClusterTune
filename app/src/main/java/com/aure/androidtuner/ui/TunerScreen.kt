package com.aure.androidtuner.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.unit.Dp
import com.aure.androidtuner.model.CpuPolicyInfo
import com.aure.androidtuner.model.PerformanceProfile
import com.aure.androidtuner.model.TunerState

@Composable
fun TunerScreen(
    state: TunerState,
    onPolicyValueChange: (CpuPolicyInfo, Int) -> Unit,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onClearSelection: () -> Unit,
    onApplyCurrent: (TunerState) -> Unit,
    onSavePreset: (String, TunerState) -> Unit,
    compactMode: Boolean,
    onDismissRequest: (() -> Unit)?,
) {
    var presetName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val allProfiles = remember(state.bundledProfiles, state.userProfiles) {
        state.bundledProfiles + state.userProfiles
    }
    val activePresetIds = remember(allProfiles, state.actualValues) {
        allProfiles
            .filter { profile ->
                profile.maxFrequencies.isNotEmpty() && profile.maxFrequencies.all { (policyId, value) ->
                    state.actualValues[policyId] == value
                }
            }
            .map { it.id }
            .toSet()
    }
    val selectedPresetId = state.selectedProfileId

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val backgroundModifier = if (compactMode) {
        Modifier
            .fillMaxSize()
            .background(Color(0x8A091018))
    } else {
        Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F1720), Color(0xFF182A36), Color(0xFFF2F5E8)),
                ),
            )
    }

    Box(modifier = backgroundModifier) {
        val containerModifier = if (compactMode) {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        } else {
            Modifier.fillMaxSize()
        }

        Card(
            modifier = containerModifier,
            shape = if (compactMode) RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 24.dp, bottomEnd = 24.dp) else RectangleShape,
            colors = CardDefaults.cardColors(
                containerColor = if (compactMode) MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp) else Color.Transparent,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = if (compactMode) 16.dp else 20.dp,
                        end = if (compactMode) 16.dp else 20.dp,
                        top = if (compactMode) 8.dp else 28.dp,
                        bottom = if (compactMode) 14.dp else 28.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (compactMode) 12.dp else 18.dp),
            ) {
                Header(state, compactMode)

                if (allProfiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        allProfiles.forEach { profile ->
                            val isApplied = profile.id in activePresetIds
                            val isSelected = profile.id == selectedPresetId
                            AssistChip(
                                onClick = { onApplyProfile(profile) },
                                colors = when {
                                    isApplied && isSelected -> AssistChipDefaults.assistChipColors(
                                        containerColor = Color(0xFFB9E08D),
                                        labelColor = Color(0xFF10290A),
                                    )
                                    isApplied -> AssistChipDefaults.assistChipColors(
                                        containerColor = Color(0xFFCFE9B6),
                                        labelColor = Color(0xFF17340E),
                                    )
                                    isSelected -> AssistChipDefaults.assistChipColors(
                                        containerColor = Color(0xFFE1D7F4),
                                        labelColor = Color(0xFF2F1C5C),
                                    )
                                    else -> AssistChipDefaults.assistChipColors()
                                },
                                border = when {
                                    isApplied && isSelected -> BorderStroke(2.dp, Color(0xFF2F6A1B))
                                    isApplied -> BorderStroke(2.dp, Color(0xFF3E7A22))
                                    isSelected -> BorderStroke(2.dp, Color(0xFF6A4BB4))
                                    else -> null
                                },
                                label = { Text(profile.name) },
                            )
                        }
                    }
                }

                if (state.policies.isEmpty()) {
                    EmptyState(state)
                } else {
                    state.policies.forEach { policy ->
                        PolicyCard(
                            policy = policy,
                            selectedValue = state.currentValues[policy.id] ?: policy.currentMaxFreq,
                            actualValue = state.actualValues[policy.id] ?: policy.currentMaxFreq,
                            onValueChanged = { onPolicyValueChange(policy, it) },
                            compactMode = compactMode,
                        )
                    }
                }

                if (!compactMode) {
                    SectionCard(title = "Save Current Values") {
                        OutlinedTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Preset name") },
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                onSavePreset(presetName, state)
                                presetName = ""
                            },
                            enabled = state.policies.isNotEmpty(),
                        ) {
                            Text("Save preset")
                        }
                    }
                }

                if (compactMode && onDismissRequest != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = { onApplyCurrent(state) },
                            enabled = state.policies.isNotEmpty() && state.isPServerAvailable,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) {
                            Text("Apply")
                        }
                    }
                } else {
                    Button(
                        onClick = { onApplyCurrent(state) },
                        enabled = state.policies.isNotEmpty() && state.isPServerAvailable,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: TunerState,
    compactMode: Boolean,
) {
    if (compactMode && state.statusMessage == null && state.errorMessage == null) return

    Column(verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 8.dp)) {
        if (!compactMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Handheld Performance",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF7FBF2),
                )
                Text(
                    text = if (state.isPServerAvailable) {
                        "PServer detected. Tune CPU policy limits and apply in one root transaction."
                    } else {
                        "PServer is unavailable on this device. You can inspect policies, but apply is disabled."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD7E2DA),
                )
            }
        }

        state.statusMessage?.let {
            Text(
                text = it,
                color = if (compactMode) Color(0xFF2A6B1E) else Color(0xFFC2FF72),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.errorMessage?.let {
            Text(
                text = it,
                color = if (compactMode) Color(0xFFB3261E) else Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyState(state: TunerState) {
    SectionCard(title = "No CPU Policies Found") {
        Text(
            text = if (state.isLoading) {
                "Scanning cpufreq policies..."
            } else {
                "No compatible cpufreq policy directories were found under /sys/devices/system/cpu/cpufreq."
            },
        )
    }
}

@Composable
private fun PolicyCard(
    policy: CpuPolicyInfo,
    selectedValue: Int,
    onValueChanged: (Int) -> Unit,
    compactMode: Boolean = false,
    actualValue: Int = selectedValue,
) {
    val supported = policy.supportedFrequencies
    val currentIndex = supported.indexOf(selectedValue).takeIf { it >= 0 } ?: 0

    SectionCard(title = null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Policy ${policy.id}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Sel ${formatFrequency(selectedValue)}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
            )
            Text(
                text = "Now ${formatFrequency(actualValue)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (actualValue == selectedValue) Color(0xFF2A6B1E) else Color(0xFF7A3E00),
                textAlign = TextAlign.End,
            )
        }
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides if (compactMode) Dp.Unspecified else 48.dp,
        ) {
            Slider(
                value = currentIndex.toFloat(),
                onValueChange = { raw ->
                    val index = raw.toInt().coerceIn(0, supported.lastIndex)
                    onValueChanged(supported[index])
                },
                valueRange = 0f..supported.lastIndex.toFloat(),
                steps = (supported.size - 2).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String?,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xECFBF8EE)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

internal fun formatFrequency(valueKhz: Int): String {
    return String.format("%.2f GHz", valueKhz / 1_000_000f)
}
