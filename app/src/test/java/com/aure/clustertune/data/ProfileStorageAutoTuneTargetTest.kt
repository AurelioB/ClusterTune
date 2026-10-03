package com.aure.clustertune.data

import com.aure.clustertune.model.AppProfileAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStorageAutoTuneTargetTest {
    @Test
    fun `captured package and target must still match before slider update`() {
        val a = AppProfileAssignment("game.a", "A", autoTuneTargetFps = 30)
        val b = AppProfileAssignment("game.b", "B", autoTuneTargetFps = 60)

        val updated = conditionalAutoTuneTargetUpdate(listOf(a, b), "game.a", 30, 45)
        val stalePackage = conditionalAutoTuneTargetUpdate(listOf(a, b), "game.c", 30, 45)
        val staleTarget = conditionalAutoTuneTargetUpdate(listOf(a, b), "game.a", 60, 45)

        assertTrue(updated.updated)
        assertEquals(45, updated.assignments.first().autoTuneTargetFps)
        assertEquals(60, updated.assignments.last().autoTuneTargetFps)
        assertFalse(stalePackage.updated)
        assertFalse(staleTarget.updated)
        assertEquals(listOf(a, b), stalePackage.assignments)
        assertEquals(listOf(a, b), staleTarget.assignments)
    }

    @Test
    fun `fixed profile cannot be turned into Auto Tune by stale HUD callback`() {
        val fixed = AppProfileAssignment("game.a", "A", profileId = "balanced")
        val result = conditionalAutoTuneTargetUpdate(listOf(fixed), "game.a", 30, 45)
        assertFalse(result.updated)
        assertEquals(listOf(fixed), result.assignments)
    }
}
