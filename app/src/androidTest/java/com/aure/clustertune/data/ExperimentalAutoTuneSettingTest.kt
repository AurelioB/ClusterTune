package com.aure.clustertune.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.AppContainer
import com.aure.clustertune.autotune.AdaptiveTuneRuntime
import com.aure.clustertune.model.AppProfileAssignment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentalAutoTuneSettingTest {
    @Test fun settingPersistsAndDisabledBackendRejectsStartsAndNewAssignments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStorage(context)
        val previous = settings.settings.first().autoTuneEnabled
        val container = AppContainer(context)
        val beforeAssignments = container.profileStorage.appProfileAssignments.first()
        var generation: Long? = null
        try {
            settings.persistAutoTuneEnabled(true)
            assertTrue(SettingsStorage(context).settings.first().autoTuneEnabled)
            settings.persistAutoTuneEnabled(false)
            assertFalse(SettingsStorage(context).settings.first().autoTuneEnabled)
            val token = AdaptiveTuneRuntime.begin(context.packageName, "Experimental feature test", 30)
            generation = token
            val failure = runCatching { container.repository.start(context.packageName, 30, token) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Auto Tune is disabled in Settings", failure?.message)
            val saveFailure = runCatching {
                container.repository.saveAppProfileAssignment(AppProfileAssignment(
                    "test.disabled.autotune", "Test", autoTuneTargetFps = 60,
                ))
            }.exceptionOrNull()
            assertEquals("Auto Tune is disabled in Settings", saveFailure?.message)
            assertEquals(beforeAssignments, container.profileStorage.appProfileAssignments.first())
        } finally {
            generation?.let { AdaptiveTuneRuntime.finish(it) }
            AdaptiveTuneRuntime.clearInactive()
            settings.persistAutoTuneEnabled(previous)
            container.close()
        }
    }
}
