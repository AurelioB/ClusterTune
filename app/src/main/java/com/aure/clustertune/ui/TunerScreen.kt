package com.aure.clustertune.ui

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.aure.clustertune.R
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.InstalledAppInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.ui.designsystem.component.CtConfirmationDialog
import com.aure.clustertune.ui.designsystem.component.CtDashedCard
import com.aure.clustertune.ui.designsystem.component.CtDivider
import com.aure.clustertune.ui.designsystem.component.CtIcon
import com.aure.clustertune.ui.designsystem.component.CtCompactOverlayFrame
import com.aure.clustertune.ui.designsystem.component.CtSectionCard
import com.aure.clustertune.ui.designsystem.component.CtSelectableRow
import com.aure.clustertune.ui.designsystem.component.CtSelectionIndicator
import com.aure.clustertune.ui.designsystem.component.CtStatePanel
import com.aure.clustertune.ui.designsystem.component.CtStatePanelState
import com.aure.clustertune.ui.designsystem.component.CtSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val NEW_PROFILE_DIALOG_ID = "__new_profile__"
private enum class MainTab {
    PROFILES,
    APPS,
    HISTORY,
}

@Composable
fun MainTunerScreen(
    state: TunerState,
    applyingProfileId: String? = null,
    displayFrequenciesAsPercent: Boolean,
    sleepProfileId: String?,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onApplyCurrent: (TunerState) -> Unit,
    onCreateProfile: (String, TunerState) -> Unit,
    onUpdateProfile: (String, String, TunerState) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onMoveProfile: (String, Int) -> Unit,
    launchableApps: List<InstalledAppInfo>,
    recentActiveApps: List<InstalledAppInfo>,
    onSaveAppProfileAssignment: (String, String, String?, Map<Int, Int>, Int?) -> Unit,
    onDeleteAppProfileAssignment: (String) -> Unit,
    onRefreshInstalledApps: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSupport: () -> Unit,
    onRefreshLiveValues: () -> Unit,
    onStatusMessageShown: () -> Unit,
    onErrorMessageShown: () -> Unit,
) {
    var dialogProfileId by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(MainTab.PROFILES) }
    var appToConfigure by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var showAppAssignmentDialog by remember { mutableStateOf(false) }

    ScreenNotifications(
        state = state,
        onStatusMessageShown = onStatusMessageShown,
        onErrorMessageShown = onErrorMessageShown,
    )

    LaunchedEffect(Unit) {
        onRefreshLiveValues()
        while (true) {
            delay(1_000)
            onRefreshLiveValues()
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == MainTab.APPS) {
            onRefreshInstalledApps()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenContainer(compactMode = false) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                MainSideMenu(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    onOpenSettings = onOpenSettings,
                    onOpenSupport = onOpenSupport,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(188.dp)
                        .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = 28.dp, bottom = 20.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp, end = 20.dp, top = 28.dp, bottom = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (state.isLoading) {
                        LoadingClustersCard()
                    } else if (!state.isPrivilegedHostAvailable) {
                        Text(
                            text = "No compatible privileged execution method found",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            when (selectedTab) {
                                MainTab.PROFILES -> ProfileListSection(
                                    state = state,
                                    displayFrequenciesAsPercent = displayFrequenciesAsPercent,
                                    sleepProfileId = sleepProfileId,
                                    onOpenCreateProfile = { dialogProfileId = NEW_PROFILE_DIALOG_ID },
                                    onEditProfile = { dialogProfileId = it },
                                    onMoveProfile = onMoveProfile,
                                    onActivateProfile = onApplyProfile,
                                    applyingProfileId = applyingProfileId,
                                    onEditManual = { dialogProfileId = ProfileStateResolver.MANUAL_PROFILE_ID },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                )

                                MainTab.APPS -> AppProfilesSection(
                                    state = state,
                                    apps = launchableApps,
                                    recentApps = recentActiveApps,
                                    onConfigureApp = { app ->
                                        appToConfigure = app
                                        showAppAssignmentDialog = true
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )

                                MainTab.HISTORY -> ProfileSwitchHistorySection(
                                    entries = state.profileSwitchHistory,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAppAssignmentDialog) {
        appToConfigure?.let { app ->
            val assignment = state.appProfileAssignments.firstOrNull { it.packageName == app.packageName }
            var appOverlayMode by remember(app.packageName) { mutableStateOf(CompactOverlayMode.PROFILES) }
            CtCompactOverlayFrame(onDismissRequest = { showAppAssignmentDialog = false }) {
                CompactOverlayScreen(
                    state = state,
                    displayFrequenciesAsPercent = displayFrequenciesAsPercent,
                    mode = appOverlayMode,
                    onModeChange = { appOverlayMode = it },
                    onApplyProfile = { profile, _ ->
                        // Named profiles carry their CPU and GPU values in profile storage.
                        // Keep the assignment declarative so later profile edits are picked up.
                        onSaveAppProfileAssignment(app.packageName, app.label, profile.id, emptyMap(), null)
                        showAppAssignmentDialog = false
                    },
                    onApplyCurrent = { customState, profile, customValues, _ ->
                        if (profile != null) {
                            onSaveAppProfileAssignment(app.packageName, app.label, profile.id, emptyMap(), null)
                        } else if (customValues == null && customState.currentGpuMaxFrequencyHz == null) {
                            onDeleteAppProfileAssignment(app.packageName)
                        } else {
                            onSaveAppProfileAssignment(
                                app.packageName,
                                app.label,
                                null,
                                customValues ?: emptyMap(),
                                customState.currentGpuMaxFrequencyHz,
                            )
                        }
                        showAppAssignmentDialog = false
                    },
                    onDismissRequest = { showAppAssignmentDialog = false },
                    onRefreshLiveValues = onRefreshLiveValues,
                    contextPackageName = app.packageName,
                    contextLabel = app.label,
                    contextIcon = app.icon,
                    onAppProfileAssignmentChange = { profile, customValues, customGpu ->
                        if (profile == null && customValues == null && customGpu == null) onDeleteAppProfileAssignment(app.packageName)
                        else if (profile != null) onSaveAppProfileAssignment(app.packageName, app.label, profile.id, emptyMap(), null)
                        else onSaveAppProfileAssignment(app.packageName, app.label, null, customValues ?: emptyMap(), customGpu)
                    },
                    showAppProfileToggle = false,
                    showAssignmentRemove = assignment != null,
                    onRemoveAssignment = {
                        onDeleteAppProfileAssignment(app.packageName)
                        showAppAssignmentDialog = false
                    },
                )
            }
        }
    }

    dialogProfileId?.let { profileId ->
        val manualProfile = remember(state.actualValues, state.policies, state.actualGpuMaxFrequencyHz) {
            if (state.policies.isEmpty()) {
                null
            } else {
                PerformanceProfile(
                    id = ProfileStateResolver.MANUAL_PROFILE_ID,
                    name = "Manual",
                    maxFrequencies = state.policies.associate { policy ->
                        policy.id to (state.actualValues[policy.id] ?: policy.currentMaxFreq)
                    },
                    gpuMaxFrequencyHz = state.actualGpuMaxFrequencyHz,
                    source = ProfileSource.VIRTUAL,
                    isEditable = true,
                    isDeletable = false,
                )
            }
        }
        val profile = when (profileId) {
            ProfileStateResolver.MANUAL_PROFILE_ID -> manualProfile
            else -> state.displayProfiles.firstOrNull { it.id == profileId }
        }
        ProfileEditorDialog(
            baseState = state,
            displayFrequenciesAsPercent = displayFrequenciesAsPercent,
            profile = profile,
            creatingNewProfile = profileId == NEW_PROFILE_DIALOG_ID,
            manualMode = profileId == ProfileStateResolver.MANUAL_PROFILE_ID,
            onDismiss = { dialogProfileId = null },
            onSave = { name, values, gpuValue ->
                val editedState = state.copy(currentValues = values, currentGpuMaxFrequencyHz = gpuValue)
                when {
                    profileId == NEW_PROFILE_DIALOG_ID -> onCreateProfile(name, editedState)
                    profileId == ProfileStateResolver.MANUAL_PROFILE_ID -> onApplyCurrent(editedState)
                    profile != null -> onUpdateProfile(profile.id, name, editedState)
                }
                dialogProfileId = null
            },
            onDelete = {
                profile?.let { onDeleteProfile(it.id) }
                dialogProfileId = null
            },
        )
    }

}

enum class CompactOverlayMode { PROFILES, TUNER }

/** Compact app-aware overlay shared by the edge picker and quick tuner. */
@Composable
fun CompactOverlayScreen(
    state: TunerState,
    applyingProfileId: String? = null,
    displayFrequenciesAsPercent: Boolean,
    mode: CompactOverlayMode,
    onModeChange: (CompactOverlayMode) -> Unit,
    onApplyProfile: (PerformanceProfile, Boolean) -> Unit,
    onApplyCurrent: (TunerState, PerformanceProfile?, Map<Int, Int>?, Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onRefreshLiveValues: () -> Unit,
    contextPackageName: String? = null,
    contextLabel: String? = null,
    contextIcon: Drawable? = null,
    onAppProfileAssignmentChange: ((PerformanceProfile?, Map<Int, Int>?, Int?) -> Unit)? = null,
    showAppProfileToggle: Boolean = true,
    showAssignmentRemove: Boolean = false,
    onRemoveAssignment: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val profiles = profilesForCompactPicker(state.displayProfiles)
    val assignment = contextPackageName?.let { packageName ->
        state.appProfileAssignments.firstOrNull { it.packageName == packageName }
    }
    val canAssign = !contextPackageName.isNullOrBlank() && onAppProfileAssignmentChange != null
    var appProfileEnabled by remember(contextPackageName, assignment?.profileId, assignment?.customMaxFrequencies) {
        mutableStateOf(assignment != null)
    }
    var stagedProfile by remember(assignment?.profileId, state.selectedDisplayProfileId) {
        mutableStateOf(
            if (assignment?.isCustom == true || (assignment == null && state.isManualSelection)) {
                null
            } else {
                profiles.firstOrNull { it.id == assignment?.profileId }
                    ?: listOfNotNull(state.selectedDisplayProfileId, state.activeDisplayProfileId, state.lastAppliedDisplayProfileId)
                        .firstNotNullOfOrNull { id -> profiles.firstOrNull { it.id == id } }
            },
        )
    }
    val initialValues = remember(assignment?.profileId, assignment?.customMaxFrequencies, state.displayProfiles) {
        assignment?.customMaxFrequencies?.takeIf { it.isNotEmpty() }
            ?: profiles.firstOrNull { it.id == assignment?.profileId }?.maxFrequencies
            ?: state.currentValues
    }
    var stagedCustomValues by remember(initialValues) { mutableStateOf(initialValues) }
    val initialGpuValue = remember(assignment?.profileId, assignment?.customGpuMaxFrequencyHz, state.currentGpuMaxFrequencyHz) {
        assignment?.customGpuMaxFrequencyHz
            ?: profiles.firstOrNull { it.id == assignment?.profileId }?.gpuMaxFrequencyHz
            ?: state.currentGpuMaxFrequencyHz
    }
    var stagedGpuValue by remember(initialGpuValue) { mutableStateOf(initialGpuValue) }
    var customDraft by remember(assignment?.profileId, assignment?.customMaxFrequencies) {
        mutableStateOf(assignment?.isCustom == true || (assignment == null && state.isManualSelection))
    }
    // Keep the preset selection derived from the complete staged values. This also
    // handles opening the overlay with values that already match a named profile.
    LaunchedEffect(stagedCustomValues, stagedGpuValue, state.policies, state.gpuPolicy, profiles) {
        val matchingProfile = stagedProfile?.takeIf { profile ->
            profileMatchesStagedValues(stagedCustomValues, profile, state, stagedGpuValue)
        } ?: listOfNotNull(
            assignment?.profileId,
            state.selectedDisplayProfileId,
            state.activeDisplayProfileId,
            state.lastAppliedDisplayProfileId,
        ).asSequence().mapNotNull { id -> profiles.firstOrNull { it.id == id } }
            .firstOrNull { profile ->
                profileMatchesStagedValues(stagedCustomValues, profile, state, stagedGpuValue)
            }
        ?: profiles.firstOrNull { profile ->
            profileMatchesStagedValues(stagedCustomValues, profile, state, stagedGpuValue)
        }
        if (matchingProfile != null) {
            if (stagedProfile?.id != matchingProfile.id || customDraft) {
                stagedProfile = matchingProfile
                customDraft = false
            }
        } else if (stagedProfile != null || !customDraft) {
            stagedProfile = null
            customDraft = true
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            onRefreshLiveValues()
        }
    }
    val selectedProfileId = if (customDraft) null else stagedProfile?.id
        ?: listOfNotNull(assignment?.profileId, state.activeDisplayProfileId, state.lastAppliedDisplayProfileId)
            .firstOrNull { id -> profiles.any { it.id == id } }

    ScreenContainer(compactMode = true, showCompactScrim = false, compactFillHeight = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.92f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(colorScheme.surfaceContainer)
                    .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (contextPackageName != null || contextLabel != null || contextIcon != null) {
                        AppIcon(icon = contextIcon, contentDescription = contextLabel, modifier = Modifier.size(40.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f).height(40.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = contextLabel ?: contextPackageName ?: "Pick a profile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!contextLabel.isNullOrBlank() && !contextPackageName.isNullOrBlank() && contextLabel != contextPackageName) {
                            Text(
                                text = contextPackageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canAssign && showAppProfileToggle) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("App profile", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurface)
                                CtSwitch(
                                    checked = appProfileEnabled,
                                    onCheckedChange = { enabled ->
                                        appProfileEnabled = enabled
                                        if (!enabled) {
                                            onAppProfileAssignmentChange?.invoke(null, null, null)
                                        }
                                    },
                                    modifier = Modifier.scale(0.78f),
                                )
                            }
                        }
                        if (showAssignmentRemove && onRemoveAssignment != null) {
                            TextButton(onClick = onRemoveAssignment) { Text("Remove") }
                        }
                        Row(
                            modifier = Modifier.background(colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            listOf(CompactOverlayMode.PROFILES to "list", CompactOverlayMode.TUNER to "tune").forEach { (item, icon) ->
                                val selected = mode == item
                                Box(
                                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) colorScheme.primaryContainer else Color.Transparent)
                                        .clickable { onModeChange(item) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CtIcon(icon, if (item == CompactOverlayMode.PROFILES) "Profiles" else "Tuner", tint = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant, size = 18.dp)
                                }
                            }
                        }
                        IconButton(onClick = onDismissRequest, modifier = Modifier.size(30.dp)) {
                            CtIcon("close", "Close", tint = colorScheme.onSurfaceVariant, size = 21.dp)
                        }
                    }
                }
            }
            CtDivider(Modifier.fillMaxWidth(), colorScheme.outlineVariant.copy(alpha = 0.48f))
            if (mode == CompactOverlayMode.PROFILES) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 0.dp, max = 340.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (customDraft) {
                        ProfileChoiceRow(
                            title = "Custom",
                            selected = true,
                            applying = false,
                            onClick = { onModeChange(CompactOverlayMode.TUNER) },
                        )
                    }
                    profiles.forEach { profile ->
                        ProfileChoiceRow(
                            title = profile.name,
                            selected = selectedProfileId == profile.id,
                            applying = applyingProfileId == profile.id,
                            onClick = {
                                stagedProfile = profile
                                stagedCustomValues = profile.maxFrequencies
                                stagedGpuValue = profile.gpuMaxFrequencyHz ?: state.currentGpuMaxFrequencyHz
                                customDraft = false
                                onApplyProfile(profile, appProfileEnabled)
                            },
                        )
                    }
                    if (profiles.isEmpty()) ProfilePickerEmptyOptionCard()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 0.dp, max = 390.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProfileChipSelector(
                        state = state.copy(
                            currentValues = stagedCustomValues,
                            currentGpuMaxFrequencyHz = stagedGpuValue,
                            activeDisplayProfileId = null,
                            lastAppliedDisplayProfileId = null,
                            selectedDisplayProfileId = stagedProfile?.id,
                            isManualSelection = customDraft,
                        ),
                        onApplyProfile = { profile ->
                            stagedProfile = profile
                            customDraft = false
                            stagedCustomValues = profile.maxFrequencies
                            stagedGpuValue = profile.gpuMaxFrequencyHz ?: state.currentGpuMaxFrequencyHz
                            if (mode == CompactOverlayMode.PROFILES) onApplyProfile(profile, appProfileEnabled)
                        },
                        onClearSelection = {
                            stagedProfile = null
                            customDraft = true
                        },
                        onOpenFullApp = null,
                        stripUnderclockSuffix = true,
                        applyingProfileId = applyingProfileId,
                        compact = true,
                    )
                    PolicyEditorSection(
                        state = state.copy(currentValues = stagedCustomValues, currentGpuMaxFrequencyHz = stagedGpuValue),
                        displayFrequenciesAsPercent = displayFrequenciesAsPercent,
                        onPolicyValueChange = { policy, value ->
                            stagedCustomValues = stagedCustomValues + (policy.id to value)
                        },
                        onGpuValueChange = { stagedGpuValue = it },
                        compactMode = true,
                    )
                }
            }
            if (mode == CompactOverlayMode.TUNER) {
                CtDivider(Modifier.fillMaxWidth(), colorScheme.outlineVariant.copy(alpha = 0.48f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismissRequest) { Text("Cancel") }
                    Button(
                        onClick = {
                            onApplyCurrent(
                                state.copy(currentValues = stagedCustomValues, currentGpuMaxFrequencyHz = stagedGpuValue),
                                stagedProfile.takeUnless { customDraft },
                                stagedCustomValues.takeIf { customDraft },
                                appProfileEnabled,
                            )
                        },
                        enabled = state.policies.isNotEmpty() && state.isPrivilegedHostAvailable,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    ) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun ProfilePickerEmptyOptionCard() {
    CtDashedCard(
        modifier = Modifier.height(54.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No profiles available",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
        }
    }
}

/** Matches staged values, treating a legacy profile's missing GPU as unchanged. */
internal fun profileMatchesStagedValues(
    values: Map<Int, Int>,
    profile: PerformanceProfile,
    state: TunerState,
    gpuValue: Int?,
): Boolean = ProfileStateResolver.matchesProfile(
    values,
    profile,
    state.policies,
    state.gpuPolicy,
    gpuValue,
)

@Composable
private fun LoadingClustersCard() {
    CtStatePanel(
        state = CtStatePanelState.Loading,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        title = {
            Text(
                text = "Scanning CPU clusters...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        leading = {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
            )
        },
    )
}

@Composable
private fun ScreenNotifications(
    state: TunerState,
    onStatusMessageShown: () -> Unit,
    onErrorMessageShown: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            SingleToast.show(context, it, Toast.LENGTH_SHORT)
            onStatusMessageShown()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            SingleToast.show(context, it, Toast.LENGTH_LONG)
            onErrorMessageShown()
        }
    }
}

@Composable
private fun ScreenContainer(
    compactMode: Boolean,
    showCompactScrim: Boolean = true,
    compactFillHeight: Boolean = true,
    compactWidthFraction: Float = 1f,
    compactMaxWidth: Dp? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val backgroundModifier = if (compactMode) {
        val modifier = Modifier.fillMaxSize()
        if (showCompactScrim) {
            modifier.background(colorScheme.scrim.copy(alpha = 0.45f))
        } else {
            modifier
        }
    } else {
        Modifier.fillMaxSize().background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    colorScheme.primaryContainer.copy(alpha = 0.9f),
                    colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    colorScheme.surface,
                ),
            ),
        )
    }

    if (compactMode && !showCompactScrim) {
        // Overlay hosts provide their own scrim and panel surface. Rendering the
        // content directly here avoids nesting a second full-screen Card inside
        // that panel, which would otherwise paint an opaque layer over the host.
        content()
    } else Box(modifier = backgroundModifier) {
        val containerModifier = if (compactMode) {
            var modifier = Modifier.align(Alignment.Center)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
            compactMaxWidth?.let { maxWidth ->
                modifier = modifier.widthIn(max = maxWidth)
            }
            modifier = modifier.fillMaxWidth(compactWidthFraction)
            if (compactFillHeight) {
                modifier = modifier.fillMaxHeight()
            }
            modifier
        } else {
            Modifier.fillMaxSize()
        }

        Card(
            modifier = containerModifier,
            shape = if (compactMode) RoundedCornerShape(20.dp) else RectangleShape,
            colors = CardDefaults.cardColors(
                containerColor = if (compactMode) colorScheme.surfaceColorAtElevation(4.dp) else Color.Transparent,
            ),
        ) {
            content()
        }
    }
}

@Composable
private fun MainSideMenu(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_clustertune_foreground),
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
            Text(
                text = "ClusterTune",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }

        SideMenuItem(
            label = "Profiles",
            symbol = "tune",
            selected = selectedTab == MainTab.PROFILES,
            onClick = { onSelectTab(MainTab.PROFILES) },
        )
        SideMenuItem(
            label = "Apps",
            symbol = "apps",
            selected = selectedTab == MainTab.APPS,
            onClick = { onSelectTab(MainTab.APPS) },
        )
        SideMenuItem(
            label = "History",
            symbol = "history",
            selected = selectedTab == MainTab.HISTORY,
            onClick = { onSelectTab(MainTab.HISTORY) },
        )
        SideMenuItem(
            label = "Settings",
            symbol = "settings",
            selected = false,
            onClick = onOpenSettings,
        )

        Spacer(Modifier.weight(1f))

        SideMenuItem(
            label = "Support",
            symbol = "favorite",
            selected = false,
            onClick = onOpenSupport,
        )
    }
}

