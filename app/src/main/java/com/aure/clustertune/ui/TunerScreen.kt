package com.aure.clustertune.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.net.Uri
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults

import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TunerState
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val NEW_PROFILE_DIALOG_ID = "__new_profile__"
private const val KOFI_URL = "https://ko-fi.com/J3J518XVKR"
private const val SUPPORT_MESSAGE =
    "ClusterTune is built and maintained independently. If it helps you tune your device, consider supporting my work on Ko-fi."

private enum class MainTab {
    PROFILES,
    APPS,
    HISTORY,
}

@Composable
fun MainTunerScreen(
    state: TunerState,
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
    onSaveAppProfileAssignment: (String, String, String) -> Unit,
    onDeleteAppProfileAssignment: (String) -> Unit,
    onRefreshInstalledApps: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshLiveValues: () -> Unit,
    onStatusMessageShown: () -> Unit,
    onErrorMessageShown: () -> Unit,
) {
    var dialogProfileId by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(MainTab.PROFILES) }
    var appToConfigure by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var showAppAssignmentDialog by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }

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
                    onOpenSupport = { showSupportDialog = true },
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
                    } else if (!state.isPServerAvailable) {
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
                                    onActivateProfile = { profile ->
                                        onApplyCurrent(state.copy(currentValues = profile.maxFrequencies))
                                    },
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
            AppProfileAssignmentDialog(
                app = app,
                currentProfileId = state.appProfileAssignments.firstOrNull { it.packageName == app.packageName }?.profileId,
                profiles = profilesForCompactPicker(state.displayProfiles),
                onDismiss = { showAppAssignmentDialog = false },
                onSave = { selectedProfile ->
                    if (selectedProfile == null) {
                        onDeleteAppProfileAssignment(app.packageName)
                    } else {
                        onSaveAppProfileAssignment(app.packageName, app.label, selectedProfile.id)
                    }
                    showAppAssignmentDialog = false
                },
            )
        }
    }

    dialogProfileId?.let { profileId ->
        val manualProfile = remember(state.actualValues, state.policies) {
            if (state.policies.isEmpty()) {
                null
            } else {
                PerformanceProfile(
                    id = ProfileStateResolver.MANUAL_PROFILE_ID,
                    name = "Manual",
                    maxFrequencies = state.policies.associate { policy ->
                        policy.id to (state.actualValues[policy.id] ?: policy.currentMaxFreq)
                    },
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
            onSave = { name, values ->
                val editedState = state.copy(currentValues = values)
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

    if (showSupportDialog) {
        SupportClusterTuneDialog(onDismiss = { showSupportDialog = false })
    }
}

@Composable
fun CompactTunerScreen(
    state: TunerState,
    displayFrequenciesAsPercent: Boolean,
    onPolicyValueChange: (CpuPolicyInfo, Int) -> Unit,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onClearSelection: () -> Unit,
    onApplyCurrent: (TunerState) -> Unit,
    onDismissRequest: () -> Unit,
    onRefreshLiveValues: () -> Unit,
    onOpenFullApp: () -> Unit,
    showCompactScrim: Boolean = true,
) {
    ScreenNotifications(
        state = state,
        onStatusMessageShown = {},
        onErrorMessageShown = {},
    )

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            onRefreshLiveValues()
        }
    }

    ScreenContainer(compactMode = true, showCompactScrim = showCompactScrim) {
        val colorScheme = MaterialTheme.colorScheme
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(
                    state = state,
                    compactMode = true,
                    onOpenSettings = null,
                )
                if (state.isLoading) {
                    LoadingClustersCard()
                } else {
                    ProfileChipSelector(
                        state = state,
                        onApplyProfile = onApplyProfile,
                        onClearSelection = onClearSelection,
                        onOpenFullApp = onOpenFullApp,
                    )
                    PolicyEditorSection(
                        state = state,
                        displayFrequenciesAsPercent = displayFrequenciesAsPercent,
                        onPolicyValueChange = onPolicyValueChange,
                        compactMode = true,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorScheme.outlineVariant.copy(alpha = 0.48f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(colorScheme.surfaceContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismissRequest,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = { onApplyCurrent(state) },
                            enabled = state.policies.isNotEmpty() && state.isPServerAvailable,
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactProfilePickerScreen(
    state: TunerState,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onDismissRequest: () -> Unit,
    showCompactScrim: Boolean = true,
    contextPackageName: String? = null,
    contextLabel: String? = null,
    contextIcon: Drawable? = null,
    onAppProfileAssignmentChange: ((PerformanceProfile?) -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val profiles = profilesForCompactPicker(state.displayProfiles)
    val assignment = contextPackageName?.let { packageName ->
        state.appProfileAssignments.firstOrNull { it.packageName == packageName }
    }
    val canAssignAppProfile = !contextPackageName.isNullOrBlank() && onAppProfileAssignmentChange != null
    var appProfileEnabled by remember(contextPackageName, assignment?.profileId) {
        mutableStateOf(assignment != null)
    }
    LaunchedEffect(assignment != null) {
        appProfileEnabled = assignment != null
    }
    val selectedProfileId = listOfNotNull(
        assignment?.profileId,
        state.activeDisplayProfileId,
        state.lastAppliedDisplayProfileId,
        state.selectedDisplayProfileId,
    ).firstOrNull { profileId -> profiles.any { profile -> profile.id == profileId } }

    ScreenContainer(
        compactMode = true,
        showCompactScrim = showCompactScrim,
        compactFillHeight = false,
        compactWidthFraction = 1f,
        compactMaxWidth = 360.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surfaceContainer)
                    .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (contextPackageName != null || contextLabel != null || contextIcon != null) {
                        AppIcon(
                            icon = contextIcon,
                            contentDescription = contextLabel,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = contextLabel ?: contextPackageName ?: "Pick a profile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (canAssignAppProfile) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "App profile",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorScheme.onSurface,
                                )
                                Switch(
                                    checked = appProfileEnabled,
                                    onCheckedChange = { enabled ->
                                        appProfileEnabled = enabled
                                        onAppProfileAssignmentChange?.invoke(
                                            if (enabled) {
                                                profiles.firstOrNull { it.id == selectedProfileId }
                                            } else {
                                                null
                                            },
                                        )
                                    },
                                    modifier = Modifier.scale(0.82f),
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.size(32.dp),
                        ) {
                            MaterialSymbol(
                                name = "close",
                                contentDescription = "Close",
                                tint = colorScheme.onSurfaceVariant,
                                size = 22.dp,
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorScheme.outlineVariant.copy(alpha = 0.48f)),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (profiles.isEmpty()) {
                    ProfilePickerEmptyOptionCard()
                } else {
                    profiles.forEach { profile ->
                        ProfileChoiceRow(
                            title = profile.name,
                            selected = selectedProfileId == profile.id,
                            onClick = {
                                if (appProfileEnabled && canAssignAppProfile) {
                                    onAppProfileAssignmentChange?.invoke(profile)
                                }
                                onApplyProfile(profile)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePickerEmptyOptionCard() {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val borderColor = colorScheme.outlineVariant.copy(alpha = 0.28f)
    val contentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(colorScheme.surfaceContainerHigh.copy(alpha = 0.10f), shape),
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
        Text(
            text = "No profiles available",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

@Composable
private fun LoadingClustersCard() {
    SectionCard(
        title = null,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
            )
            Text(
                text = "Scanning CPU clusters...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
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

    Box(modifier = backgroundModifier) {
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
private fun SupportClusterTuneDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qrCode = remember { generateQrCodeBitmap(KOFI_URL, 520).asImageBitmap() }
    val openKofi: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            MaterialSymbol(
                name = "favorite",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                size = 28.dp,
            )
        },
        title = {
            Text(
                text = "Support ClusterTune",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = SUPPORT_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                ) {
                    Image(
                        bitmap = qrCode,
                        contentDescription = "Ko-fi donation QR code",
                        modifier = Modifier
                            .size(196.dp)
                            .padding(12.dp),
                    )
                }
                Text(
                    text = KOFI_URL,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(onClick = openKofi),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = openKofi) {
                Text("Open Ko-fi")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

private fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
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
            .clickable(onClick = onClick),
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
            MaterialSymbol(
                name = symbol,
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
                        Icon(
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
            leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
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
            leadingIcon = { Icon(Icons.Outlined.Apps, contentDescription = null) },
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
            leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
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

@Composable
private fun ProfileSwitchHistorySection(
    entries: List<ProfileSwitchHistoryEntry>,
    modifier: Modifier = Modifier,
) {
    val timestampFormatter = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()) }
    if (entries.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No profile switches logged yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(
                items = entries,
                key = { index, entry -> "${entry.timestampMillis}-$index" },
            ) { _, entry ->
                ProfileSwitchHistoryRow(
                    entry = entry,
                    timestamp = timestampFormatter.format(Date(entry.timestampMillis)),
                )
            }
        }
    }
}

@Composable
private fun ProfileSwitchHistoryRow(
    entry: ProfileSwitchHistoryEntry,
    timestamp: String,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = rowShape,
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.profileName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
            Text(
                text = entry.trigger,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                maxLines = 2,
            )
        }
    }
}

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
                                        val profileName = assignment?.let { profilesById[it.profileId]?.name ?: "Missing profile" }
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
                    MaterialSymbol(
                        name = "history",
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
private fun AppProfileAssignmentDialog(
    app: InstalledAppInfo,
    currentProfileId: String?,
    profiles: List<PerformanceProfile>,
    onDismiss: () -> Unit,
    onSave: (PerformanceProfile?) -> Unit,
) {
    var selectedProfileId by remember(app.packageName, currentProfileId) { mutableStateOf(currentProfileId) }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val colorScheme = MaterialTheme.colorScheme

    CenteredModalSurface(maxWidth = 520.dp, onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surfaceContainer)
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    AppIcon(
                        icon = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.size(48.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorScheme.outlineVariant.copy(alpha = 0.48f)),
            )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProfileChoiceRow(
                        title = "None",
                        selected = selectedProfileId == null,
                        onClick = { selectedProfileId = null },
                    )
                    profiles.forEach { profile ->
                        ProfileChoiceRow(
                            title = profile.name,
                            selected = selectedProfileId == profile.id,
                            onClick = { selectedProfileId = profile.id },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colorScheme.outlineVariant.copy(alpha = 0.48f)),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
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
                                onClick = { onSave(selectedProfile) },
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
private fun ProfileChoiceRow(
    title: String,
    selected: Boolean,
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
        Surface(
            modifier = Modifier.size(if (compact) 22.dp else 26.dp),
            shape = RoundedCornerShape(999.dp),
            color = if (selected) colorScheme.primary else Color.Transparent,
            border = BorderStroke(
                2.dp,
                if (selected) colorScheme.primary else colorScheme.outline.copy(alpha = 0.78f),
            ),
            contentColor = colorScheme.onPrimary,
        ) {
            if (selected) {
                MaterialSymbol(
                    name = "check",
                    contentDescription = "Selected",
                    tint = colorScheme.onPrimary,
                    size = if (compact) 15.dp else 18.dp,
                    modifier = Modifier.padding(if (compact) 3.dp else 4.dp),
                )
            }
        }
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
                MaterialSymbol(
                    name = "apps",
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
                            displayFrequenciesAsPercent = displayFrequenciesAsPercent,
                            isDragging = isDragging,
                            dragActive = draggingProfileId != null,
                            onActivate = { onActivateProfile(profile) },
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
            MaterialSymbol(
                name = "add",
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
    displayFrequenciesAsPercent: Boolean,
    isDragging: Boolean,
    dragActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)
    val containerColor = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f)
    val containerBrush = if (isApplied) {
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
        isSelected -> colorScheme.primary.copy(alpha = 0.58f)
        else -> colorScheme.outlineVariant.copy(alpha = 0.28f)
    }
    val profileNameColor = if (isApplied) borderColor else contentColor
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
                    MaterialSymbol(
                        name = "dark_mode",
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
                )
            }
        }
        if (showEdit) {
            IconButton(onClick = onEdit) {
                MaterialSymbol(
                    name = "tune",
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
            onClick = onActivate,
            enabled = valuePreview.isNotEmpty() && !dragActive,
        )
    }
}

@Composable
private fun ProfileActivationControl(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val borderColor = when {
        !enabled -> colorScheme.outline.copy(alpha = 0.36f)
        selected -> colorScheme.primary
        else -> colorScheme.outline.copy(alpha = 0.78f)
    }
    val fillColor = if (selected) {
        colorScheme.primary.copy(alpha = if (enabled) 1f else 0.42f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(26.dp),
            shape = RoundedCornerShape(999.dp),
            color = fillColor,
            border = BorderStroke(2.dp, borderColor),
            contentColor = colorScheme.onPrimary,
        ) {
            if (selected) {
                MaterialSymbol(
                    name = "check",
                    contentDescription = "Active profile",
                    tint = colorScheme.onPrimary,
                    size = 18.dp,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReorderControl(
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
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
        MaterialSymbol(
            name = "drag_indicator",
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
                    text = formatFrequency(
                        valueKhz = value,
                        boosted = policy?.isBoosted(value) == true,
                        policy = policy,
                        displayAsPercent = displayAsPercent,
                    ),
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.displayProfiles.forEach { profile ->
                ProfileSelectorChip(
                    label = profile.name,
                    isApplied = profile.id == state.activeDisplayProfileId,
                    isSelected = profile.id == state.selectedDisplayProfileId,
                    onClick = { onApplyProfile(profile) },
                )
            }
            if (state.isManualSelection) {
                ProfileSelectorChip(
                    label = "Manual",
                    isApplied = false,
                    isSelected = true,
                    onClick = onClearSelection,
                )
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
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Open full app",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectorChip(
    label: String,
    isApplied: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
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
        label = { Text(label) },
    )
}

@Composable
private fun PolicyEditorSection(
    state: TunerState,
    displayFrequenciesAsPercent: Boolean,
    onPolicyValueChange: (CpuPolicyInfo, Int) -> Unit,
    compactMode: Boolean,
) {
    if (state.policies.isEmpty()) {
        EmptyState(state)
        return
    }

    state.policies.forEach { policy ->
        PolicyCard(
            policy = policy,
            selectedValue = state.currentValues[policy.id] ?: policy.currentMaxFreq,
            actualValue = state.actualValues[policy.id] ?: policy.currentMaxFreq,
            onValueChanged = { onPolicyValueChange(policy, it) },
            compactMode = compactMode,
            displayFrequenciesAsPercent = displayFrequenciesAsPercent,
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
    onSave: (String, Map<Int, Int>) -> Unit,
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
                        PolicyCard(
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
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorScheme.outlineVariant.copy(alpha = 0.48f)),
            )

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
                            Icon(
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
                                    onSave(profile?.name.orEmpty(), editedValues)
                                } else {
                                    onSave(profileName, editedValues)
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
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete profile?") },
            text = { Text("This profile will be removed until you reset profiles to default.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
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
private fun PolicyCard(
    policy: CpuPolicyInfo,
    selectedValue: Int,
    onValueChanged: (Int) -> Unit,
    compactMode: Boolean = false,
    displayFrequenciesAsPercent: Boolean = false,
    actualValue: Int = selectedValue,
) {
    val supported = policy.supportedFrequencies
    val displaySelectedValue = policy.clampToWritableMax(selectedValue)
    val currentIndex = supported.indexOf(displaySelectedValue).takeIf { it >= 0 } ?: supported.lastIndex
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = rowShape,
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides if (compactMode) Dp.Unspecified else 48.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tightRow = maxWidth < 420.dp
                val veryTightRow = maxWidth < 340.dp
                val horizontalGap = when {
                    veryTightRow -> 4.dp
                    tightRow -> 5.dp
                    else -> 6.dp
                }
                val clusterColumnWidth = when {
                    veryTightRow -> 72.dp
                    tightRow -> 84.dp
                    else -> 96.dp
                }
                val valueColumnWidth = when {
                    veryTightRow -> 58.dp
                    tightRow -> 66.dp
                    else -> 76.dp
                }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 62.dp)
                    .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(horizontalGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.width(clusterColumnWidth),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Cluster ${policy.id}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Now ${formatFrequency(actualValue, boosted = policy.isBoosted(actualValue), policy = policy, displayAsPercent = displayFrequenciesAsPercent)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                CompactFrequencySlider(
                    valueIndex = currentIndex,
                    maxIndex = supported.lastIndex,
                    onIndexChange = { index -> onValueChanged(supported[index]) },
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = formatFrequency(selectedValue, boosted = policy.isBoosted(selectedValue), policy = policy, displayAsPercent = displayFrequenciesAsPercent),
                    modifier = Modifier.width(valueColumnWidth),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            }
        }
    }
}

@Composable
private fun CompactFrequencySlider(
    valueIndex: Int,
    maxIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val trackHeight = with(density) { 4.dp.toPx() }
    val tickRadius = with(density) { 1.4.dp.toPx() }
    val thumbRadius = with(density) { 7.dp.toPx() }
    val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

    BoxWithConstraints(modifier = modifier.height(24.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        fun indexForPosition(x: Float): Int {
            if (maxIndex <= 0) return 0
            return ((x / widthPx) * maxIndex).roundToInt().coerceIn(0, maxIndex)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(maxIndex, widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onIndexChange(indexForPosition(down.position.x))
                        val pointerId = down.id
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change != null) {
                                onIndexChange(indexForPosition(change.position.x))
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            val centerY = size.height / 2f
            val clampedMax = maxIndex.coerceAtLeast(1)
            val progress = valueIndex.coerceIn(0, clampedMax).toFloat() / clampedMax.toFloat()
            val thumbX = size.width * progress

            drawRoundRect(
                color = colorScheme.outlineVariant.copy(alpha = 0.34f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = cornerRadius,
            )
            drawRoundRect(
                color = colorScheme.primary,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(thumbX, trackHeight),
                cornerRadius = cornerRadius,
            )
            if (maxIndex > 1) {
                for (index in 0..maxIndex) {
                    val tickX = size.width * (index.toFloat() / maxIndex.toFloat())
                    drawCircle(
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
                        radius = tickRadius,
                        center = Offset(tickX, centerY),
                    )
                }
            }
            drawCircle(
                color = colorScheme.primary,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
            )
        }
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
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = contentModifier.padding(contentPadding),
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

private fun CpuPolicyInfo.clampToWritableMax(valueKhz: Int): Int {
    return valueKhz.coerceAtMost(selectableMaxFreq)
}

private fun CpuPolicyInfo.isBoosted(valueKhz: Int): Boolean {
    return valueKhz > selectableMaxFreq
}

internal fun formatFrequency(
    valueKhz: Int,
    boosted: Boolean = false,
    policy: CpuPolicyInfo? = null,
    displayAsPercent: Boolean = false,
): String {
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
