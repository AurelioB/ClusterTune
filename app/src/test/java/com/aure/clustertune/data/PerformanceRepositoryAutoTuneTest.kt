package com.aure.clustertune.data

import com.aure.clustertune.autotune.AdaptiveCpuPolicy
import com.aure.clustertune.autotune.AdaptiveFrequencyCeilings
import com.aure.clustertune.autotune.AdaptiveGpuDomain
import com.aure.clustertune.autotune.AdaptiveThermalState
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
    fun `adaptive envelope uses live base caps and only choices at or below them`() {
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
        assertEquals(listOf(200L, 600L, 1_000L), envelope.cpuPolicies[0].availableCeilingsKHz)
        assertEquals(1_500L, envelope.cpuPolicies[1].baseCeilingKHz)
        assertEquals(listOf(400L, 900L), envelope.cpuPolicies[1].availableCeilingsKHz)
        assertEquals(700L, envelope.gpu?.baseCeilingHz)
        assertEquals(listOf(200L, 500L), envelope.gpu?.availableCeilingsHz)
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
        assertEquals(listOf(300L, 600L), envelope.cpuPolicies[1].availableCeilingsKHz)
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
    fun `adaptive envelope excludes choices below live minimums`() {
        val policy = cpuPolicy(id = 0, supported = listOf(200, 400, 600, 800))
        val gpuPolicy = gpuPolicy(supported = listOf(200, 400, 600, 900))
        val capabilities = HostCapabilities(
            cpus = listOf(hostCpu("policy0", listOf(200, 400, 600, 800))),
            gpu = hostGpu(listOf(200, 400, 600, 900)),
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

        assertEquals(listOf(600L, 800L), envelope.cpuPolicies.single().availableCeilingsKHz)
        assertEquals(listOf(600L, 900L), envelope.gpu?.availableCeilingsHz)
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
            thermal = listOf(
                HostThermalReading("cpu-thermal", 74_999L),
                HostThermalReading("battery", 99_000L),
            ),
        ).toAdaptiveTuneSample(listOf(cpuPolicy(0), cpuPolicy(4)))

        assertEquals(123_456L, sample.timestampNanos)
        assertEquals(59.94, sample.frames?.fps ?: error("missing frames"), 0.000_001)
        assertEquals(18.5, sample.frames?.p95FrameTimeMillis ?: error("missing p95"), 0.000_001)
        assertEquals(0.037, sample.frames?.slowFrameRatio ?: error("missing slow ratio"), 0.000_001)
        assertFalse(sample.frames?.isStale ?: true)
        assertEquals(0.825, sample.cpuLoad[0] ?: error("missing policy0 load"), 0.000_001)
        assertNull(sample.cpuLoad[4])
        assertEquals(0.61, sample.gpuBusy ?: error("missing GPU load"), 0.000_001)
        assertEquals(AdaptiveThermalState.NORMAL, sample.thermalState)
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
    fun `utilization is clamped and thermal thresholds use relevant sensors`() {
        val moderate = telemetry(
            cpuLoadPermille = listOf(-50, 1_200),
            gpuBusyPermille = 1_500,
            thermal = listOf(
                HostThermalReading("battery", 90_000L),
                HostThermalReading("soc", 75_000L),
                HostThermalReading("gpu-invalid", 300_001L),
            ),
        ).toAdaptiveTuneSample(listOf(cpuPolicy(0), cpuPolicy(4)))
        val severe = telemetry(thermal = listOf(HostThermalReading("GPU", 85_000L)))
            .toAdaptiveTuneSample(listOf(cpuPolicy(0)))

        assertEquals(0.0, moderate.cpuLoad[0] ?: error("missing policy0 load"), 0.0)
        assertEquals(1.0, moderate.cpuLoad[4] ?: error("missing policy4 load"), 0.0)
        assertEquals(1.0, moderate.gpuBusy ?: error("missing GPU load"), 0.0)
        assertEquals(AdaptiveThermalState.MODERATE, moderate.thermalState)
        assertEquals(AdaptiveThermalState.SEVERE, severe.thermalState)
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

    private fun cpuPolicy(id: Int, supported: List<Int> = listOf(300, 600, 800)) = CpuPolicyInfo(
        id = id,
        policyPath = "/sys/policy$id",
        scalingMaxPath = "/sys/policy$id/max",
        currentMaxFreq = supported.last(),
        selectableMaxFreq = supported.last(),
        observedMaxFreq = supported.last(),
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

    private fun hostCpu(id: String, supported: List<Long>) = CpuDomain(
        id = id,
        minPath = "/sys/$id/min",
        maxPath = "/sys/$id/max",
        curPath = null,
        minimumCandidates = listOf(supported.first()),
        supportedFrequencies = supported,
        stockMax = supported.last(),
        observedMax = supported.last(),
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
