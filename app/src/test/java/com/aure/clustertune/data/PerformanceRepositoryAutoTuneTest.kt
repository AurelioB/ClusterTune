package com.aure.clustertune.data

import com.aure.clustertune.autotune.AdaptiveCpuPolicy
import com.aure.clustertune.autotune.AdaptiveFrequencyCeilings
import com.aure.clustertune.autotune.AdaptiveFrequencyController
import com.aure.clustertune.autotune.AdaptiveGpuDomain
import com.aure.clustertune.autotune.AdaptiveTuneConfig
import com.aure.clustertune.autotune.AdaptiveTuneEnvelope
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.EffectiveProfileSource
import com.aure.clustertune.model.GpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.root.host.CpuDomain
import com.aure.clustertune.root.host.GpuDomain
import com.aure.clustertune.root.host.HostAutoSessionSnapshot
import com.aure.clustertune.root.host.HostAutoSessionStatus
import com.aure.clustertune.root.host.HostAutoTelemetry
import com.aure.clustertune.root.host.HostCapabilities
import com.aure.clustertune.root.host.HostState
import com.aure.clustertune.root.host.HostThermalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceRepositoryAutoTuneTest {
    @Test
    fun `adaptive envelope uses live base caps and choices strictly above live minimums`() {
        val policies = listOf(
            cpuPolicy(id = 0, supported = listOf(300, 600, 900, 1_200)),
            cpuPolicy(id = 4, supported = listOf(400, 800, 1_600)),
        )
        val gpuPolicy = gpuPolicy(supported = listOf(300, 700, 1_000))
        val capabilities = HostCapabilities(
            cpus = listOf(
                hostCpu(id = "policy0", supported = listOf(200, 600, 1_000, 1_400)),
                hostCpu(id = "policy4", supported = listOf(400, 900, 1_800)),
            ),
            gpu = hostGpu(supported = listOf(200, 500, 900)),
        )

        val envelope = buildAdaptiveEnvelope(
            policies = policies,
            gpuPolicy = gpuPolicy,
            capabilities = capabilities,
            hostState = HostState(
                cpuMax = listOf(1_000, 1_500),
                cpuMin = listOf(200, 400),
                gpuMax = 700,
                gpuMin = 200,
            ),
        )

        assertEquals(1_000L, envelope.cpuPolicies[0].baseCeilingKHz)
        assertEquals(listOf(600L, 1_000L), envelope.cpuPolicies[0].availableCeilingsKHz)
        assertEquals(1_500L, envelope.cpuPolicies[1].baseCeilingKHz)
        assertEquals(listOf(900L), envelope.cpuPolicies[1].availableCeilingsKHz)
        assertEquals(700L, envelope.gpu?.baseCeilingHz)
        assertEquals(listOf(500L), envelope.gpu?.availableCeilingsHz)
    }

    @Test
    fun `adaptive envelope rejects changed topology and missing live base caps`() {
        val policy = cpuPolicy(id = 0, supported = listOf(300, 600))
        val capabilities = HostCapabilities(listOf(hostCpu("policy0", listOf(300, 600))), gpu = null)

        assertThrows(IllegalArgumentException::class.java) {
            buildAdaptiveEnvelope(
                emptyList(),
                null,
                capabilities,
                HostState(cpuMax = listOf(600), cpuMin = listOf(300), gpuMax = null),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            buildAdaptiveEnvelope(
                listOf(policy),
                null,
                capabilities,
                HostState(cpuMax = listOf(-1), cpuMin = listOf(300), gpuMax = null),
            )
        }
    }

    @Test
    fun `adaptive topology maps reordered host domains by exact identity and path`() {
        val policy0 = cpuPolicy(id = 0, supported = listOf(300, 600))
        val policy4 = cpuPolicy(id = 4, supported = listOf(400, 800))
        val capabilities = HostCapabilities(
            cpus = listOf(
                hostCpu("policy4", listOf(400, 800)),
                hostCpu("policy0", listOf(300, 600)).copy(supportedFrequencies = emptyList()),
            ),
            gpu = null,
        )
        val state = HostState(cpuMax = listOf(800, 600), cpuMin = listOf(400, 300), gpuMax = null)

        validateAutoSessionBaseline(
            policies = listOf(policy0, policy4),
            gpuPolicy = null,
            capabilities = capabilities,
            hostState = state,
            expectedCpu = mapOf(0 to 600L, 4 to 800L),
            expectedGpu = null,
        )
        val envelope = buildAdaptiveEnvelope(listOf(policy0, policy4), null, capabilities, state)

        assertEquals(listOf(4, 0), envelope.cpuPolicies.map { it.policyId })
        assertEquals(listOf(600L), envelope.cpuPolicies[1].availableCeilingsKHz)
    }

    @Test
    fun `three-policy scope fixes the unique lowest hardware max independent of ids order and live caps`() {
        val policies = listOf(
            cpuPolicy(id = 2, supported = listOf(300, 600, 1_200), observedMax = 2_600),
            cpuPolicy(id = 8, supported = listOf(300, 600, 1_200), observedMax = 1_800),
            cpuPolicy(id = 9, supported = listOf(300, 600, 1_200), observedMax = 3_200),
        )
        val capabilities = HostCapabilities(
            cpus = listOf(
                hostCpu("policy9", listOf(300, 600, 1_200), stockMax = 3_200, observedMax = 3_200),
                hostCpu("policy8", listOf(300, 600, 1_200), stockMax = 1_800, observedMax = 1_800),
                hostCpu("policy2", listOf(300, 600, 1_200), stockMax = 2_600, observedMax = 2_600),
            ),
            gpu = null,
        )

        val envelope = buildAdaptiveEnvelope(
            policies = policies,
            gpuPolicy = null,
            capabilities = capabilities,
            hostState = HostState(
                cpuMax = listOf(1_200, 1_200, 1_200),
                cpuMin = listOf(300, 300, 300),
                gpuMax = null,
            ),
        )

        assertEquals(listOf(9, 8, 2), envelope.cpuPolicies.map { it.policyId })
        assertEquals(listOf(true, false, true), envelope.cpuPolicies.map { it.allowsAdaptiveAdjustment })
        assertEquals(
            mapOf(2 to 1_200L, 8 to 1_200L, 9 to 1_200L),
            AdaptiveFrequencyController(AdaptiveTuneConfig(targetFps = 60), envelope).baseCeilings.cpuKHz,
        )
        assertEquals(listOf(600L, 1_200L), envelope.cpuPolicies.single { it.policyId == 8 }.availableCeilingsKHz)
    }

    @Test
    fun `one and two policy topologies keep every CPU adjustable`() {
        val onePolicy = listOf(
            hostCpu("policy7", listOf(300, 1_800), stockMax = 1_800, observedMax = 1_800),
        )
        val twoPolicies = listOf(
            hostCpu("policy9", listOf(300, 3_200), stockMax = 3_200, observedMax = 3_200),
            hostCpu("policy7", listOf(300, 1_800), stockMax = 1_800, observedMax = 1_800),
        )

        assertEquals(setOf(7), adaptiveAdjustableCpuPolicyIds(onePolicy))
        assertEquals(setOf(9, 7), adaptiveAdjustableCpuPolicyIds(twoPolicies))
    }

    @Test
    fun `tied lowest hardware maxima fail open to every CPU policy`() {
        val domains = listOf(
            hostCpu("policy9", listOf(300, 3_200), stockMax = 3_200, observedMax = 3_200),
            hostCpu("policy2", listOf(300, 1_800), stockMax = 1_800, observedMax = 1_800),
            hostCpu("policy8", listOf(300, 1_800), stockMax = 1_800, observedMax = 1_800),
        )

        assertEquals(setOf(9, 2, 8), adaptiveAdjustableCpuPolicyIds(domains))
    }

    @Test
    fun `unknown hardware and observed maximum fails open without guessing a policy id`() {
        val domains = listOf(
            hostCpu("policy9", listOf(300, 3_200), stockMax = 3_200, observedMax = 3_200),
            hostCpu("policy42", listOf(300, 1_800), stockMax = 0, observedMax = -1),
            hostCpu("policy2", listOf(300, 2_600), stockMax = 2_600, observedMax = 2_600),
        )

        assertEquals(setOf(9, 42, 2), adaptiveAdjustableCpuPolicyIds(domains))
    }

    @Test
    fun `adaptive baseline rejects same-size replacement and changed checkpoint values`() {
        val policy = cpuPolicy(id = 0, supported = listOf(300, 600))
        val replacement = HostCapabilities(
            cpus = listOf(hostCpu("policy7", listOf(300, 600))),
            gpu = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            buildAdaptiveEnvelope(
                listOf(policy),
                null,
                replacement,
                HostState(cpuMax = listOf(600), cpuMin = listOf(300), gpuMax = null),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAutoSessionBaseline(
                policies = listOf(policy),
                gpuPolicy = null,
                capabilities = HostCapabilities(listOf(hostCpu("policy0", listOf(300, 600))), null),
                hostState = HostState(cpuMax = listOf(300), cpuMin = listOf(300), gpuMax = null),
                expectedCpu = mapOf(0 to 600L),
                expectedGpu = null,
            )
        }
    }

    @Test
    fun `adaptive envelope excludes ceilings below or equal to positive live minimums`() {
        val policy = cpuPolicy(id = 0, supported = listOf(200, 400, 600, 700, 800))
        val gpuPolicy = gpuPolicy(supported = listOf(200, 400, 600, 700, 900))
        val capabilities = HostCapabilities(
            cpus = listOf(hostCpu("policy0", listOf(200, 400, 600, 700, 800))),
            gpu = hostGpu(listOf(200, 400, 600, 700, 900)),
        )

        val envelope = buildAdaptiveEnvelope(
            policies = listOf(policy),
            gpuPolicy = gpuPolicy,
            capabilities = capabilities,
            hostState = HostState(
                cpuMax = listOf(800),
                cpuMin = listOf(600),
                gpuMax = 900,
                gpuMin = 600,
            ),
        )

        assertEquals(listOf(700L, 800L), envelope.cpuPolicies.single().availableCeilingsKHz)
        assertEquals(listOf(700L, 900L), envelope.gpu?.availableCeilingsHz)
    }

    @Test
    fun `adaptive envelope leaves domains inert at base when no step clears live minimum`() {
        val policy = cpuPolicy(id = 0, supported = listOf(200, 400, 800))
        val gpuPolicy = gpuPolicy(supported = listOf(200, 500, 900))
        val capabilities = HostCapabilities(
            cpus = listOf(hostCpu("policy0", listOf(200, 400, 800))),
            gpu = hostGpu(listOf(200, 500, 900)),
        )

        val envelope = buildAdaptiveEnvelope(
            policies = listOf(policy),
            gpuPolicy = gpuPolicy,
            capabilities = capabilities,
            hostState = HostState(
                cpuMax = listOf(800),
                cpuMin = listOf(800),
                gpuMax = 900,
                gpuMin = 1_000,
            ),
        )

        assertTrue(envelope.cpuPolicies.single().availableCeilingsKHz.isEmpty())
        assertTrue(envelope.gpu?.availableCeilingsHz?.isEmpty() == true)
        assertEquals(
            AdaptiveFrequencyCeilings(cpuKHz = mapOf(0 to 800L), gpuHz = 900L),
            AdaptiveFrequencyController(AdaptiveTuneConfig(targetFps = 60), envelope).baseCeilings,
        )
    }

    @Test
    fun `adaptive envelope retains every valid step above live minimum`() {
        val policy = cpuPolicy(id = 0, supported = listOf(300, 500, 600, 700, 800))
        val gpuPolicy = gpuPolicy(supported = listOf(300, 500, 600, 700, 900))
        val capabilities = HostCapabilities(
            cpus = listOf(hostCpu("policy0", listOf(300, 500, 600, 700, 800))),
            gpu = hostGpu(listOf(300, 500, 600, 700, 900)),
        )

        val envelope = buildAdaptiveEnvelope(
            policies = listOf(policy),
            gpuPolicy = gpuPolicy,
            capabilities = capabilities,
            hostState = HostState(
                cpuMax = listOf(800),
                cpuMin = listOf(500),
                gpuMax = 900,
                gpuMin = 500,
            ),
        )

        assertEquals(listOf(600L, 700L, 800L), envelope.cpuPolicies.single().availableCeilingsKHz)
        assertEquals(listOf(600L, 700L, 900L), envelope.gpu?.availableCeilingsHz)
    }

    @Test
    fun `adaptive baseline permits external minimum votes above the max ceiling`() {
        val policy = cpuPolicy(id = 0, supported = listOf(200, 400, 800))
        val gpuPolicy = gpuPolicy(supported = listOf(200, 500, 900))
        val capabilities = HostCapabilities(
            cpus = listOf(hostCpu("policy0", listOf(200, 400, 800))),
            gpu = hostGpu(listOf(200, 500, 900)),
        )
        val state = HostState(
            cpuMax = listOf(800),
            cpuMin = listOf(900),
            gpuMax = 900,
            gpuMin = 1_000,
        )

        validateAutoSessionBaseline(
            policies = listOf(policy),
            gpuPolicy = gpuPolicy,
            capabilities = capabilities,
            hostState = state,
            expectedCpu = mapOf(0 to 800L),
            expectedGpu = 900L,
        )

        val envelope = buildAdaptiveEnvelope(listOf(policy), gpuPolicy, capabilities, state)
        assertTrue(envelope.cpuPolicies.single().availableCeilingsKHz.isEmpty())
        assertTrue(envelope.gpu?.availableCeilingsHz?.isEmpty() == true)
    }

    @Test
    fun `unknown or nonpositive minimum leaves only the affected domain inert`() {
        val policy = cpuPolicy(id = 0, supported = listOf(200, 400, 800))
        val gpuPolicy = gpuPolicy(supported = listOf(200, 500, 900))
        val capabilities = HostCapabilities(
            cpus = listOf(hostCpu("policy0", listOf(200, 400, 800))),
            gpu = hostGpu(listOf(200, 500, 900)),
        )
        val unknownCpuState = HostState(
            cpuMax = listOf(800),
            cpuMin = listOf(-1),
            gpuMax = 900,
            gpuMin = 500,
        )

        validateAutoSessionBaseline(
            policies = listOf(policy),
            gpuPolicy = gpuPolicy,
            capabilities = capabilities,
            hostState = unknownCpuState,
            expectedCpu = mapOf(0 to 800L),
            expectedGpu = 900L,
        )

        val unknownCpu = buildAdaptiveEnvelope(listOf(policy), gpuPolicy, capabilities, unknownCpuState)
        assertTrue(unknownCpu.cpuPolicies.single().availableCeilingsKHz.isEmpty())
        assertEquals(listOf(900L), unknownCpu.gpu?.availableCeilingsHz)

        val unknownGpu = buildAdaptiveEnvelope(
            listOf(policy),
            gpuPolicy,
            capabilities,
            unknownCpuState.copy(cpuMin = listOf(400), gpuMin = null),
        )
        assertEquals(listOf(800L), unknownGpu.cpuPolicies.single().availableCeilingsKHz)
        assertTrue(unknownGpu.gpu?.availableCeilingsHz?.isEmpty() == true)

        val zeroMinimums = buildAdaptiveEnvelope(
            listOf(policy),
            gpuPolicy,
            capabilities,
            unknownCpuState.copy(cpuMin = listOf(0), gpuMin = 0),
        )
        assertTrue(zeroMinimums.cpuPolicies.single().availableCeilingsKHz.isEmpty())
        assertTrue(zeroMinimums.gpu?.availableCeilingsHz?.isEmpty() == true)
        assertEquals(
            AdaptiveFrequencyCeilings(cpuKHz = mapOf(0 to 800L), gpuHz = 900L),
            AdaptiveFrequencyController(AdaptiveTuneConfig(targetFps = 60), zeroMinimums).baseCeilings,
        )
    }

    @Test
    fun `adaptive ceiling validation accepts discrete choices and exact base caps`() {
        val envelope = envelopeWithGpu()

        validateAdaptiveCeilings(
            ceilings = AdaptiveFrequencyCeilings(
                cpuKHz = mapOf(0 to 800L, 4 to 400L),
                gpuHz = 700L,
            ),
            envelope = envelope,
        )
    }

    @Test
    fun `adaptive ceiling validation rejects mismatched unsupported and above-base CPU caps`() {
        val envelope = envelopeWithGpu()

        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(AdaptiveFrequencyCeilings(mapOf(0 to 600L), 700L), envelope)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(AdaptiveFrequencyCeilings(mapOf(0 to 700L, 4 to 400L), 700L), envelope)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(AdaptiveFrequencyCeilings(mapOf(0 to 1_000L, 4 to 400L), 700L), envelope)
        }
    }

    @Test
    fun `adaptive ceiling validation requires complete fixed efficiency policy at its base`() {
        val envelope = AdaptiveTuneEnvelope(
            cpuPolicies = listOf(
                AdaptiveCpuPolicy(
                    policyId = 8,
                    availableCeilingsKHz = listOf(300, 600, 900),
                    baseCeilingKHz = 900,
                    allowsAdaptiveAdjustment = false,
                ),
                AdaptiveCpuPolicy(
                    policyId = 9,
                    availableCeilingsKHz = listOf(600, 1_200),
                    baseCeilingKHz = 1_200,
                ),
            ),
        )

        validateAdaptiveCeilings(
            AdaptiveFrequencyCeilings(cpuKHz = mapOf(8 to 900L, 9 to 600L)),
            envelope,
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(
                AdaptiveFrequencyCeilings(cpuKHz = mapOf(8 to 600L, 9 to 600L)),
                envelope,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(
                AdaptiveFrequencyCeilings(cpuKHz = mapOf(9 to 600L)),
                envelope,
            )
        }
    }

    @Test
    fun `adaptive ceiling validation enforces GPU presence choices and base cap`() {
        val envelope = envelopeWithGpu()

        assertThrows(IllegalStateException::class.java) {
            validateAdaptiveCeilings(AdaptiveFrequencyCeilings(mapOf(0 to 800L, 4 to 400L)), envelope)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(AdaptiveFrequencyCeilings(mapOf(0 to 800L, 4 to 400L), 650L), envelope)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(AdaptiveFrequencyCeilings(mapOf(0 to 800L, 4 to 400L), 900L), envelope)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdaptiveCeilings(
                AdaptiveFrequencyCeilings(mapOf(0 to 800L, 4 to 400L), 500L),
                envelope.copy(gpu = null),
            )
        }
    }

    @Test
    fun `host telemetry maps frame timing CPU and GPU utilization`() {
        val sample = telemetry(
            timestampNanos = 123_456L,
            fpsMilli = 59_940,
            frameTimeP95Nanos = 18_500_000L,
            slowFrameRatioPermille = 37,
            cpuLoadPermille = listOf(825, null),
            gpuBusyPermille = 610,
        ).toAdaptiveTuneSample(listOf(cpuPolicy(0), cpuPolicy(4)))

        assertEquals(123_456L, sample.timestampNanos)
        assertEquals(59.94, sample.frames?.fps ?: error("missing frames"), 0.000_001)
        assertEquals(18.5, sample.frames?.p95FrameTimeMillis ?: error("missing p95"), 0.000_001)
        assertEquals(0.037, sample.frames?.slowFrameRatio ?: error("missing slow ratio"), 0.000_001)
        assertFalse(sample.frames?.isStale ?: true)
        assertEquals(0.825, sample.cpuLoad[0] ?: error("missing policy0 load"), 0.000_001)
        assertNull(sample.cpuLoad[4])
        assertEquals(0.61, sample.gpuBusy ?: error("missing GPU load"), 0.000_001)
    }

    @Test
    fun `frame telemetry preserves staleness and rejects low confidence samples`() {
        val stale = telemetry(frameStale = true, frameConfidencePermille = 250)
            .toAdaptiveTuneSample(listOf(cpuPolicy(0)))
        val lowConfidence = telemetry(frameStale = false, frameConfidencePermille = 249)
            .toAdaptiveTuneSample(listOf(cpuPolicy(0)))

        assertTrue(stale.frames?.isStale == true)
        assertNull(lowConfidence.frames)
    }

    @Test
    fun `utilization is clamped and thermal readings are observational`() {
        val withThermals = telemetry(
            cpuLoadPermille = listOf(-50, 1_200),
            gpuBusyPermille = 1_500,
            thermal = listOf(
                HostThermalReading("battery", 90_000L),
                HostThermalReading("soc", 75_000L),
                HostThermalReading("GPU", 100_000L),
            ),
        ).toAdaptiveTuneSample(listOf(cpuPolicy(0), cpuPolicy(4)))
        val withoutThermals = telemetry(
            cpuLoadPermille = listOf(-50, 1_200),
            gpuBusyPermille = 1_500,
        ).toAdaptiveTuneSample(listOf(cpuPolicy(0), cpuPolicy(4)))

        assertEquals(0.0, withThermals.cpuLoad[0] ?: error("missing policy0 load"), 0.0)
        assertEquals(1.0, withThermals.cpuLoad[4] ?: error("missing policy4 load"), 0.0)
        assertEquals(1.0, withThermals.gpuBusy ?: error("missing GPU load"), 0.0)
        assertEquals(withoutThermals, withThermals)
    }

    @Test
    fun `Auto Tune apply accepts exact upward physical Stock aliases only`() {
        val cpuDomains = listOf(
            hostCpu("policy0", listOf(200, 400, 800, 1_000)).copy(
                stockMax = 1_000,
                selectableMax = 800,
            ),
            hostCpu("policy4", listOf(300, 600, 900, 1_200)),
        )
        val gpuDomain = hostGpu(listOf(300, 600, 900, 1_000)).copy(
            stockMax = 1_000,
            selectableMax = 900,
        )
        val request = com.aure.clustertune.root.host.ApplyRequest(
            cpuMax = listOf(400, 900),
            gpuMax = 600,
            resetToStock = false,
        )

        assertTrue(
            autoTuneApplyStateIsAccepted(
                request,
                HostState(cpuMax = listOf(400, 900), gpuMax = 600),
                cpuDomains,
                gpuDomain,
            ),
        )
        assertTrue(
            autoTuneApplyStateIsAccepted(
                request,
                HostState(cpuMax = listOf(800, 1_200), gpuMax = 1_000),
                cpuDomains,
                gpuDomain,
            ),
        )
        listOf(
            HostState(cpuMax = listOf(700, 900), gpuMax = 600),
            HostState(cpuMax = listOf(300, 900), gpuMax = 600),
            HostState(cpuMax = listOf(-1, 900), gpuMax = 600),
            HostState(cpuMax = listOf(400, 900), gpuMax = 800),
            HostState(cpuMax = listOf(400), gpuMax = 600),
        ).forEach { state ->
            assertFalse(autoTuneApplyStateIsAccepted(request, state, cpuDomains, gpuDomain))
        }
    }

    @Test
    fun `known restoration requires exact terminal identity and completed attempt`() {
        validateAutoTuneRestoration(
            restorationSnapshot(),
            sessionId = "session-1",
            hostEpoch = 7L,
        )

        listOf(
            restorationSnapshot(sessionId = "other"),
            restorationSnapshot(hostEpoch = 8L),
            restorationSnapshot(status = HostAutoSessionStatus.STALE),
            restorationSnapshot(restorationAttempted = false),
            restorationSnapshot(restorationComplete = false),
            restorationSnapshot(status = HostAutoSessionStatus.RESTORE_FAILED, restorationComplete = false),
        ).forEach { snapshot ->
            assertThrows(IllegalStateException::class.java) {
                validateAutoTuneRestoration(snapshot, "session-1", 7L)
            }
        }
    }

    @Test
    fun `global restoration accepts completed terminal or idempotent no-session envelope`() {
        validateGlobalAutoTuneRestoration(restorationSnapshot())
        validateGlobalAutoTuneRestoration(
            restorationSnapshot(
                sessionId = null,
                restorationAttempted = false,
            ),
        )
        validateGlobalAutoTuneRestoration(
            restorationSnapshot(status = HostAutoSessionStatus.EXPIRED),
        )

        listOf(
            restorationSnapshot(status = HostAutoSessionStatus.ACTIVE, restorationComplete = false),
            restorationSnapshot(status = HostAutoSessionStatus.RESTORE_FAILED, restorationComplete = false),
            restorationSnapshot(restorationAttempted = false),
            restorationSnapshot(sessionId = null, restorationAttempted = true),
            restorationSnapshot(sessionId = null, status = HostAutoSessionStatus.EXPIRED, restorationAttempted = false),
        ).forEach { snapshot ->
            assertThrows(IllegalStateException::class.java) {
                validateGlobalAutoTuneRestoration(snapshot)
            }
        }
    }

    @Test
    fun `typed unsupported start without a session does not request global cleanup`() {
        assertFalse(
            autoStartSnapshotNeedsCleanup(
                HostAutoSessionSnapshot(
                    sessionId = null,
                    hostEpoch = 7L,
                    status = HostAutoSessionStatus.UNSUPPORTED,
                    targetFps = 0,
                    restorationComplete = true,
                    message = "telemetry unavailable",
                ),
            ),
        )
        assertTrue(autoStartSnapshotNeedsCleanup(restorationSnapshot()))
        assertTrue(
            autoStartSnapshotNeedsCleanup(
                restorationSnapshot(
                    sessionId = null,
                    status = HostAutoSessionStatus.RESTORE_FAILED,
                    restorationComplete = false,
                ),
            ),
        )
    }

    @Test
    fun `normal identity is resolved from persisted selection without hardware state`() {
        val profile = PerformanceProfile(
            id = "balanced",
            name = "Balanced",
            maxFrequencies = mapOf(0 to 600),
            source = ProfileSource.USER,
        )
        val named = resolveNormalProfileIdentity("balanced", listOf(profile), emptyList())
        val historyFallback = resolveNormalProfileIdentity(
            "bundled",
            emptyList(),
            listOf(ProfileSwitchHistoryEntry(1L, "bundled", "Battery", "test")),
        )
        val manual = resolveNormalProfileIdentity(null, emptyList(), emptyList())
        val stock = resolveNormalProfileIdentity(ProfileStateResolver.STOCK_PROFILE_ID, emptyList(), emptyList())

        assertEquals(PerformanceRepository.NormalProfileIdentity("balanced", "Balanced", EffectiveProfileSource.NORMAL), named)
        assertEquals("Battery", historyFallback.profileName)
        assertEquals(EffectiveProfileSource.NORMAL, historyFallback.source)
        assertEquals(ProfileStateResolver.MANUAL_PROFILE_ID, manual.profileId)
        assertEquals(EffectiveProfileSource.MANUAL, manual.source)
        assertEquals("Stock", stock.profileName)
        assertEquals(EffectiveProfileSource.STOCK, stock.source)
    }

    private fun envelopeWithGpu() = AdaptiveTuneEnvelope(
        cpuPolicies = listOf(
            AdaptiveCpuPolicy(policyId = 0, availableCeilingsKHz = listOf(300, 600, 1_000), baseCeilingKHz = 800),
            AdaptiveCpuPolicy(policyId = 4, availableCeilingsKHz = listOf(200, 400), baseCeilingKHz = 600),
        ),
        gpu = AdaptiveGpuDomain(
            id = "gpu",
            availableCeilingsHz = listOf(300, 500, 900),
            baseCeilingHz = 700,
        ),
    )

    private fun cpuPolicy(
        id: Int,
        supported: List<Int> = listOf(300, 600, 800),
        observedMax: Int = supported.last(),
    ) = CpuPolicyInfo(
        id = id,
        policyPath = "/sys/policy$id",
        scalingMaxPath = "/sys/policy$id/max",
        currentMaxFreq = supported.last(),
        selectableMaxFreq = supported.last(),
        observedMaxFreq = observedMax,
        minFreq = supported.first(),
        supportedFrequencies = supported,
        scalingMinPath = "/sys/policy$id/min",
    )

    private fun gpuPolicy(supported: List<Int>) = GpuPolicyInfo(
        policyPath = "/sys/gpu",
        maxFrequencyPath = "/sys/gpu/max",
        currentMaxFrequencyHz = supported.last(),
        selectableMaxFrequencyHz = supported.last(),
        observedMaxFrequencyHz = supported.last(),
        supportedFrequenciesHz = supported,
    )

    private fun hostCpu(
        id: String,
        supported: List<Long>,
        stockMax: Long = supported.last(),
        observedMax: Long = stockMax,
    ) = CpuDomain(
        id = id,
        minPath = "/sys/$id/min",
        maxPath = "/sys/$id/max",
        curPath = null,
        minimumCandidates = listOf(supported.first()),
        supportedFrequencies = supported,
        stockMax = stockMax,
        observedMax = observedMax,
        observedMin = supported.first(),
    )

    private fun hostGpu(supported: List<Long>) = GpuDomain(
        id = "gpu",
        minPath = "/sys/gpu/min",
        maxPath = "/sys/gpu/max",
        curPath = null,
        supportedFrequencies = supported,
        stockMax = supported.last(),
        observedMax = supported.last(),
        observedMin = supported.first(),
    )

    private fun telemetry(
        timestampNanos: Long = 1L,
        frameConfidencePermille: Int = 1_000,
        fpsMilli: Int? = 60_000,
        frameTimeP95Nanos: Long? = 16_666_667L,
        slowFrameRatioPermille: Int? = 10,
        frameStale: Boolean = false,
        cpuLoadPermille: List<Int?> = listOf(500),
        gpuBusyPermille: Int? = 500,
        thermal: List<HostThermalReading> = emptyList(),
    ) = HostAutoTelemetry(
        sequence = 1L,
        timestampNanos = timestampNanos,
        frameBackend = "surfaceflinger-latency",
        frameConfidencePermille = frameConfidencePermille,
        frameLayer = "com.example.game/MainActivity",
        frameCount = 120,
        fpsMilli = fpsMilli,
        frameTimeP95Nanos = frameTimeP95Nanos,
        slowFrameRatioPermille = slowFrameRatioPermille,
        frameStale = frameStale,
        cpuLoadPermille = cpuLoadPermille,
        cpuClockKHz = emptyList(),
        gpuBusyPermille = gpuBusyPermille,
        gpuClockHz = null,
        thermal = thermal,
    )

    private fun restorationSnapshot(
        sessionId: String? = "session-1",
        hostEpoch: Long = 7L,
        status: HostAutoSessionStatus = HostAutoSessionStatus.STOPPED,
        restorationAttempted: Boolean = true,
        restorationComplete: Boolean = true,
    ) = HostAutoSessionSnapshot(
        sessionId = sessionId,
        hostEpoch = hostEpoch,
        status = status,
        targetFps = 60,
        restorationAttempted = restorationAttempted,
        restorationComplete = restorationComplete,
    )
}
