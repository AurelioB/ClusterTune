package com.aure.clustertune.ui.designsystem.token

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTuneTokenTest {
    @Test
    fun regularTokensPreserveProfilesViewRhythm() {
        assertEquals(20.dp, ClusterTuneSpacings.Regular.screenHorizontal)
        assertEquals(28.dp, ClusterTuneSpacings.Regular.screenVertical)
        assertEquals(18.dp, ClusterTuneSpacings.Regular.sectionGap)
        assertEquals(16.dp, ClusterTuneSpacings.Regular.cardPadding)
        assertEquals(12.dp, ClusterTuneSpacings.Regular.contentGap)
        assertEquals(48.dp, ClusterTuneSizes.Regular.interactiveTarget)
    }

    @Test
    fun connectedDeviceUsesCompactLandscapeDensity() {
        assertTrue(ClusterTuneBreakpoints.isCompactLandscape(833.dp, 468.dp))
        assertEquals(16.dp, ClusterTuneBreakpoints.spacingFor(833.dp, 468.dp).screenVertical)
        assertEquals(36.dp, ClusterTuneBreakpoints.sizingFor(833.dp, 468.dp).appearanceControlHeight)
        assertEquals(48.dp, ClusterTuneBreakpoints.sizingFor(833.dp, 468.dp).interactiveTarget)
        assertTrue(ClusterTuneBreakpoints.usesTwoColumnSettings(833.dp, 468.dp))
        assertEquals(8.dp, ClusterTuneBreakpoints.densityFor(833.dp, 468.dp).spacing.contentGap)
    }

    @Test
    fun portraitAndTallLandscapeRemainRegular() {
        assertFalse(ClusterTuneBreakpoints.isCompactLandscape(468.dp, 833.dp))
        assertFalse(ClusterTuneBreakpoints.isCompactLandscape(1280.dp, 720.dp))
        assertEquals(28.dp, ClusterTuneBreakpoints.spacingFor(1280.dp, 720.dp).screenVertical)
    }

    @Test
    fun compactBoundaryIsInclusive() {
        assertTrue(ClusterTuneBreakpoints.isCompactLandscape(601.dp, 600.dp))
        assertTrue(ClusterTuneBreakpoints.isCompactLandscape(600.dp, 500.dp))
        assertFalse(ClusterTuneBreakpoints.isCompactLandscape(600.dp, 600.dp))
        assertFalse(ClusterTuneBreakpoints.isCompactLandscape(599.dp, 600.dp))
        assertFalse(ClusterTuneBreakpoints.isCompactLandscape(600.dp, 601.dp))
        assertFalse(ClusterTuneBreakpoints.usesTwoColumnSettings(719.dp, 500.dp))
    }
}
