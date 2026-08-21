package com.aure.clustertune.quicktuner

import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.GpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TunerState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTunerApplyHandlerTest {

    private val policy = CpuPolicyInfo(
        id = 0,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        currentMaxFreq = 1_000_000,
        selectableMaxFreq = 2_000_000,
        observedMaxFreq = 2_000_000,
        minFreq = 500_000,
        supportedFrequencies = listOf(500_000, 1_000_000, 2_000_000),
    )

    @Test
    fun appliesAndLogsResolvedProfile() = runTest {
        val profile = PerformanceProfile(
            id = "balanced",
            name = "Balanced",
            maxFrequencies = mapOf(0 to 1_000_000),
            source = ProfileSource.USER,
        )
        val repository = FakeQuickTunerRepository()
        val toasts = mutableListOf<String>()
        var tileRefreshCount = 0
        val handler = QuickTunerApplyHandler(
            repository = repository,
            showToast = { message, _ -> toasts += message },
            refreshTile = { tileRefreshCount++ },
        )

        val result = handler.applyCurrent(
            TunerState(
                policies = listOf(policy),
                currentValues = profile.maxFrequencies,
                displayProfiles = listOf(profile),
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals("balanced", repository.appliedDisplayProfileId)
        assertEquals("balanced", repository.selectedProfileId)
        assertEquals("balanced", repository.loggedProfileId)
        assertEquals("Balanced", repository.loggedProfileName)
        assertEquals("Quick Settings dialog", repository.loggedTrigger)
        assertEquals(listOf("Balanced"), toasts)
        assertEquals(1, tileRefreshCount)
    }

    @Test
    fun forwardsGpuArgumentsToRepository() = runTest {
        val repository = FakeQuickTunerRepository()
        val gpu = GpuPolicyInfo("/gpu", "/gpu/max", currentMaxFrequencyHz = 500, selectableMaxFrequencyHz = 600, observedMaxFrequencyHz = 600)
        QuickTunerApplyHandler(repository, { _, _ -> }, {}).applyCurrent(
            TunerState(policies = listOf(policy), gpuPolicy = gpu, currentValues = mapOf(0 to 1_000_000), currentGpuMaxFrequencyHz = 600),
        )
        assertEquals(gpu, repository.gpuPolicy)
        assertEquals(600, repository.selectedGpuMaxFrequencyHz)
    }

    @Test
    fun logsManualProfileWhenValuesDoNotMatchDisplayProfile() = runTest {
        val repository = FakeQuickTunerRepository()
        val handler = QuickTunerApplyHandler(
            repository = repository,
            showToast = { _, _ -> },
            refreshTile = {},
        )

        handler.applyCurrent(
            TunerState(
                policies = listOf(policy),
                currentValues = mapOf(0 to 500_000),
                displayProfiles = emptyList(),
            ),
        )

        assertEquals(ProfileStateResolver.MANUAL_PROFILE_ID, repository.appliedDisplayProfileId)
        assertEquals(null, repository.selectedProfileId)
        assertEquals(ProfileStateResolver.MANUAL_PROFILE_ID, repository.loggedProfileId)
        assertEquals("Manual", repository.loggedProfileName)
    }

    @Test
    fun appliesPickedProfileAndLogsPickerTrigger() = runTest {
        val profile = PerformanceProfile(
            id = "gaming",
            name = "Gaming",
            maxFrequencies = mapOf(0 to 2_000_000),
            source = ProfileSource.USER,
        )
        val repository = FakeQuickTunerRepository()
        val toasts = mutableListOf<String>()
        var tileRefreshCount = 0
        val handler = QuickTunerApplyHandler(
            repository = repository,
            showToast = { message, _ -> toasts += message },
            refreshTile = { tileRefreshCount++ },
        )

        val result = handler.applyProfile(
            state = TunerState(policies = listOf(policy)),
            profile = profile,
        )

        assertTrue(result.isSuccess)
        assertEquals("gaming", repository.appliedDisplayProfileId)
        assertEquals("gaming", repository.selectedProfileId)
        assertEquals("gaming", repository.loggedProfileId)
        assertEquals("Gaming", repository.loggedProfileName)
        assertEquals("Quick Settings picker", repository.loggedTrigger)
        assertEquals(listOf("Gaming"), toasts)
        assertEquals(1, tileRefreshCount)
    }

    @Test
    fun appliesPickedProfileGpuLimit() = runTest {
        val gpu = GpuPolicyInfo(
            policyPath = "/gpu",
            maxFrequencyPath = "/gpu/max",
            currentMaxFrequencyHz = 500,
            selectableMaxFrequencyHz = 600,
            observedMaxFrequencyHz = 600,
        )
        val profile = PerformanceProfile(
            id = "gpu-quiet",
            name = "GPU Quiet",
            maxFrequencies = mapOf(0 to 1_000_000),
            source = ProfileSource.USER,
            gpuMaxFrequencyHz = 400,
        )
        val repository = FakeQuickTunerRepository()

        val result = QuickTunerApplyHandler(repository, { _, _ -> }, {})
            .applyProfile(TunerState(policies = listOf(policy), gpuPolicy = gpu), profile)

        assertTrue(result.isSuccess)
        assertEquals(gpu, repository.gpuPolicy)
        assertEquals(400, repository.selectedGpuMaxFrequencyHz)
    }

    private class FakeQuickTunerRepository : QuickTunerApplyRepository {
        var appliedDisplayProfileId: String? = null
        var selectedProfileId: String? = null
        var loggedProfileId: String? = null
        var loggedProfileName: String? = null
        var loggedTrigger: String? = null
        var gpuPolicy: GpuPolicyInfo? = null
        var selectedGpuMaxFrequencyHz: Int? = null

        override suspend fun applyValues(
            policies: List<CpuPolicyInfo>,
            selectedValues: Map<Int, Int>,
            gpuPolicy: GpuPolicyInfo?,
            selectedGpuMaxFrequencyHz: Int?,
            isReset: Boolean,
            appliedDisplayProfileId: String?,
            onHardwareApplied: (suspend (PerformanceRepository.ApplyOutcome) -> Unit)?,
        ): Result<PerformanceRepository.ApplyOutcome> {
            this.appliedDisplayProfileId = appliedDisplayProfileId
            this.selectedProfileId = appliedDisplayProfileId?.takeUnless {
                it == ProfileStateResolver.STOCK_PROFILE_ID || it == ProfileStateResolver.MANUAL_PROFILE_ID
            }
            this.gpuPolicy = gpuPolicy
            this.selectedGpuMaxFrequencyHz = selectedGpuMaxFrequencyHz
            val outcome = PerformanceRepository.ApplyOutcome(
                    actualValues = selectedValues,
                    verificationPassed = true,
                    commandOutput = null,
                )
            onHardwareApplied?.invoke(outcome)
            return Result.success(outcome)
        }

        override suspend fun logProfileSwitch(profileId: String?, profileName: String, trigger: String) {
            loggedProfileId = profileId
            loggedProfileName = profileName
            loggedTrigger = trigger
        }
    }
}
