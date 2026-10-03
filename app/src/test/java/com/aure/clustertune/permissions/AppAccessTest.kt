package com.aure.clustertune.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccessTest {
    @Test
    fun `disconnected service is not reported as revoked permission`() {
        assertEquals(
            listOf(AppAccess.ACCESSIBILITY_SERVICE),
            missingAppAccess(AppAccessStatus(true, true, true, true, accessibilityServiceDisconnected = true)),
        )
        assertEquals(
            listOf(AppAccess.ACCESSIBILITY),
            missingAppAccess(AppAccessStatus(true, false, true, true, accessibilityServiceDisconnected = true)),
        )
    }

    @Test
    fun `service reconnect suppresses warning and initial binding gets a grace period`() {
        assertFalse(accessibilityServiceNeedsRestart(true, false, false))
        assertTrue(accessibilityServiceNeedsRestart(true, false, true))
        assertFalse(accessibilityServiceNeedsRestart(true, true, true))
        assertFalse(accessibilityServiceNeedsRestart(false, false, true))
    }

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
