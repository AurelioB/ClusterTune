package com.aure.clustertune.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeHandleGeometryTest {

    @Test
    fun `position zero anchors handle at top`() {
        assertEquals(
            0,
            calculateEdgeHandleTopOffset(
                displayHeightPx = 1_080,
                handleHeightPx = 200,
                verticalPositionPercent = 0,
            ),
        )
    }

    @Test
    fun `position fifty centers handle`() {
        assertEquals(
            440,
            calculateEdgeHandleTopOffset(
                displayHeightPx = 1_080,
                handleHeightPx = 200,
                verticalPositionPercent = 50,
            ),
        )
    }

    @Test
    fun `position one hundred anchors handle at bottom`() {
        assertEquals(
            880,
            calculateEdgeHandleTopOffset(
                displayHeightPx = 1_080,
                handleHeightPx = 200,
                verticalPositionPercent = 100,
            ),
        )
    }

    @Test
    fun `position and available travel are clamped`() {
        assertEquals(
            0,
            calculateEdgeHandleTopOffset(
                displayHeightPx = 100,
                handleHeightPx = 200,
                verticalPositionPercent = 150,
            ),
        )
    }
}
