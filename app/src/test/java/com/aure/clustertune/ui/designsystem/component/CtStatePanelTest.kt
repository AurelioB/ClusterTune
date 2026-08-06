package com.aure.clustertune.ui.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CtStatePanelTest {
    @Test
    fun stateKinds_coverTheSharedPresentationStates() {
        assertEquals(
            listOf("Empty", "Loading", "Warning", "Error"),
            CtStatePanelState.values().map { it.name },
        )
    }
}
