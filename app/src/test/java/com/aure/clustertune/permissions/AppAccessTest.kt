package com.aure.clustertune.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAccessTest {
    @Test fun `normal operation needs no accessibility or usage grant`() {
        assertEquals(emptyList<AppAccess>(), missingAppAccess(AppAccessStatus(true, true)))
    }

    @Test fun `backend failure is reported separately from permissions`() {
        assertEquals(listOf(AppAccess.APP_DETECTION),
            missingAppAccess(AppAccessStatus(true, true, appDetectionUnavailable = true)))
    }

    @Test fun `missing access keeps stable order`() {
        assertEquals(listOf(AppAccess.OVERLAY, AppAccess.APP_DETECTION, AppAccess.NOTIFICATIONS),
            missingAppAccess(AppAccessStatus(false, false, appDetectionUnavailable = true)))
    }
}
