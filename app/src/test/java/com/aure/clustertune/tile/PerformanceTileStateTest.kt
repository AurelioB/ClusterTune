package com.aure.clustertune.tile

import com.aure.clustertune.model.EffectiveProfileSource
import com.aure.clustertune.model.EffectiveProfileState
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TileInteractionBehavior
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTileStateTest {

    @Test
    fun `persisted effective app profile wins over the normal fallback`() {
        val normal = profile("normal")
        val effective = EffectiveProfileState(
            id = "app:game",
            name = "Custom",
            source = EffectiveProfileSource.APP,
            contributingPackageNames = listOf("game"),
        )

        assertEquals(effective, resolveEffectiveTileState(effective, listOf(normal), normal.id))
    }

    @Test
    fun `stored profile is used to migrate a missing effective state`() {
        val normal = profile("normal")

        assertEquals(
            EffectiveProfileState(normal.id, normal.name, EffectiveProfileSource.NORMAL),
            resolveEffectiveTileState(null, listOf(normal), normal.id),
        )
    }

    @Test
    fun `stock and manual fallbacks do not require stored profile rows`() {
        assertEquals(
            EffectiveProfileSource.STOCK,
            resolveEffectiveTileState(null, emptyList(), ProfileStateResolver.STOCK_PROFILE_ID)?.source,
        )
        assertEquals(
            EffectiveProfileSource.MANUAL,
            resolveEffectiveTileState(null, emptyList(), ProfileStateResolver.MANUAL_PROFILE_ID)?.source,
        )
    }

    @Test
    fun `tap actions route overlay permission`() {
        assertEquals(
            TileTapAction.SHOW_DIALOG,
            resolveTileTapAction(TileInteractionBehavior.SHOW_DIALOG, true),
        )
        assertEquals(
            TileTapAction.SHOW_PROFILE_PICKER,
            resolveTileTapAction(TileInteractionBehavior.SHOW_PROFILE_PICKER, true),
        )
        assertEquals(
            TileTapAction.REQUEST_OVERLAY_PERMISSION,
            resolveTileTapAction(TileInteractionBehavior.SHOW_DIALOG, false),
        )
        assertEquals(
            TileTapAction.REQUEST_OVERLAY_PERMISSION,
            resolveTileTapAction(TileInteractionBehavior.SHOW_PROFILE_PICKER, false),
        )
        assertEquals(
            TileTapAction.OPEN_APP,
            resolveTileTapAction(TileInteractionBehavior.OPEN_APP, false),
        )
        assertEquals(
            TileTapAction.CYCLE_PROFILES,
            resolveTileTapAction(TileInteractionBehavior.CYCLE_PROFILES, false),
        )
    }

    private fun profile(id: String) = PerformanceProfile(
        id = id,
        name = id,
        maxFrequencies = mapOf(0 to 1_000_000),
        source = ProfileSource.USER,
    )
}
