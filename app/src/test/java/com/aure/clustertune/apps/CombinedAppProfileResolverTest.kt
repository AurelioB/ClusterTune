package com.aure.clustertune.apps

import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import org.junit.Assert.*
import org.junit.Test

class CombinedAppProfileResolverTest {
    private fun target(
        pkg: String,
        id: String,
        cpu: Map<Int, Int> = emptyMap(),
        gpu: Int? = null,
    ) = VisibleAppProfileTarget(pkg, profileId = id, profileName = id, cpuMaxFrequencies = cpu, gpuMaxFrequencyHz = gpu)

    @Test fun singleTargetPreservesIdentity() {
        val result = CombinedAppProfileResolver.resolve(listOf(target("chrome", "balanced", mapOf(0 to 100))))
        assertEquals(listOf("chrome"), result.contributors.map { it.packageName })
        assertEquals(mapOf(0 to 100), result.cpuMaxFrequencies)
        assertEquals(CombinedProfilePresentation("balanced", "balanced", false), result.presentation)
    }

    @Test fun twoDisplaysCombineAndAreInputOrderIndependent() {
        val a = target("chrome", "web", mapOf(0 to 100), 300)
        val b = target("retroarch", "game", mapOf(0 to 200), 500)
        val first = CombinedAppProfileResolver.resolve(listOf(a, b))
        val second = CombinedAppProfileResolver.resolve(listOf(b, a))
        assertEquals(first, second)
        assertEquals(CombinedProfilePresentation(null, "Combined", true), first.presentation)
    }

    @Test fun componentwiseCrossoverUsesEachHighestValue() {
        val result = CombinedAppProfileResolver.resolve(listOf(
            target("a", "one", mapOf(0 to 100, 1 to 300), 400),
            target("b", "two", mapOf(0 to 250, 1 to 200), 350),
        ))
        assertEquals(mapOf(0 to 250, 1 to 300), result.cpuMaxFrequencies)
        assertEquals(400, result.gpuMaxFrequencyHz)
    }

    @Test fun stockNumericValueWinsLikeAnyOtherMaximum() {
        val result = CombinedAppProfileResolver.resolve(listOf(
            target("game", "low", mapOf(0 to 100), 200),
            target("stock", "stock", mapOf(0 to 999), 999),
        ))
        assertEquals(mapOf(0 to 999), result.cpuMaxFrequencies)
        assertEquals(999, result.gpuMaxFrequencyHz)
    }

    @Test fun missingDomainsDoNotConstrainOtherTargets() {
        val result = CombinedAppProfileResolver.resolve(listOf(
            target("cpu", "cpu", mapOf(0 to 123)),
            target("gpu", "gpu", gpu = 456),
        ))
        assertEquals(mapOf(0 to 123), result.cpuMaxFrequencies)
        assertEquals(456, result.gpuMaxFrequencyHz)
    }

    @Test fun duplicatePackageContributesOnceAndCombinesReports() {
        val reports = listOf(
            target("same", "z", mapOf(0 to 100)),
            target("same", "a", mapOf(0 to 200)),
            target("other", "x", mapOf(1 to 300)),
        )
        val result = CombinedAppProfileResolver.resolve(reports)
        val reversed = CombinedAppProfileResolver.resolve(reports.reversed())
        assertEquals(listOf("other", "same"), result.contributors.map { it.packageName })
        assertEquals(result.contributors, reversed.contributors)
        assertEquals("a", result.contributors.last().profileId)
        assertEquals(mapOf(0 to 200, 1 to 300), result.cpuMaxFrequencies)
    }

    @Test fun noTargetsReturnsEmptyResult() {
        val result = CombinedAppProfileResolver.resolve(emptyList())
        assertTrue(result.contributors.isEmpty())
        assertTrue(result.cpuMaxFrequencies.isEmpty())
        assertNull(result.presentation)
    }

    @Test fun exactKnownProfileGetsKnownPresentation() {
        val known = PerformanceProfile("p", "Known", mapOf(0 to 200, 1 to 300), ProfileSource.USER, gpuMaxFrequencyHz = 500)
        val result = CombinedAppProfileResolver.resolve(listOf(
            target("a", "one", mapOf(0 to 200), 500),
            target("b", "two", mapOf(1 to 300)),
        ), listOf(known))
        assertEquals(CombinedProfilePresentation("p", "Known", true), result.presentation)
    }
}
