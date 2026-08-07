package com.aure.clustertune.data

import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.ProfileStateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceRepositoryTargetResolutionTest {

    private val policy = CpuPolicyInfo(
        id = 0,
        policyPath = "policy0",
        scalingMaxPath = "max",
        currentMaxFreq = 1_000,
        selectableMaxFreq = 1_000,
        observedMaxFreq = 1_200,
        minFreq = 300,
        supportedFrequencies = listOf(800, 1_000),
    )

    @Test
    fun `valid named profile replaces stale persisted values with current definition`() {
        val profile = profile("quiet", mapOf(0 to 800))
        val result = resolvePersistedTarget(listOf(policy), listOf(profile), "quiet", mapOf(0 to 1_000))
        assertEquals(ResolvedPerformanceTarget(mapOf(0 to 800), "quiet", false), result)
    }

    @Test
    fun `hidden observed stock bin is accepted for stock reset`() {
        val stock = profile(ProfileStateResolver.STOCK_PROFILE_ID, mapOf(0 to 1_200), ProfileSource.VIRTUAL)
        val result = resolvePersistedTarget(listOf(policy), listOf(stock), stock.id, mapOf(0 to 800))
        assertEquals(ResolvedPerformanceTarget(mapOf(0 to 1_200), stock.id, true), result)
    }

    @Test
    fun `unknown id with complete supported map repairs to manual`() {
        val result = resolvePersistedTarget(listOf(policy), emptyList(), "deleted", mapOf(0 to 800))
        assertEquals(ResolvedPerformanceTarget(mapOf(0 to 800), ProfileStateResolver.MANUAL_PROFILE_ID, false), result)
    }

    @Test
    fun `unknown id with complete observed stock values repairs to manual`() {
        val result = resolvePersistedTarget(listOf(policy), emptyList(), "deleted", mapOf(0 to 1_200))
        assertEquals(ResolvedPerformanceTarget(mapOf(0 to 1_200), ProfileStateResolver.MANUAL_PROFILE_ID, false), result)
        assertEquals(true, allowsObservedMaxValues(result!!, listOf(policy)))
    }

    @Test
    fun `partial map is rejected`() {
        val second = policy.copy(id = 1)
        assertNull(resolvePersistedTarget(listOf(policy, second), emptyList(), ProfileStateResolver.MANUAL_PROFILE_ID, mapOf(0 to 800)))
    }

    @Test
    fun `legacy baseline always restores observed stock ceilings instead of live app cap`() {
        val second = policy.copy(
            id = 1,
            selectableMaxFreq = 900,
            observedMaxFreq = 1_400,
            supportedFrequencies = listOf(700, 900),
        )

        // The live values may still be an assigned underclock, but they must
        // not become the normal baseline when legacy storage is incomplete.
        assertEquals(
            ResolvedPerformanceTarget(
                values = mapOf(0 to 1_200, 1 to 1_400),
                profileId = ProfileStateResolver.STOCK_PROFILE_ID,
                isReset = true,
            ),
            resolveLegacyStockBaseline(listOf(policy, second)),
        )
    }

    @Test
    fun `unsupported stale boost is rejected`() {
        assertNull(resolvePersistedTarget(listOf(policy), emptyList(), ProfileStateResolver.MANUAL_PROFILE_ID, mapOf(0 to 1_100)))
    }

    @Test
    fun `extra legacy policy key is normalized away`() {
        val result = resolvePersistedTarget(listOf(policy), emptyList(), ProfileStateResolver.MANUAL_PROFILE_ID, mapOf(0 to 800, 9 to 900))
        assertEquals(mapOf(0 to 800), result?.values)
    }

    @Test
    fun `partial custom values merge with complete valid baseline`() {
        val second = policy.copy(id = 1, supportedFrequencies = listOf(700, 900), currentMaxFreq = 900)
        assertEquals(
            mapOf(0 to 800, 1 to 900),
            mergeCustomValues(listOf(policy, second), mapOf(0 to 800), mapOf(0 to 1_000, 1 to 900)),
        )
    }

    @Test
    fun `partial custom values allow untouched observed stock ceiling`() {
        val second = policy.copy(id = 1, supportedFrequencies = listOf(700, 900), observedMaxFreq = 1_100)
        assertEquals(
            mapOf(0 to 800, 1 to 1_100),
            mergeCustomValues(listOf(policy, second), mapOf(0 to 800), mapOf(0 to 1_000, 1 to 1_100)),
        )
    }

    @Test
    fun `partial custom values reject arbitrary unsupported baseline`() {
        val second = policy.copy(id = 1, supportedFrequencies = listOf(700, 900), observedMaxFreq = 1_100)
        assertNull(mergeCustomValues(listOf(policy, second), mapOf(0 to 800), mapOf(0 to 1_000, 1 to 1_050)))
    }

    @Test
    fun `partial custom values reject missing baseline`() {
        val second = policy.copy(id = 1)
        assertNull(mergeCustomValues(listOf(policy, second), mapOf(0 to 800), emptyMap()))
    }

    private fun profile(
        id: String,
        values: Map<Int, Int>,
        source: ProfileSource = ProfileSource.USER,
    ) = PerformanceProfile(id = id, name = id, maxFrequencies = values, source = source)
}
