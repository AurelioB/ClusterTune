package com.aure.clustertune.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aure.clustertune.R

/**
 * Two modes:
 *  - When [HandoffRequest.showTutorial] is true: a multi-page
 *    interactive walkthrough with screenshots showing exactly what to
 *    tap inside Odin Settings.
 *  - When false: a short reminder with the script path and three
 *    buttons — Cancel, Guide (re-opens the tutorial), Open Odin
 *    Settings.
 */
@Composable
fun OdinHandoffDialog(
    request: com.aure.clustertune.ui.TunerViewModel.HandoffRequest,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The state lives inside the dialog so the user can flip back
    // and forth between tutorial and reminder modes without us
    // round-tripping through the view model.
    var showingTutorial by remember { mutableStateOf(request.showTutorial) }

    if (showingTutorial) {
        TutorialDialog(
            request = request,
            onOpenSettings = onOpenSettings,
            onDismiss = onDismiss,
        )
    } else {
        ReminderDialog(
            request = request,
            onOpenSettings = onOpenSettings,
            onDismiss = onDismiss,
            onShowGuide = { showingTutorial = true },
        )
    }
}

@Composable
private fun ReminderDialog(
    request: com.aure.clustertune.ui.TunerViewModel.HandoffRequest,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onShowGuide: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply via Odin Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Open Odin Settings, run clustertune-apply.sh through " +
                        "Run script as Root, then return here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = request.userVisiblePath,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Odin Settings")
            }
        },
        dismissButton = {
            // Cancel on the left, Guide on the right (closest to the
            // confirm button on the far right).
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                TextButton(onClick = onShowGuide) {
                    Text("Guide")
                }
            }
        },
    )
}

@Composable
private fun TutorialDialog(
    request: com.aure.clustertune.ui.TunerViewModel.HandoffRequest,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pages = remember(request.userVisiblePath) { tutorialPages(request.userVisiblePath) }
    var pageIndex by remember { mutableStateOf(0) }
    val page = pages[pageIndex]
    val isLastPage = pageIndex == pages.lastIndex

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${page.title}  (${pageIndex + 1}/${pages.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page.imageRes != null) {
                    Image(
                        painter = painterResource(id = page.imageRes),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    text = page.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (page.pathHighlight != null) {
                    Text(
                        text = page.pathHighlight,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        },
        confirmButton = {
            if (isLastPage) {
                TextButton(onClick = onOpenSettings) {
                    Text("Open Odin Settings")
                }
            } else {
                TextButton(onClick = { pageIndex++ }) {
                    Text("Next")
                }
            }
        },
        dismissButton = {
            // Cancel always on the far left. Back follows Cancel when
            // we're past the first page.
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                if (pageIndex > 0) {
                    TextButton(onClick = { pageIndex-- }) {
                        Text("Back")
                    }
                }
            }
        },
    )
}

private data class TutorialPage(
    val title: String,
    val body: String,
    val imageRes: Int? = null,
    val pathHighlight: String? = null,
)

private fun tutorialPages(userVisiblePath: String): List<TutorialPage> {
    return listOf(
        TutorialPage(
            title = "Apply via Odin Settings",
            body = "This device needs an extra step to apply CPU frequency limits. " +
                "ClusterTune has written the apply script and Odin Settings will run it.",
        ),
        TutorialPage(
            title = "Run script as Root",
            body = "After tapping Open Odin Settings, scroll down and tap " +
                "\"Run script as Root\" in the Odin Settings list.",
            imageRes = R.drawable.tutorial_run_script_item,
        ),
        TutorialPage(
            title = "Select a script",
            body = "Read the warning and tap \"SELECT A SCRIPT\".",
            imageRes = R.drawable.tutorial_select_script,
        ),
        TutorialPage(
            title = "Pick clustertune-apply.sh",
            body = "From the file picker, tap the menu icon at the top left, then " +
                "tap your device name (Odin2 Mini) to browse storage from the root. " +
                "Open Documents, then the ClusterScripts folder, then pick " +
                "clustertune-apply.sh.",
            imageRes = R.drawable.tutorial_file_picker,
            pathHighlight = userVisiblePath,
        ),
        TutorialPage(
            title = "Return to ClusterTune",
            body = "Odin Settings will show \"applied\" when the script finishes. " +
                "Switch back to ClusterTune and you'll see whether the underclock " +
                "landed in a brief toast at the bottom of the screen.",
        ),
    )
}