@Composable
private fun SideMenuItem(
    label: String,
    symbol: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (selected) {
        colorScheme.primaryContainer.copy(alpha = 0.34f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant

    val itemShape = RoundedCornerShape(16.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(itemShape)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            ),
        shape = itemShape,
        color = containerColor,
        contentColor = contentColor,
        border = if (selected) {
            BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.16f))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CtIcon(
                symbol = symbol,
                contentDescription = null,
                tint = contentColor,
                size = 26.dp,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp),
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun Header(
    state: TunerState,
    compactMode: Boolean,
    selectedTab: MainTab? = null,
    onSelectTab: ((MainTab) -> Unit)? = null,
    onOpenSettings: (() -> Unit)?,
) {
    if (compactMode && state.statusMessage == null && state.errorMessage == null) return

    Column(verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 8.dp)) {
        if (!compactMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ClusterTune",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (selectedTab != null && onSelectTab != null) {
                    MainTabSelector(
                        selectedTab = selectedTab,
                        onSelect = onSelectTab,
                        modifier = Modifier,
                    )
                }
                Spacer(Modifier.weight(1f))
                state.privilegedExecutionMethodId?.let { methodId ->
                    AssistChip(
                        onClick = {},
                        label = { Text("Execution: ${executionMethodLabel(methodId)}") },
                        enabled = false,
                    )
                }
                onOpenSettings?.let { openSettings ->
                    IconButton(onClick = openSettings) {
                        CtIcon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        state.statusMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CurrentFrequenciesCard(
    state: TunerState,
    displayFrequenciesAsPercent: Boolean,
    gpuPolicy: com.aure.clustertune.model.GpuPolicyInfo?,
    gpuValue: Int?,
    onEditManual: () -> Unit = {},
) {
    if (state.policies.isEmpty()) {
        Text(
            text = "No CPU clusters found.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.36f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Now",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
            InlineFrequencyMetadata(
                values = state.policies.associate { policy ->
                    policy.id to (state.actualValues[policy.id] ?: policy.currentMaxFreq)
                },
                policies = state.policies,
                displayAsPercent = displayFrequenciesAsPercent,
                gpuPolicy = gpuPolicy,
                gpuValue = gpuValue,
                formatTargets = false,
                modifier = Modifier.weight(1f),
            )
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                TextButton(
                    onClick = onEditManual,
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "Override",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainTabSelector(
    selectedTab: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AssistChip(
            onClick = { onSelect(MainTab.PROFILES) },
            label = { Text("Profiles") },
            leadingIcon = { CtIcon(Icons.Outlined.Memory, contentDescription = null) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (selectedTab == MainTab.PROFILES) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        )
        AssistChip(
            onClick = { onSelect(MainTab.APPS) },
            label = { Text("Apps") },
            leadingIcon = { CtIcon(Icons.Outlined.Apps, contentDescription = null) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (selectedTab == MainTab.APPS) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        )
        AssistChip(
            onClick = { onSelect(MainTab.HISTORY) },
            label = { Text("History") },
            leadingIcon = { CtIcon(Icons.Outlined.History, contentDescription = null) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (selectedTab == MainTab.HISTORY) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        )
    }
}

private data class AppListSection(
    val key: String,
    val title: String,
    val railLabel: String,
    val bubbleLabel: String = title,
)

private data class RailMarker(
    val sectionIndex: Int,
    val label: String,
    val isDot: Boolean,
    val isRecents: Boolean = false,
)

private sealed interface AppListItem {
    val key: String

    data class Header(val section: AppListSection) : AppListItem {
        override val key: String = "header-${section.key}"
    }

    data class App(val sectionKey: String, val app: InstalledAppInfo) : AppListItem {
        override val key: String = "app-$sectionKey-${app.packageName}"
    }
}

@Composable
private fun AppProfilesSection(
    state: TunerState,
    apps: List<InstalledAppInfo>,
    recentApps: List<InstalledAppInfo>,
    onConfigureApp: (InstalledAppInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var activeRailBubbleLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeRailBubbleLabel) {
        if (activeRailBubbleLabel != null) {
            delay(900)
            activeRailBubbleLabel = null
        }
    }
    val sortedApps = remember(apps) { apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }) }
    val appsByPackage = remember(sortedApps) { sortedApps.associateBy { it.packageName } }
    val recentKnownApps = remember(recentApps, appsByPackage) {
        recentApps
            .map { app -> appsByPackage[app.packageName] ?: app }
            .distinctBy { it.packageName }
            .take(5)
    }
    val assignmentsByPackage = remember(state.appProfileAssignments) {
        state.appProfileAssignments.associateBy { it.packageName }
    }
    val profilesById = remember(state.displayProfiles) { state.displayProfiles.associateBy { it.id } }
    val appGroups = remember(sortedApps) {
        sortedApps.groupBy { appListLetter(it.label) }
    }
    val railSections = remember(appGroups, recentKnownApps) {
        buildList {
            if (recentKnownApps.isNotEmpty()) {
                add(AppListSection(key = RECENTS_SECTION_KEY, title = "Recents", railLabel = "", bubbleLabel = "Recent"))
            }
            ('A'..'Z').forEach { letter ->
                add(AppListSection(key = letter.toString(), title = letter.toString(), railLabel = letter.toString()))
            }
            if (appGroups.containsKey(NON_LETTER_SECTION)) {
                add(AppListSection(key = NON_LETTER_SECTION.toString(), title = NON_LETTER_SECTION.toString(), railLabel = NON_LETTER_SECTION.toString()))
            }
        }
    }
    val listItems = remember(appGroups, recentKnownApps) {
        buildList {
            if (recentKnownApps.isNotEmpty()) {
                val recentSection = AppListSection(
                    key = RECENTS_SECTION_KEY,
                    title = "Recents",
                    railLabel = "",
                    bubbleLabel = "Recent",
                )
                add(AppListItem.Header(recentSection))
                recentKnownApps.forEach { app -> add(AppListItem.App(recentSection.key, app)) }
            }
            ('A'..'Z').forEach { letter ->
                val sectionApps = appGroups[letter].orEmpty()
                if (sectionApps.isNotEmpty()) {
                    val section = AppListSection(key = letter.toString(), title = letter.toString(), railLabel = letter.toString())
                    add(AppListItem.Header(section))
                    sectionApps.forEach { app -> add(AppListItem.App(section.key, app)) }
                }
            }
            appGroups[NON_LETTER_SECTION]?.let { sectionApps ->
                val section = AppListSection(
                    key = NON_LETTER_SECTION.toString(),
                    title = NON_LETTER_SECTION.toString(),
                    railLabel = NON_LETTER_SECTION.toString(),
                )
                add(AppListItem.Header(section))
                sectionApps.forEach { app -> add(AppListItem.App(section.key, app)) }
            }
        }
    }
    val firstIndexBySection = remember(listItems) {
        listItems
            .mapIndexedNotNull { index, item ->
                (item as? AppListItem.Header)?.section?.key?.let { sectionKey -> sectionKey to index }
            }
            .toMap()
    }

    Column(modifier = modifier.fillMaxSize()) {
            if (sortedApps.isEmpty()) {
                AssignmentEmptyState(
                    title = "No apps found",
                    message = "Refresh the app list and make sure ClusterTune can query installed packages.",
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(
                                items = listItems,
                                key = { _, item -> item.key },
                            ) { _, item ->
                                when (item) {
                                    is AppListItem.Header -> AppListHeader(section = item.section)
                                    is AppListItem.App -> {
                                        val assignment = assignmentsByPackage[item.app.packageName]
                                        val profileName = assignment?.let {
                                            if (it.isCustom) "Custom" else profilesById[it.profileId]?.name ?: "Missing profile"
                                        }
                                        AppProfileAppRow(
                                            app = item.app,
                                            profileName = profileName,
                                            onClick = { onConfigureApp(item.app) },
                                        )
                                    }
                                }
                            }
                        }
                        AlphabetScrubber(
                            sections = railSections,
                            enabledSectionKeys = firstIndexBySection.keys,
                            onSectionSelected = { section ->
                                activeRailBubbleLabel = section.bubbleLabel
                                firstIndexBySection[section.key]?.let { index ->
                                    coroutineScope.launch { listState.scrollToItem(index) }
                                }
                            },
                        )
                    }
                    activeRailBubbleLabel?.let { label ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 96.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            SectionBubble(label = label)
                        }
                    }
                }
            }
        }
    }

@Composable
private fun AppListHeader(section: AppListSection) {
    Text(
        text = section.title,
        modifier = Modifier.padding(start = 4.dp, top = if (section.key == RECENTS_SECTION_KEY) 0.dp else 10.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun AppProfileAppRow(
    app: InstalledAppInfo,
    profileName: String?,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = rowShape,
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                icon = app.icon,
                contentDescription = app.label,
                modifier = Modifier.size(44.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                    maxLines = 1,
                )
            }
            Text(
                text = profileName ?: "None",
                style = MaterialTheme.typography.labelLarge,
                color = if (profileName == null) {
                    colorScheme.onSurfaceVariant
                } else {
                    colorScheme.primary
                },
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 72.dp, max = 150.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AlphabetScrubber(
    sections: List<AppListSection>,
    enabledSectionKeys: Set<String>,
    onSectionSelected: (AppListSection) -> Unit,
) {
    if (sections.isEmpty()) return

    fun sectionAt(y: Float, height: Int): AppListSection {
        if (height <= 0) return sections.first()
        val sectionIndex = ((y.coerceIn(0f, height.toFloat()) / height.toFloat()) * sections.size)
            .toInt()
            .coerceIn(0, sections.lastIndex)
        return sections[sectionIndex]
    }

    fun selectNearestEnabled(section: AppListSection): AppListSection? {
        val index = sections.indexOf(section).takeIf { it >= 0 } ?: return null
        return if (section.key in enabledSectionKeys) {
            section
        } else {
            sections.drop(index + 1).firstOrNull { it.key in enabledSectionKeys }
                ?: sections.take(index).asReversed().firstOrNull { it.key in enabledSectionKeys }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .width(34.dp)
            .fillMaxHeight()
            .pointerInput(sections, enabledSectionKeys) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val initialSection = sectionAt(down.position.y, size.height)
                    selectNearestEnabled(initialSection)?.let(onSectionSelected)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            break
                        }
                        val section = sectionAt(change.position.y, size.height)
                        selectNearestEnabled(section)?.let(onSectionSelected)
                        change.consume()
                    }
                }
            },
    ) {
        val markerHeight = 22.dp
        val maxMarkers = (maxHeight.value / markerHeight.value).toInt().coerceAtLeast(1)
        val markers = remember(sections, maxMarkers) {
            railMarkers(sections = sections, maxMarkers = maxMarkers)
        }
        val railHeight = maxHeight

        markers.forEachIndexed { markerIndex, marker ->
            val section = sections[marker.sectionIndex]
            val enabled = section.key in enabledSectionKeys || marker.isDot
            val yFraction = if (markers.size == 1) {
                0.5f
            } else {
                markerIndex.toFloat() / markers.lastIndex.toFloat()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (railHeight - markerHeight) * yFraction)
                    .height(markerHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val markerColor = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                }
                if (marker.isRecents) {
                    CtIcon(
                        symbol = "history",
                        contentDescription = "Recents",
                        tint = markerColor,
                        size = 16.dp,
                    )
                } else {
                    Text(
                        text = marker.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = if (marker.isDot) 18.sp else 13.sp,
                        color = markerColor,
                        fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun railMarkers(
    sections: List<AppListSection>,
    maxMarkers: Int,
): List<RailMarker> {
    fun markerFor(index: Int): RailMarker {
        val section = sections[index]
        return RailMarker(
            sectionIndex = index,
            label = section.railLabel,
            isDot = false,
            isRecents = section.key == RECENTS_SECTION_KEY,
        )
    }

    if (sections.size <= maxMarkers) {
        return sections.mapIndexed { index, _ -> markerFor(index) }
    }

    val recentsIndex = sections.indexOfFirst { it.key == RECENTS_SECTION_KEY }.takeIf { it >= 0 }
    val nonLetterIndex = sections.indexOfFirst { it.key == NON_LETTER_SECTION.toString() }.takeIf { it >= 0 }
    val alphabetIndexes = ('A'..'Z')
        .mapNotNull { letter -> sections.indexOfFirst { it.key == letter.toString() }.takeIf { index -> index >= 0 } }

    if (alphabetIndexes.size <= 2) {
        return sections.mapIndexed { index, _ -> markerFor(index) }.take(maxMarkers)
    }

    val fixedMarkerCount = listOfNotNull(recentsIndex, nonLetterIndex).size
    val availableAlphabetMarkers = (maxMarkers - fixedMarkerCount).coerceAtLeast(2)
    val visibleLetterCount = ((availableAlphabetMarkers + 1) / 2)
        .coerceIn(2, alphabetIndexes.size)
    val sortedLabels = (0 until visibleLetterCount)
        .map { markerIndex ->
            val alphabetPosition = ((markerIndex * (alphabetIndexes.lastIndex)).toFloat() /
                (visibleLetterCount - 1).toFloat()).roundToInt()
            alphabetIndexes[alphabetPosition.coerceIn(0, alphabetIndexes.lastIndex)]
        }
        .toMutableSet()
        .apply {
            add(alphabetIndexes.first())
            add(alphabetIndexes.last())
        }
        .sorted()

    return buildList {
        recentsIndex?.let { add(markerFor(it)) }

        sortedLabels.forEachIndexed { index, sectionIndex ->
            add(markerFor(sectionIndex))

            val nextSectionIndex = sortedLabels.getOrNull(index + 1) ?: return@forEachIndexed
            if (nextSectionIndex - sectionIndex > 1 && size < maxMarkers) {
                add(
                    RailMarker(
                        sectionIndex = (sectionIndex + nextSectionIndex) / 2,
                        label = "•",
                        isDot = true,
                    ),
                )
            }
        }
        nonLetterIndex?.let { index ->
            if (size < maxMarkers) add(markerFor(index))
        }
    }.sortedBy { it.sectionIndex }
}

@Composable
private fun SectionBubble(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(72.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CenteredModalSurface(
    maxWidth: Dp,
    onDismiss: () -> Unit,
    widthFraction: Float = 0.86f,
    heightFraction: Float = 0.86f,
    content: @Composable () -> Unit,
) {
    val outsideInteractionSource = remember { MutableInteractionSource() }
    val surfaceInteractionSource = remember { MutableInteractionSource() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                interactionSource = outsideInteractionSource,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(maxHeight * heightFraction)
                .widthIn(max = maxWidth)
                .clickable(
                    interactionSource = surfaceInteractionSource,
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
        ) {
            content()
        }
    }
}

@Composable
private fun ProfileChoiceRow(
    title: String,
    selected: Boolean,
    applying: Boolean = false,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)
    val containerColor = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f)
    val containerBrush = if (selected) {
        Brush.horizontalGradient(
            listOf(
                colorScheme.primaryContainer.copy(alpha = 0.24f),
                colorScheme.surfaceContainerHigh.copy(alpha = 0.56f),
            ),
        )
    } else {
        Brush.horizontalGradient(listOf(containerColor, containerColor))
    }
    val borderColor = if (selected) {
        colorScheme.primary.copy(alpha = 0.82f)
    } else {
        colorScheme.outlineVariant.copy(alpha = 0.28f)
    }
    val titleColor = if (selected) borderColor else colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 38.dp else 48.dp)
            .background(containerBrush, rowShape)
            .border(BorderStroke(1.dp, borderColor), rowShape)
            .clip(rowShape)
            .clickable(onClick = onClick)
            .padding(
                start = if (compact) 8.dp else 12.dp,
                top = 8.dp,
                end = 12.dp,
                bottom = if (compact) 10.dp else 8.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = 1,
        )
        CtSelectionIndicator(
            selected = selected,
            applying = applying,
            size = if (compact) 22.dp else 26.dp,
            targetSize = if (compact) 22.dp else 26.dp,
            contentDescription = if (applying) "Applying $title" else if (selected) "Selected" else null,
        )
    }
}

@Composable
private fun AssignmentEmptyState(title: String, message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val RECENTS_SECTION_KEY = "__recents__"
private const val NON_LETTER_SECTION = '#'

private fun appListLetter(label: String): Char {
    val first = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (first in 'A'..'Z') first else '#'
}

@Composable
private fun AppIcon(
    icon: Drawable?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (icon == null) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CtIcon(
                    symbol = "apps",
                    contentDescription = contentDescription,
                    size = 24.dp,
                )
            }
        }
        return
    }

    val bitmap = remember(icon) {
        icon.toBitmap(width = 48, height = 48).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
private fun ProfileListSection(
    state: TunerState,
    displayFrequenciesAsPercent: Boolean,
    sleepProfileId: String?,
    onOpenCreateProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onMoveProfile: (String, Int) -> Unit,
    onActivateProfile: (PerformanceProfile) -> Unit,
    applyingProfileId: String? = null,
    onEditManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CurrentFrequenciesCard(
            state = state,
            displayFrequenciesAsPercent = displayFrequenciesAsPercent,
            gpuPolicy = state.gpuPolicy,
            gpuValue = state.actualGpuMaxFrequencyHz,
            onEditManual = onEditManual,
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val profiles = state.displayProfiles
            val rowStepPx = with(LocalDensity.current) { 94.dp.toPx() }
            var draggingProfileId by remember { mutableStateOf<String?>(null) }
            var dragStartIndex by remember { mutableStateOf(-1) }
            var dragTargetIndex by remember { mutableStateOf(-1) }
            var dragOffsetPx by remember { mutableStateOf(0f) }
            val previewProfiles = remember(profiles, draggingProfileId, dragStartIndex, dragTargetIndex) {
                val fromIndex = dragStartIndex
                val toIndex = dragTargetIndex
                if (draggingProfileId == null ||
                    fromIndex !in profiles.indices ||
                    toIndex !in profiles.indices ||
                    fromIndex == toIndex
                ) {
                    profiles
                } else {
                    profiles.toMutableList().apply {
                        val draggedProfile = removeAt(fromIndex)
                        add(toIndex, draggedProfile)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                previewProfiles.forEachIndexed { index, profile ->
                    val originalIndex = profiles.indexOfFirst { it.id == profile.id }
                    val canMove = originalIndex >= 0
                    val isDragging = draggingProfileId == profile.id
                    key(profile.id) {
                        ProfileListRow(
                            profile = profile,
                            isApplied = profile.id == state.activeDisplayProfileId,
                            isSelected = profile.id == state.selectedDisplayProfileId,
                            isSleepProfile = profile.id == sleepProfileId,
                            canMoveUp = canMove && originalIndex > 0,
                            canMoveDown = canMove && originalIndex < profiles.lastIndex,
                            showReorder = true,
                            showEdit = profile.isEditable,
                            valuePreview = profile.maxFrequencies,
                            policies = state.policies,
                            gpuPolicy = state.gpuPolicy,
                            gpuValue = profile.gpuMaxFrequencyHz,
                            displayFrequenciesAsPercent = displayFrequenciesAsPercent,
                            isDragging = isDragging,
                            dragActive = draggingProfileId != null,
                            applying = applyingProfileId == profile.id,
                            onActivate = { onActivateProfile(profile) },
                            onMoveUp = if (canMove && originalIndex > 0) {
                                { onMoveProfile(profile.id, -1) }
                            } else {
                                null
                            },
                            onMoveDown = if (canMove && originalIndex < profiles.lastIndex) {
                                { onMoveProfile(profile.id, 1) }
                            } else {
                                null
                            },
                            onEdit = {
                                if (profile.isEditable) {
                                    onEditProfile(profile.id)
                                }
                            },
                            onDragStart = {
                                draggingProfileId = profile.id
                                dragStartIndex = originalIndex
                                dragTargetIndex = originalIndex
                                dragOffsetPx = 0f
                            },
                            onDrag = { dragAmount ->
                                dragOffsetPx += dragAmount
                                val offset = (dragOffsetPx / rowStepPx).roundToInt()
                                dragTargetIndex = (dragStartIndex + offset).coerceIn(0, profiles.lastIndex)
                            },
                            onDragEnd = {
                                val offset = dragTargetIndex - dragStartIndex
                                if (offset != 0) {
                                    onMoveProfile(profile.id, offset)
                                }
                                draggingProfileId = null
                                dragStartIndex = -1
                                dragTargetIndex = -1
                                dragOffsetPx = 0f
                            },
                            onDragCancel = {
                                draggingProfileId = null
                                dragStartIndex = -1
                                dragTargetIndex = -1
                                dragOffsetPx = 0f
                            },
                        )
                    }
                }
                AddProfileSkeletonButton(onClick = onOpenCreateProfile)
            }
        }
    }
}

@Composable
private fun AddProfileSkeletonButton(onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val borderColor = colorScheme.outlineVariant.copy(alpha = 0.28f)
    val contentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(colorScheme.surfaceContainerHigh.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 2.dp.toPx()
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                style = Stroke(
                    width = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(9.dp.toPx(), 6.dp.toPx()),
                    ),
                ),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CtIcon(
                symbol = "add",
                contentDescription = null,
                tint = contentColor,
                size = 24.dp,
            )
            Text(
                text = "New profile",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun ProfileListRow(
    profile: PerformanceProfile,
    isApplied: Boolean,
    isSelected: Boolean,
    isSleepProfile: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showReorder: Boolean,
    showEdit: Boolean,
    valuePreview: Map<Int, Int>,
    policies: List<CpuPolicyInfo>,
    gpuPolicy: com.aure.clustertune.model.GpuPolicyInfo?,
    gpuValue: Int?,
    displayFrequenciesAsPercent: Boolean,
    isDragging: Boolean,
    dragActive: Boolean,
    applying: Boolean = false,
    onActivate: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onEdit: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)
    val containerColor = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f)
    val containerBrush = if (isApplied || applying) {
        Brush.horizontalGradient(
            listOf(
                colorScheme.primaryContainer.copy(alpha = 0.24f),
                colorScheme.surfaceContainerHigh.copy(alpha = 0.56f),
            ),
        )
    } else {
        Brush.horizontalGradient(listOf(containerColor, containerColor))
    }
    val contentColor = colorScheme.onSurface
    val borderColor = when {
        isDragging -> colorScheme.primary
        isApplied -> colorScheme.primary.copy(alpha = 0.82f)
        applying -> colorScheme.primary.copy(alpha = 0.62f)
        isSelected -> colorScheme.primary.copy(alpha = 0.58f)
        else -> colorScheme.outlineVariant.copy(alpha = 0.28f)
    }
    val profileNameColor = if (isApplied || applying) borderColor else contentColor
    val metadataContentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.84f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerBrush, rowShape)
            .border(
                BorderStroke(
                    if (isDragging) 2.dp else 1.dp,
                    borderColor,
                ),
                rowShape,
            )
            .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showReorder) {
            ReorderControl(
                enabled = true,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                profileName = profile.name,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
            )
        } else {
            Spacer(Modifier.width(64.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = profileNameColor,
                )
                if (isSleepProfile) {
                    CtIcon(
                        symbol = "dark_mode",
                        contentDescription = "Sleep profile",
                        tint = contentColor.copy(alpha = 0.78f),
                        size = 18.dp,
                    )
                }
            }
            if (valuePreview.isNotEmpty()) {
            InlineFrequencyMetadata(
                values = valuePreview,
                policies = policies,
                displayAsPercent = displayFrequenciesAsPercent,
                valueColor = metadataContentColor,
                gpuPolicy = gpuPolicy,
                gpuValue = gpuValue,
                forceNumeric = profile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            )
            }
        }
        if (showEdit) {
            IconButton(onClick = onEdit) {
                CtIcon(
                symbol = "tune",
                    contentDescription = "Edit ${profile.name}",
                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    size = 26.dp,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        ProfileActivationControl(
            selected = isApplied,
            applying = applying,
            profileName = profile.name,
            onClick = onActivate,
            enabled = valuePreview.isNotEmpty() && !dragActive,
        )
    }
}

@Composable
private fun ProfileActivationControl(
    selected: Boolean,
    applying: Boolean = false,
    profileName: String = "profile",
    enabled: Boolean,
    onClick: () -> Unit,
) {
    CtSelectionIndicator(
        selected = selected,
        applying = applying,
        enabled = enabled,
        onClick = onClick,
        contentDescription = if (applying) "Applying $profileName" else if (selected) "Active profile" else null,
    )
}

@Composable
private fun ReorderControl(
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    profileName: String,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val canDrag = enabled && (canMoveUp || canMoveDown)
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 48.dp)
            .semantics {
                customActions = buildList {
                    if (canMoveUp && onMoveUp != null) {
                        add(
                            CustomAccessibilityAction("Move $profileName up") {
                                onMoveUp()
                                true
                            },
                        )
                    }
                    if (canMoveDown && onMoveDown != null) {
                        add(
                            CustomAccessibilityAction("Move $profileName down") {
                                onMoveDown()
                                true
                            },
                        )
                    }
                }
            }
            .pointerInput(canDrag, canMoveUp, canMoveDown) {
                if (!canDrag) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        CtIcon(
            symbol = "drag_indicator",
            contentDescription = "Drag to reorder ${if (canDrag) "profile" else "profile unavailable"}",
            tint = if (canDrag) {
                colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            } else {
                colorScheme.onSurfaceVariant.copy(alpha = 0.36f)
            },
            size = 28.dp,
        )
    }
}

@Composable
private fun InlineFrequencyMetadata(
    values: Map<Int, Int>,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
    labelColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
    policies: List<CpuPolicyInfo> = emptyList(),
    displayAsPercent: Boolean = false,
    gpuPolicy: com.aure.clustertune.model.GpuPolicyInfo? = null,
    gpuValue: Int? = null,
    formatTargets: Boolean = true,
    forceNumeric: Boolean = false,
) {
    val policiesById = policies.associateBy { it.id }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.toSortedMap().entries.forEachIndexed { index, (policyId, value) ->
            if (index > 0) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.62f),
                    maxLines = 1,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "C$policyId",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = labelColor,
                    maxLines = 1,
                )
                val policy = policiesById[policyId]
                Text(
                    text = if (forceNumeric) {
                        formatFrequency(
                            value,
                            boosted = policy?.isBoosted(value) == true,
                            policy = policy,
                            displayAsPercent = displayAsPercent,
                            showStockLabel = false,
                        )
                    } else if (!formatTargets) {
                        formatFrequency(
                            value,
                            boosted = policy?.isBoosted(value) == true,
                            policy = policy,
                            displayAsPercent = displayAsPercent,
                            showStockLabel = false,
                        )
                    } else formatTargetFrequency(value, policy, displayAsPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                    maxLines = 1,
                )
            }
        }
        if (gpuPolicy != null && gpuValue != null) {
            if (values.isNotEmpty()) Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.62f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("GPU", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = labelColor, maxLines = 1)
                Text(
                    if (forceNumeric || !formatTargets) formatGpuFrequency(gpuValue)
                    else formatGpuFrequency(gpuValue, gpuPolicy),
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ProfileChipSelector(
    state: TunerState,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onClearSelection: () -> Unit,
    onOpenFullApp: (() -> Unit)?,
    stripUnderclockSuffix: Boolean = false,
    applyingProfileId: String? = null,
    compact: Boolean = false,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedDisplayProfileId, state.isManualSelection, state.displayProfiles) {
        val selectedIndex = when {
            state.isManualSelection -> state.displayProfiles.size
            state.selectedDisplayProfileId != null -> state.displayProfiles.indexOfFirst {
                it.id == state.selectedDisplayProfileId
            }.takeIf { it >= 0 } ?: 0
            else -> 0
        }
        listState.animateScrollToItem(selectedIndex)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            itemsIndexed(
                items = state.displayProfiles,
                key = { _, profile -> profile.id },
            ) { _, profile ->
                ProfileSelectorChip(
                    label = profile.name.displayNameForTuner(stripUnderclockSuffix),
                    isApplied = profile.id == state.activeDisplayProfileId,
                    isSelected = profile.id == state.selectedDisplayProfileId,
                    applying = applyingProfileId == profile.id,
                    onClick = { onApplyProfile(profile) },
                    compact = compact,
                )
            }
            if (state.isManualSelection) {
                item(key = "custom") {
                    ProfileSelectorChip(
                        label = "Custom",
                        isApplied = false,
                        isSelected = true,
                        onClick = onClearSelection,
                        compact = compact,
                    )
                }
            }
        }
        onOpenFullApp?.let { openFullApp ->
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                IconButton(
                    onClick = openFullApp,
                    modifier = Modifier.size(40.dp),
                ) {
                    CtIcon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Open full app",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun String.displayNameForTuner(stripUnderclockSuffix: Boolean): String =
    if (stripUnderclockSuffix) removeSuffix(" Underclock") else this

@Composable
private fun ProfileSelectorChip(
    label: String,
    isApplied: Boolean,
    isSelected: Boolean,
    applying: Boolean = false,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    AssistChip(
        onClick = onClick,
        modifier = if (compact) Modifier.height(28.dp) else Modifier,
        colors = when {
            isApplied -> AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            else -> AssistChipDefaults.assistChipColors()
        },
        border = when {
            isApplied -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isSelected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else -> null
        },
        label = {
            Row(
                modifier = if (applying) Modifier.semantics { contentDescription = "Applying $label" } else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (compact) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(label)
                }
                if (applying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                }
            }
        },
    )
}

@Composable
private fun PolicyEditorSection(
    state: TunerState,
    displayFrequenciesAsPercent: Boolean,
    onPolicyValueChange: (CpuPolicyInfo, Int) -> Unit,
    onGpuValueChange: (Int) -> Unit = {},
    compactMode: Boolean,
) {
    if (state.policies.isEmpty()) {
        EmptyState(state)
        return
    }

    state.policies.forEach { policy ->
        TunerPolicyCard(
            policy = policy,
            selectedValue = state.currentValues[policy.id] ?: policy.currentMaxFreq,
            actualValue = state.actualValues[policy.id] ?: policy.currentMaxFreq,
            onValueChanged = { onPolicyValueChange(policy, it) },
            compactMode = compactMode,
            displayFrequenciesAsPercent = displayFrequenciesAsPercent,
        )
    }
    state.gpuPolicy?.let { gpuPolicy ->
        TunerGpuPolicyCard(
            policy = gpuPolicy,
            selectedValue = state.currentGpuMaxFrequencyHz ?: gpuPolicy.currentMaxFrequencyHz,
            actualValue = state.actualGpuMaxFrequencyHz ?: gpuPolicy.currentMaxFrequencyHz,
            onValueChanged = onGpuValueChange,
            compactMode = compactMode,
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    baseState: TunerState,
    displayFrequenciesAsPercent: Boolean,
    profile: PerformanceProfile?,
    creatingNewProfile: Boolean,
    manualMode: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Map<Int, Int>, Int?) -> Unit,
    onDelete: () -> Unit,
) {
    val initialValues = remember(profile?.id, creatingNewProfile, manualMode, baseState.actualValues) {
        baseState.policies.associate { policy ->
            val initialValue = when {
                creatingNewProfile || manualMode -> baseState.actualValues[policy.id]
                else -> profile?.maxFrequencies?.get(policy.id)
            } ?: policy.currentMaxFreq
            policy.id to policy.clampToWritableMax(initialValue)
        }
    }
    var profileName by remember(profile?.id, creatingNewProfile) { mutableStateOf(profile?.name.orEmpty()) }
    var editedValues by remember(profile?.id, initialValues) { mutableStateOf(initialValues) }
    var editedGpuValue by remember(profile?.id, creatingNewProfile, manualMode, baseState.actualGpuMaxFrequencyHz) {
        mutableStateOf(
                profile?.gpuMaxFrequencyHz
                ?: if (creatingNewProfile || manualMode) baseState.actualGpuMaxFrequencyHz else null,
        )
    }
    var showDeleteConfirmation by remember(profile?.id) { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    CenteredModalSurface(maxWidth = 900.dp, onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!manualMode) {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 62.dp),
                        singleLine = true,
                        label = { Text("Profile name") },
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary.copy(alpha = 0.72f),
                            unfocusedBorderColor = colorScheme.outlineVariant.copy(alpha = 0.28f),
                            focusedContainerColor = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f),
                            unfocusedContainerColor = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f),
                            cursorColor = colorScheme.primary,
                        ),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    baseState.policies.forEach { policy ->
                        TunerPolicyCard(
                            policy = policy,
                            selectedValue = editedValues[policy.id] ?: policy.currentMaxFreq,
                            actualValue = baseState.actualValues[policy.id] ?: policy.currentMaxFreq,
                            onValueChanged = { editedValue ->
                                editedValues = editedValues + (policy.id to editedValue)
                            },
                            compactMode = true,
                            displayFrequenciesAsPercent = displayFrequenciesAsPercent,
                        )
                    }
                    baseState.gpuPolicy?.let { gpuPolicy ->
                        TunerGpuPolicyCard(
                            policy = gpuPolicy,
                            selectedValue = editedGpuValue ?: gpuPolicy.currentMaxFrequencyHz,
                            actualValue = baseState.actualGpuMaxFrequencyHz ?: gpuPolicy.currentMaxFrequencyHz,
                            onValueChanged = { editedGpuValue = it },
                            compactMode = true,
                        )
                    }
                }
            }

            CtDivider(Modifier.fillMaxWidth(), colorScheme.outlineVariant.copy(alpha = 0.48f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    if (!manualMode && profile?.isDeletable == true) {
                        IconButton(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            CtIcon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete profile",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Spacer(Modifier.size(36.dp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (manualMode) {
                                    onSave(profile?.name.orEmpty(), editedValues, editedGpuValue)
                                } else {
                                    onSave(profileName, editedValues, editedGpuValue)
                                }
                            },
                            modifier = Modifier.height(30.dp),
                            enabled = baseState.policies.isNotEmpty() && (manualMode || profileName.isNotBlank()),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        ) {
                            Text(if (manualMode) "Apply custom values" else "Save")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        CtConfirmationDialog(
            title = "Delete profile?",
            message = "This profile will be removed until you reset profiles to default.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = {
                showDeleteConfirmation = false
                onDelete()
            },
            onDismissRequest = { showDeleteConfirmation = false },
            destructive = true,
        )
    }
}

@Composable
private fun EmptyState(state: TunerState) {
    SectionCard(title = if (state.isLoading) "Scanning CPU Clusters" else "No CPU Clusters Found") {
        Text(
            text = if (state.isLoading) {
                "Scanning CPU clusters..."
            } else {
                "No compatible CPU frequency clusters were found."
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String?,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier: Modifier = Modifier.fillMaxWidth(),
    contentModifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    CtSectionCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        containerColor = containerColor,
        contentPadding = contentPadding,
        contentModifier = contentModifier,
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

internal fun CpuPolicyInfo.clampToWritableMax(valueKhz: Int): Int {
    return valueKhz.coerceAtMost(selectableMaxFreq)
}

internal fun CpuPolicyInfo.isBoosted(valueKhz: Int): Boolean {
    return valueKhz > selectableMaxFreq
}

internal fun formatFrequency(
    valueKhz: Int,
    boosted: Boolean = false,
    policy: CpuPolicyInfo? = null,
    displayAsPercent: Boolean = false,
    showStockLabel: Boolean = true,
): String {
    if (showStockLabel && !boosted && policy != null && valueKhz == policy.selectableMaxFreq) return "Stock"
    val base = if (displayAsPercent && policy != null && policy.selectableMaxFreq > 0) {
        val percent = ((valueKhz.toFloat() / policy.selectableMaxFreq.toFloat()) * 100f).roundToInt()
        "$percent%"
    } else {
        when {
            valueKhz >= 1_000_000 -> String.format("%.2f GHz", valueKhz / 1_000_000f)
            valueKhz >= 1_000 -> String.format("%.0f MHz", valueKhz / 1_000f)
            else -> "$valueKhz kHz"
        }
    }
    return if (boosted) "$base+" else base
}

internal fun formatTargetFrequency(valueKhz: Int, policy: CpuPolicyInfo?, displayAsPercent: Boolean = false): String =
    if (policy != null && valueKhz >= policy.selectableMaxFreq) "Stock"
    else formatFrequency(valueKhz, policy = policy, displayAsPercent = displayAsPercent)
