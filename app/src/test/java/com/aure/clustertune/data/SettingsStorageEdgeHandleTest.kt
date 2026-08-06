package com.aure.clustertune.data

import com.aure.clustertune.model.MAX_EDGE_HANDLE_THICKNESS_DP
import com.aure.clustertune.model.MIN_EDGE_HANDLE_THICKNESS_DP
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStorageEdgeHandleTest {

    @Test
    fun `opacity accepts a fully hidden handle`() {
        assertEquals(0, normalizeEdgeHandleOpacityPercent(0))
        assertEquals(0, normalizeEdgeHandleOpacityPercent(-1))
        assertEquals(100, normalizeEdgeHandleOpacityPercent(101))
    }

    @Test
    fun `thickness stays inside the visible handle bounds`() {
        assertEquals(
            MIN_EDGE_HANDLE_THICKNESS_DP,
            normalizeEdgeHandleThicknessDp(MIN_EDGE_HANDLE_THICKNESS_DP - 1),
        )
        assertEquals(10, normalizeEdgeHandleThicknessDp(10))
        assertEquals(
            MAX_EDGE_HANDLE_THICKNESS_DP,
            normalizeEdgeHandleThicknessDp(MAX_EDGE_HANDLE_THICKNESS_DP + 1),
        )
    }
}
