package com.aure.clustertune.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAccessTest {
    @Test
    fun `all granted returns no missing access`() {
        assertEquals(
            emptyList<AppAccess>(),
            missingAppAccess(
                AppAccessStatus(
                    overlayGranted = true,
                    accessibilityGranted = true,
                    usageGranted = true,
                    notificationsGranted = true,
                ),
            ),
        )
    }

    @Test
    fun `each missing access is included`() {
        assertEquals(listOf(AppAccess.OVERLAY), missingAppAccess(AppAccessStatus(false, true, true, true)))
        assertEquals(listOf(AppAccess.ACCESSIBILITY), missingAppAccess(AppAccessStatus(true, false, true, true)))
        assertEquals(emptyList<AppAccess>(), missingAppAccess(AppAccessStatus(true, true, false, true)))
        assertEquals(listOf(AppAccess.NOTIFICATIONS), missingAppAccess(AppAccessStatus(true, true, true, false)))
    }

    @Test
    fun `missing access uses stable overlay accessibility notifications order`() {
        assertEquals(
            listOf(AppAccess.OVERLAY, AppAccess.ACCESSIBILITY, AppAccess.NOTIFICATIONS),
            missingAppAccess(
                AppAccessStatus(
                    overlayGranted = false,
                    accessibilityGranted = false,
                    usageGranted = false,
                    notificationsGranted = false,
                ),
            ),
        )
    }
}
