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
                    usageGranted = true,
                    notificationsGranted = true,
                ),
            ),
        )
    }

    @Test
    fun `each missing access is included`() {
        assertEquals(
            listOf(AppAccess.OVERLAY),
            missingAppAccess(AppAccessStatus(false, true, true)),
        )
        assertEquals(
            listOf(AppAccess.USAGE),
            missingAppAccess(AppAccessStatus(true, false, true)),
        )
        assertEquals(
            listOf(AppAccess.NOTIFICATIONS),
            missingAppAccess(AppAccessStatus(true, true, false)),
        )
    }

    @Test
    fun `missing access uses stable overlay usage notifications order`() {
        assertEquals(
            listOf(AppAccess.OVERLAY, AppAccess.USAGE, AppAccess.NOTIFICATIONS),
            missingAppAccess(
                AppAccessStatus(
                    overlayGranted = false,
                    usageGranted = false,
                    notificationsGranted = false,
                ),
            ),
        )
    }
}
