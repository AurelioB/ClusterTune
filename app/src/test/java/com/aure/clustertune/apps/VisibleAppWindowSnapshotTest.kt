package com.aure.clustertune.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleAppWindowSnapshotTest {
    @Test
    fun packagesIncludeApplicationsFromEveryDisplay() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("com.example.primary", 0, isFocused = true)),
                1 to listOf(VisibleAppWindow("com.example.secondary", 1, isActive = true)),
            ),
            isInteractive = true,
        )

        assertEquals(
            setOf("com.example.primary", "com.example.secondary"),
            snapshot.packages,
        )
    }

    @Test
    fun packageSetIsUnaffectedByFocusChanges() {
        val focusedPrimary = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0, isFocused = true),
                    VisibleAppWindow("com.example.second", 0),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
            isInteractive = true,
        )
        val focusedSecondary = focusedPrimary.copy(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0),
                    VisibleAppWindow("com.example.second", 0, isFocused = true),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
        )

        assertEquals(focusedPrimary.packages, focusedSecondary.packages)
    }

    @Test
    fun resolverContributorsDoNotChangeWhenOnlyFocusChanges() {
        val before = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0, isFocused = true),
                    VisibleAppWindow("com.example.second", 0),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
            isInteractive = true,
        )
        val after = before.copy(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0),
                    VisibleAppWindow("com.example.second", 0, isFocused = true),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
        )

        fun resolve(snapshot: VisibleAppSnapshot) = CombinedAppProfileResolver.resolve(
            snapshot.packages.map { packageName ->
                VisibleAppProfileTarget(packageName = packageName, profileId = packageName)
            },
        ).contributors.map { it.packageName }

        assertEquals(resolve(before), resolve(after))
    }

    @Test
    fun duplicateWindowsRemainPresentInSnapshotButPackageSetDeduplicates() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("com.example.game", 0, isFocused = true)),
                1 to listOf(VisibleAppWindow("com.example.game", 1, isActive = true)),
            ),
            isInteractive = true,
        )

        assertEquals(2, snapshot.windowsByDisplay.values.flatten().size)
        assertEquals(setOf("com.example.game"), snapshot.packages)
    }

    @Test
    fun emptySnapshotHasNoContributors() {
        assertTrue(VisibleAppSnapshot.Empty.packages.isEmpty())
    }

}
