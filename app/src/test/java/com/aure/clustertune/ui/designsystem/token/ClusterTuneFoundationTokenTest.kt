package com.aure.clustertune.ui.designsystem.token

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterTuneFoundationTokenTest {
    @Test fun elevationRolesMatchCurrentSurfaces() {
        assertEquals(0.dp, ClusterTuneElevation.flat)
        assertEquals(4.dp, ClusterTuneElevation.selected)
        assertEquals(8.dp, ClusterTuneElevation.modal)
    }

    @Test fun reducedMotionDisablesDuration() {
        assertEquals(0, ClusterTuneMotion.durationMillis(reducedMotion = true))
        assertEquals(220, ClusterTuneMotion.durationMillis(reducedMotion = false, normal = 220))
    }
}
