package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp

/** Shared modal structure with a header, scrollable body, and action footer. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CtModalScaffold(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    appIdentity: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    footer: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Contain controller focus inside the modal. Without this, D-pad
            // left/right escapes to the content behind the overlay (nav rail /
            // app list) and cannot get back in. Cancelling the group's exit
            // blocks focus *movement* out without consuming any key, so
            // left/right are still delivered to children that need them.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusRestorer()
            .focusGroup(),
    ) {
        if (title != null || appIdentity != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                appIdentity?.invoke()
                title?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        (footer ?: actions)?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(contentPadding),
                horizontalArrangement = Arrangement.End,
                content = it,
            )
        }
    }
}
