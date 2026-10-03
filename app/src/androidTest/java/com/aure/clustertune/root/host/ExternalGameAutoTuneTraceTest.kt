package com.aure.clustertune.root.host

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.autotune.AdaptiveActuator
import com.aure.clustertune.autotune.AdaptiveCpuPolicy
import com.aure.clustertune.autotune.AdaptiveFrameMetrics
import com.aure.clustertune.autotune.AdaptiveFrequencyCeilings
import com.aure.clustertune.autotune.AdaptiveFrequencyController
import com.aure.clustertune.autotune.AdaptiveGpuDomain
import com.aure.clustertune.autotune.AdaptiveTuneConfig
import com.aure.clustertune.autotune.AdaptiveTuneDecision
import com.aure.clustertune.autotune.AdaptiveTuneEnvelope
import com.aure.clustertune.autotune.AdaptiveTuneReason
import com.aure.clustertune.autotune.AdaptiveTuneSample
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.adaptiveAdjustableCpuPolicyIds
import com.aure.clustertune.data.autoTuneApplyStateIsAccepted
import com.aure.clustertune.root.PrivilegedExecutionResolver
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real-game trace. Normal connected-test runs skip it unless externalPackage is supplied.
 *
 * This owns the privileged host session and controller used by production, but deliberately
 * bypasses persisted-profile resolution and the foreground coordinator. It is a controller/host
 * integration trace, not an end-to-end automation test.
 */
@RunWith(AndroidJUnit4::class)
class ExternalGameAutoTuneTraceTest {
    @Test
    fun traceExternalGameUntilBoundaryOrTimeout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val targetPackage = arguments.getString("externalPackage")
        assumeTrue("externalPackage was not supplied", !targetPackage.isNullOrBlank())
        val packageName = requireNotNull(targetPackage)

        val targetFps = arguments.getString("targetFps")?.toIntOrNull() ?: 60
        val sampleCount = arguments.getString("sampleCount")?.toIntOrNull()?.coerceIn(10, 360) ?: 300
        val sampleIntervalMs = arguments.getString("sampleIntervalMs")
            ?.toLongOrNull()
            ?.coerceIn(500L, 2_000L)
            ?: 1_000L
        val stopAfterRegressionSamples = arguments.getString("stopAfterRegressionSamples")
            ?.toIntOrNull()
            ?.coerceIn(0, 120)
            ?: 15
        val requireBoundary = arguments.getString("requireBoundary")?.toBooleanStrictOrNull() ?: true
        val maxUnhealthyWithHeadroomSamples = arguments
            .getString("maxUnhealthyWithHeadroomSamples")
            ?.toIntOrNull()
            ?.coerceIn(0, 120)
            ?: 0
        val context = instrumentation.targetContext
        val assignedPackages = runBlocking {
            ProfileStorage(context).appProfileAssignments.first().map { it.packageName }.toSet()
        }
        assumeTrue(
            "the production foreground coordinator owns an assignment for $packageName",
            packageName !in assignedPackages,
        )
        val traceFile = File(context.cacheDir, TRACE_FILE_NAME)
        val resolver = PrivilegedExecutionResolver.default(context)
        val client = ClusterTuneHostClient(context, resolver)
        var handle: HostAutoSessionHandle? = null
        var safeToStopHost = false
        var traceFailure: Throwable? = null

        traceFile.bufferedWriter().use { writer ->
            fun emit(value: JSONObject, flush: Boolean = true) {
                writer.append(value.toString()).append('\n')
                if (flush) writer.flush()
            }

            try {
                client.ensureStarted(5_000L).getOrThrow()
                val method = requireNotNull(client.selectedMethodId)
                val snapshot = client.readSnapshot().getOrThrow()
                val autoCapabilities = client.readAutoCapabilities().getOrThrow()
                assertTrue(autoCapabilities.autoSessionSupported)
                val preflight = client.readAutoTelemetry().getOrThrow()
                assumeTrue("another automatic session is active", preflight.status != HostAutoSessionStatus.ACTIVE)
                assumeTrue(
                    "a previous automatic session has not restored",
                    preflight.status != HostAutoSessionStatus.RESTORE_FAILED,
                )
                safeToStopHost = true

                emit(
                    JSONObject()
                        .put("event", "start")
                        .put("wallTimeMillis", System.currentTimeMillis())
                        .put("package", packageName)
                        .put("targetFps", targetFps)
                        .put("sampleCount", sampleCount)
                        .put("sampleIntervalMs", sampleIntervalMs)
                        .put("method", method)
                        .put("hostEpoch", snapshot.epoch)
                        .put("autoCapabilities", autoCapabilities.toJson())
                        .put("hardwareCapabilities", snapshot.capabilities.toJson())
                        .put("preStartState", snapshot.state.toJson()),
                )

                val started = client.startAutoSession(
                    AutoSessionRequest(
                        packageName = packageName,
                        targetFps = targetFps,
                        heartbeatTimeoutMs = 15_000L,
                    ),
                ).getOrThrow()
                assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
                val sessionHandle = requireNotNull(started.handle)
                handle = sessionHandle
                val baseline = requireNotNull(started.state)
                val envelope = adaptiveEnvelope(snapshot.capabilities, baseline)
                val fixedCpuCeilings = envelope.cpuPolicies
                    .filterNot(AdaptiveCpuPolicy::allowsAdaptiveAdjustment)
                    .associate { it.policyId to it.baseCeilingKHz }
                val controller = AdaptiveFrequencyController(
                    config = AdaptiveTuneConfig(targetFps = targetFps),
                    envelope = envelope,
                )
                assertEquals(
                    snapshot.capabilities.cpus.map { it.policyId() }.toSet(),
                    controller.baseCeilings.cpuKHz.keys,
                )
                assertFixedCpuCeilings(fixedCpuCeilings, controller.baseCeilings)
                emit(
                    JSONObject()
                        .put("event", "session_active")
                        .put("sessionId", sessionHandle.sessionId)
                        .put("hostEpoch", sessionHandle.hostEpoch)
                        .put("baseline", baseline.toJson())
                        .put("baseCeilings", controller.baseCeilings.toJson())
                        .put("fixedCpuPolicyIds", JSONArray(fixedCpuCeilings.keys.toList())),
                )

                var afterSequence = -1L
                var trimCount = 0
                var acceptedTrimCount = 0
                var regressionCount = 0
                var recoveryCount = 0
                var floorCount = 0
                var freshSamples = 0
                var staleSamples = 0
                var samplesAfterFirstRegression = 0
                var minimumFpsMilli: Int? = null
                var maximumFpsMilli: Int? = null
                var expectedFrameBackend: String? = null
                var expectedFrameLayer: String? = null
                var pendingTrialReason: AdaptiveTuneReason? = null
                var minimumClampSamples = 0
                var unhealthySamples = 0
                var unhealthyAtBaseSamples = 0
                var consecutiveUnhealthySamples = 0
                var maxConsecutiveUnhealthySamples = 0
                var consecutiveUnhealthyWithHeadroomSamples = 0
                var maxConsecutiveUnhealthyWithHeadroomSamples = 0
                var upwardResetSamples = 0
                var upwardResetEvents = 0
                var upwardResetReassertions = 0
                var postApplyUpwardResetEvents = 0
                var previousUpwardResetDomains = emptySet<String>()
                val reasons = linkedMapOf<String, Int>()

                for (index in 0 until sampleCount + MAX_TRIAL_SETTLE_SAMPLES) {
                    Thread.sleep(sampleIntervalMs)
                    val heartbeat = client.heartbeatAutoSession(sessionHandle).getOrThrow()
                    check(heartbeat.status == HostAutoSessionStatus.ACTIVE) {
                        "automatic session stopped during heartbeat: ${heartbeat.status}: ${heartbeat.message}"
                    }
                    val observedState = requireNotNull(heartbeat.state) {
                        "automatic-session heartbeat returned no hardware state"
                    }
                    check(observedState.cpuMin == baseline.cpuMin && observedState.gpuMin == baseline.gpuMin) {
                        "a live minimum changed before sampling: " +
                            "cpu=${observedState.cpuMin} gpu=${observedState.gpuMin}"
                    }
                    val intendedBeforeSample = controller.currentCeilings()
                    val upwardResetDomains = buildSet {
                        snapshot.capabilities.cpus.forEachIndexed { cpuIndex, domain ->
                            val intended = intendedBeforeSample.cpuKHz.getValue(domain.policyId())
                            val actual = observedState.cpuMax[cpuIndex]
                            check(actual >= intended) {
                                "an external lower CPU ceiling remained active for ${domain.id}: $intended->$actual"
                            }
                            if (actual > intended) add(domain.id)
                        }
                        snapshot.capabilities.gpu?.let { domain ->
                            val intended = requireNotNull(intendedBeforeSample.gpuHz)
                            val actual = requireNotNull(observedState.gpuMax)
                            check(actual >= intended) {
                                "an external lower GPU ceiling remained active for ${domain.id}: $intended->$actual"
                            }
                            if (actual > intended) add(domain.id)
                        }
                    }
                    if (upwardResetDomains.isNotEmpty()) upwardResetSamples++
                    if (upwardResetDomains.any { it !in previousUpwardResetDomains }) upwardResetEvents++
                    val sampled = client.readAutoTelemetry(sessionHandle, afterSequence).getOrThrow()
                    check(sampled.status == HostAutoSessionStatus.ACTIVE) {
                        "automatic session stopped during telemetry: ${sampled.status}: ${sampled.message}"
                    }
                    val telemetry = requireNotNull(sampled.telemetry)
                    check(telemetry.sequence > afterSequence) { "telemetry sequence did not advance" }
                    afterSequence = telemetry.sequence
                    val sample = telemetry.toAdaptiveTuneSample(snapshot.capabilities)
                    val decision = controller.step(sample)
                    assertEquals(
                        snapshot.capabilities.cpus.map { it.policyId() }.toSet(),
                        decision.ceilings.cpuKHz.keys,
                    )
                    assertFixedCpuCeilings(fixedCpuCeilings, decision.ceilings)
                    reasons[decision.reason.name] = reasons.getOrDefault(decision.reason.name, 0) + 1
                    val usableFrames = !telemetry.frameStale &&
                        telemetry.frameConfidencePermille >= 250 &&
                        telemetry.fpsMilli?.let { it > 0 } == true
                    if (usableFrames) {
                        freshSamples++
                        val backend = requireNotNull(telemetry.frameBackend) { "usable frames lack a backend" }
                        val layer = requireNotNull(telemetry.frameLayer) { "usable frames lack a layer" }
                        check(packageName in layer) { "telemetry selected a non-target layer: $layer" }
                        expectedFrameBackend?.let { check(it == backend) { "frame backend changed from $it to $backend" } }
                        expectedFrameLayer?.let { check(it == layer) { "frame layer changed from $it to $layer" } }
                        expectedFrameBackend = backend
                        expectedFrameLayer = layer
                    } else {
                        staleSamples++
                    }
                    telemetry.fpsMilli?.takeIf { usableFrames }?.let { fps ->
                        minimumFpsMilli = minimumFpsMilli?.coerceAtMost(fps) ?: fps
                        maximumFpsMilli = maximumFpsMilli?.coerceAtLeast(fps) ?: fps
                    }
                    if (usableFrames && sample.frames?.isUnhealthy(controller.config) == true) {
                        unhealthySamples++
                        consecutiveUnhealthySamples++
                        maxConsecutiveUnhealthySamples = maxOf(
                            maxConsecutiveUnhealthySamples,
                            consecutiveUnhealthySamples,
                        )
                        if (decision.ceilings.hasHeadroom(controller.baseCeilings)) {
                            consecutiveUnhealthyWithHeadroomSamples++
                            maxConsecutiveUnhealthyWithHeadroomSamples = maxOf(
                                maxConsecutiveUnhealthyWithHeadroomSamples,
                                consecutiveUnhealthyWithHeadroomSamples,
                            )
                        } else {
                            unhealthyAtBaseSamples++
                            consecutiveUnhealthyWithHeadroomSamples = 0
                        }
                    } else if (usableFrames) {
                        consecutiveUnhealthySamples = 0
                        consecutiveUnhealthyWithHeadroomSamples = 0
                    }

                    var appliedState: HostState? = null
                    var postApplyUpwardResetDomains = emptySet<String>()
                    var reappliedUpwardResetDomains = emptySet<String>()
                    if (decision is AdaptiveTuneDecision.Apply) {
                        val request = decision.toApplyRequest(snapshot.capabilities)
                        val applied = client.applyAutoStep(sessionHandle, request).getOrThrow()
                        check(applied.status == HostAutoSessionStatus.ACTIVE) {
                            "automatic session stopped during apply: ${applied.status}: ${applied.message}"
                        }
                        appliedState = requireNotNull(applied.state)
                        check(
                            autoTuneApplyStateIsAccepted(
                                request = request,
                                state = appliedState,
                                cpuDomains = snapshot.capabilities.cpus,
                                gpuDomain = snapshot.capabilities.gpu,
                            ),
                        ) {
                            "privileged host returned an unrecognized maximum after apply: " +
                                "requested=${request.cpuMax}/${request.gpuMax} " +
                                "actual=${appliedState.cpuMax}/${appliedState.gpuMax}"
                        }
                        postApplyUpwardResetDomains = buildSet {
                            snapshot.capabilities.cpus.forEachIndexed { cpuIndex, domain ->
                                if (request.cpuMax[cpuIndex] != appliedState.cpuMax[cpuIndex]) add(domain.id)
                            }
                            snapshot.capabilities.gpu?.let { domain ->
                                if (request.gpuMax != appliedState.gpuMax) add(domain.id)
                            }
                        }
                        if (postApplyUpwardResetDomains.isNotEmpty()) postApplyUpwardResetEvents++
                        reappliedUpwardResetDomains = buildSet {
                            snapshot.capabilities.cpus.forEachIndexed { cpuIndex, domain ->
                                if (
                                    domain.id in upwardResetDomains &&
                                    observedState.cpuMax[cpuIndex] != request.cpuMax[cpuIndex] &&
                                    appliedState.cpuMax[cpuIndex] == request.cpuMax[cpuIndex]
                                ) {
                                    add(domain.id)
                                }
                            }
                            snapshot.capabilities.gpu?.let { domain ->
                                if (
                                    domain.id in upwardResetDomains &&
                                    observedState.gpuMax != request.gpuMax &&
                                    appliedState.gpuMax == request.gpuMax
                                ) {
                                    add(domain.id)
                                }
                            }
                        }
                        fixedCpuCeilings.forEach { (policyId, expectedCeiling) ->
                            val index = snapshot.capabilities.cpus.indexOfFirst { it.policyId() == policyId }
                            assertTrue("fixed CPU policy$policyId disappeared", index >= 0)
                            if (snapshot.capabilities.cpus[index].id !in postApplyUpwardResetDomains) {
                                assertEquals(
                                    "fixed CPU policy$policyId changed during Auto Tune",
                                    expectedCeiling,
                                    appliedState.cpuMax[index],
                                )
                            }
                        }
                        if (appliedState.cpuMin != baseline.cpuMin || appliedState.gpuMin != baseline.gpuMin) {
                            minimumClampSamples++
                            error(
                                "a live minimum changed during the automatic session: " +
                                    "cpu=${appliedState.cpuMin} gpu=${appliedState.gpuMin}",
                            )
                        }
                        if (reappliedUpwardResetDomains.isNotEmpty()) {
                            upwardResetReassertions++
                        }
                        if (decision.reason == AdaptiveTuneReason.EFFICIENCY_TRIM) {
                            trimCount++
                            pendingTrialReason = decision.reason
                        }
                        if (decision.reason == AdaptiveTuneReason.TRIAL_REGRESSION) regressionCount++
                        if (
                            decision.reason == AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY ||
                            decision.reason == AdaptiveTuneReason.GPU_BOTTLENECK_RECOVERY
                        ) {
                            recoveryCount++
                            pendingTrialReason = decision.reason
                        }
                    }
                    if (decision.reason == AdaptiveTuneReason.TRIAL_ACCEPTED) {
                        if (pendingTrialReason == AdaptiveTuneReason.EFFICIENCY_TRIM) {
                            acceptedTrimCount++
                        }
                        pendingTrialReason = null
                    }
                    if (decision.reason == AdaptiveTuneReason.TRIAL_REGRESSION) {
                        pendingTrialReason = null
                    }
                    val reachedPhysicalHealthyFloor =
                        decision.reason == AdaptiveTuneReason.HEALTHY_AT_FLOOR &&
                            upwardResetDomains.isEmpty()
                    if (reachedPhysicalHealthyFloor) floorCount++
                    if (regressionCount > 0) samplesAfterFirstRegression++

                    emit(
                        JSONObject()
                            .put("event", "sample")
                            .put("index", index + 1)
                            .put("wallTimeMillis", System.currentTimeMillis())
                            .put("telemetry", telemetry.toJson())
                            .put("observedState", observedState.toJson())
                            .put("intendedCeilingsBeforeSample", intendedBeforeSample.toJson())
                            .put("upwardResetDomains", JSONArray(upwardResetDomains.toList()))
                            .put(
                                "postApplyUpwardResetDomains",
                                JSONArray(postApplyUpwardResetDomains.toList()),
                            )
                            .put(
                                "reappliedUpwardResetDomains",
                                JSONArray(reappliedUpwardResetDomains.toList()),
                            )
                            .put("decision", decision.toJson())
                            .putNullable("appliedState", appliedState?.toJson()),
                        flush = decision is AdaptiveTuneDecision.Apply || (index + 1) % 10 == 0,
                    )

                    if (decision is AdaptiveTuneDecision.Stop) {
                        error("controller requested stop: ${decision.reason}")
                    }
                    if (reachedPhysicalHealthyFloor && pendingTrialReason == null) {
                        break
                    }
                    if (
                        regressionCount > 0 &&
                        samplesAfterFirstRegression >= stopAfterRegressionSamples &&
                        pendingTrialReason == null
                    ) {
                        break
                    }
                    previousUpwardResetDomains = if (appliedState != null) {
                        postApplyUpwardResetDomains
                    } else {
                        upwardResetDomains
                    }
                    if (index + 1 >= sampleCount && pendingTrialReason == null) break
                }

                val stopped = stopAndRestore(client, sessionHandle)
                val stoppedState = requireNotNull(stopped.state)
                val baselineRequest = ApplyRequest(
                    cpuMax = baseline.cpuMax,
                    gpuMax = baseline.gpuMax,
                    resetToStock = false,
                )
                assertTrue(
                    "stopped state did not preserve the checkpoint or a recognized OEM Stock reset",
                    restoredStateMatchesCheckpointOrPhysicalStock(
                        baseline = baselineRequest,
                        state = stoppedState,
                        capabilities = snapshot.capabilities,
                    ),
                )
                assertEquals(baseline.cpuMin, stoppedState.cpuMin)
                assertEquals(baseline.gpuMin, stoppedState.gpuMin)
                val restored = client.readSnapshot().getOrThrow().state
                assertTrue(
                    "live state did not preserve the checkpoint or a recognized OEM Stock reset",
                    restoredStateMatchesCheckpointOrPhysicalStock(
                        baseline = baselineRequest,
                        state = restored,
                        capabilities = snapshot.capabilities,
                    ),
                )
                assertEquals(baseline.cpuMin, restored.cpuMin)
                assertEquals(baseline.gpuMin, restored.gpuMin)
                assertTrue("no usable frame samples were recorded", freshSamples > 0)
                assertTrue("Auto Tune never attempted an efficiency trim", trimCount > 0)
                assertTrue("Auto Tune never accepted an efficiency trim", acceptedTrimCount > 0)
                assertTrue(
                    "measurement ended during an unresolved controller trial: $pendingTrialReason",
                    pendingTrialReason == null,
                )
                if (maxUnhealthyWithHeadroomSamples > 0) {
                    assertTrue(
                        "Auto Tune stayed unhealthy with unused headroom for " +
                            "$maxConsecutiveUnhealthyWithHeadroomSamples samples " +
                            "(limit $maxUnhealthyWithHeadroomSamples)",
                        maxConsecutiveUnhealthyWithHeadroomSamples <= maxUnhealthyWithHeadroomSamples,
                    )
                }
                if (requireBoundary) {
                    assertTrue(
                        "Auto Tune did not reach a measured regression or the healthy floor",
                        regressionCount > 0 || floorCount > 0,
                    )
                }
                emit(
                    JSONObject()
                        .put("event", "summary")
                        .put("wallTimeMillis", System.currentTimeMillis())
                        .put("freshSamples", freshSamples)
                        .put("staleSamples", staleSamples)
                        .put("trimCount", trimCount)
                        .put("acceptedTrimCount", acceptedTrimCount)
                        .put("regressionCount", regressionCount)
                        .put("recoveryCount", recoveryCount)
                        .put("floorCount", floorCount)
                        .put("fixedCpuPolicyIds", JSONArray(fixedCpuCeilings.keys.toList()))
                        .put("minimumClampSamples", minimumClampSamples)
                        .put("unhealthySamples", unhealthySamples)
                        .put("unhealthyAtBaseSamples", unhealthyAtBaseSamples)
                        .put("maxConsecutiveUnhealthySamples", maxConsecutiveUnhealthySamples)
                        .put("upwardResetSamples", upwardResetSamples)
                        .put("upwardResetEvents", upwardResetEvents)
                        .put("upwardResetReassertions", upwardResetReassertions)
                        .put("postApplyUpwardResetEvents", postApplyUpwardResetEvents)
                        .put(
                            "maxConsecutiveUnhealthyWithHeadroomSamples",
                            maxConsecutiveUnhealthyWithHeadroomSamples,
                        )
                        .putNullable("minimumFpsMilli", minimumFpsMilli)
                        .putNullable("maximumFpsMilli", maximumFpsMilli)
                        .put("reasons", JSONObject(reasons as Map<*, *>))
                        .put("stop", stopped.toJson())
                        .put("restoredState", restored.toJson()),
                )
                handle = null
            } catch (failure: Throwable) {
                traceFailure = failure
                emit(
                    JSONObject()
                        .put("event", "failure")
                        .put("wallTimeMillis", System.currentTimeMillis())
                        .put("type", failure.javaClass.name)
                        .put("message", failure.message.orEmpty()),
                )
            } finally {
                var cleanupFailure: Throwable? = null
                handle?.let { sessionHandle ->
                    runCatching { stopAndRestore(client, sessionHandle) }
                        .onSuccess { stopped ->
                            emit(
                                JSONObject()
                                    .put("event", "finally_stop")
                                    .put("wallTimeMillis", System.currentTimeMillis())
                                    .put("stop", stopped.toJson()),
                            )
                        }
                        .onFailure { cleanupFailure = it }
                }
                if (safeToStopHost && cleanupFailure == null) {
                    val current = client.readAutoTelemetry().getOrNull()
                    if (current?.status != HostAutoSessionStatus.ACTIVE) {
                        client.stop().exceptionOrNull()?.let { cleanupFailure = it }
                    }
                }
                cleanupFailure?.let { failure ->
                    emit(
                        JSONObject()
                            .put("event", "cleanup_failure")
                            .put("wallTimeMillis", System.currentTimeMillis())
                            .put("type", failure.javaClass.name)
                            .put("message", failure.message.orEmpty()),
                    )
                    traceFailure?.addSuppressed(failure) ?: run { traceFailure = failure }
                }
            }
        }
        traceFailure?.let { throw it }
    }

    private fun stopAndRestore(
        client: ClusterTuneHostClient,
        handle: HostAutoSessionHandle,
    ): HostAutoSessionSnapshot {
        var latest: HostAutoSessionSnapshot? = null
        repeat(20) {
            latest = client.stopAutoSession(handle).getOrThrow()
            val snapshot = requireNotNull(latest)
            check(snapshot.sessionId == handle.sessionId) {
                "restoration returned a different session: ${snapshot.sessionId}"
            }
            check(snapshot.hostEpoch == handle.hostEpoch) {
                "restoration returned a different host epoch: ${snapshot.hostEpoch}"
            }
            if (
                snapshot.status in setOf(HostAutoSessionStatus.STOPPED, HostAutoSessionStatus.EXPIRED) &&
                snapshot.restorationAttempted &&
                snapshot.restorationComplete
            ) {
                return snapshot
            }
            Thread.sleep(500L)
        }
        error("automatic restoration remained incomplete: ${latest?.message.orEmpty()}")
    }

    private fun adaptiveEnvelope(
        capabilities: HostCapabilities,
        baseline: HostState,
    ): AdaptiveTuneEnvelope {
        val adjustableCpuPolicyIds = adaptiveAdjustableCpuPolicyIds(capabilities.cpus)
        return AdaptiveTuneEnvelope(
            cpuPolicies = capabilities.cpus.mapIndexed { index, domain ->
                val base = baseline.cpuMax[index]
                val exclusiveFloor = baseline.cpuMin[index]
                AdaptiveCpuPolicy(
                    policyId = domain.policyId(),
                    availableCeilingsKHz = if (exclusiveFloor > 0L) {
                        domain.supportedFrequencies
                            .ifEmpty { domain.minimumCandidates }
                            .filter { it > exclusiveFloor && it <= base }
                    } else {
                        emptyList()
                    },
                    baseCeilingKHz = base,
                    allowsAdaptiveAdjustment = domain.policyId() in adjustableCpuPolicyIds,
                )
            },
            gpu = capabilities.gpu?.let { domain ->
                val base = requireNotNull(baseline.gpuMax)
                val exclusiveFloor = baseline.gpuMin
                AdaptiveGpuDomain(
                    id = domain.id,
                    availableCeilingsHz = if (exclusiveFloor != null && exclusiveFloor > 0L) {
                        domain.supportedFrequencies.filter { it > exclusiveFloor && it <= base }
                    } else {
                        emptyList()
                    },
                    baseCeilingHz = base,
                )
            },
        )
    }

    private fun assertFixedCpuCeilings(
        expected: Map<Int, Long>,
        actual: AdaptiveFrequencyCeilings,
    ) {
        expected.forEach { (policyId, expectedCeiling) ->
            assertEquals(
                "fixed CPU policy$policyId changed in the complete controller envelope",
                expectedCeiling,
                actual.cpuKHz[policyId],
            )
        }
    }

    private fun HostAutoTelemetry.toAdaptiveTuneSample(
        capabilities: HostCapabilities,
    ): AdaptiveTuneSample {
        val frames = fpsMilli?.takeIf { it > 0 && frameConfidencePermille >= 250 }?.let { fps ->
            AdaptiveFrameMetrics(
                fps = fps / 1_000.0,
                p95FrameTimeMillis = frameTimeP95Nanos?.takeIf { it > 0L }?.div(1_000_000.0),
                slowFrameRatio = slowFrameRatioPermille?.coerceIn(0, 1_000)?.div(1_000.0),
                isStale = frameStale || frameConfidencePermille < 250,
            )
        }
        return AdaptiveTuneSample(
            timestampNanos = timestampNanos,
            frames = frames,
            cpuLoad = capabilities.cpus.mapIndexed { index, domain ->
                domain.policyId() to cpuLoadPermille.getOrNull(index)?.coerceIn(0, 1_000)?.div(1_000.0)
            }.toMap(),
            gpuBusy = gpuBusyPermille?.coerceIn(0, 1_000)?.div(1_000.0),
        )
    }

    private fun AdaptiveTuneDecision.Apply.toApplyRequest(
        capabilities: HostCapabilities,
    ): ApplyRequest = ApplyRequest(
        cpuMax = capabilities.cpus.map { domain -> ceilings.cpuKHz.getValue(domain.policyId()) },
        gpuMax = capabilities.gpu?.let { requireNotNull(ceilings.gpuHz) },
        resetToStock = false,
        cpuIds = capabilities.cpus.map(CpuDomain::id),
        gpuId = capabilities.gpu?.id,
        gpuMaxPath = capabilities.gpu?.maxPath,
        maximumsOnly = true,
    )

    private fun CpuDomain.policyId(): Int = id.removePrefix("policy").toInt()

    private fun HostAutoCapabilities.toJson(): JSONObject = JSONObject()
        .put("frameStats", frameStats)
        .put("cpuLoad", cpuLoad)
        .put("cpuClocks", cpuClocks)
        .put("gpuBusy", gpuBusy)
        .put("gpuClock", gpuClock)
        .put("thermal", thermal)
        .putNullable("frameBackend", frameBackend)
        .putNullable("unsupportedReason", unsupportedReason)

    private fun HostCapabilities.toJson(): JSONObject = JSONObject()
        .put(
            "cpus",
            JSONArray(
                cpus.map { cpu ->
                    JSONObject()
                        .put("id", cpu.id)
                        .put("minPath", cpu.minPath)
                        .put("maxPath", cpu.maxPath)
                        .putNullable("curPath", cpu.curPath)
                        .put("minimumCandidates", JSONArray(cpu.minimumCandidates))
                        .put("supportedFrequencies", JSONArray(cpu.supportedFrequencies))
                        .put("stockMax", cpu.stockMax)
                        .put("observedMax", cpu.observedMax)
                        .put("observedMin", cpu.observedMin)
                        .put("selectableMax", cpu.selectableMax)
                        .put("currentMax", cpu.currentMax)
                },
            ),
        )
        .putNullable(
            "gpu",
            gpu?.let { domain ->
                JSONObject()
                    .put("id", domain.id)
                    .putNullable("minPath", domain.minPath)
                    .put("maxPath", domain.maxPath)
                    .putNullable("curPath", domain.curPath)
                    .put("supportedFrequencies", JSONArray(domain.supportedFrequencies))
                    .put("stockMax", domain.stockMax)
                    .put("observedMax", domain.observedMax)
                    .put("observedMin", domain.observedMin)
                    .put("selectableMax", domain.selectableMax)
                    .put("currentMax", domain.currentMax)
            },
        )

    private fun HostState.toJson(): JSONObject = JSONObject()
        .put("cpuMax", JSONArray(cpuMax))
        .put("cpuMin", JSONArray(cpuMin))
        .put("cpuCurrent", JSONArray(cpuCurrent))
        .putNullable("gpuMax", gpuMax)
        .putNullable("gpuMin", gpuMin)
        .putNullable("gpuCurrent", gpuCurrent)

    private fun restoredStateMatchesCheckpointOrPhysicalStock(
        baseline: ApplyRequest,
        state: HostState,
        capabilities: HostCapabilities,
    ): Boolean {
        if (baseline.cpuMax.size != capabilities.cpus.size || state.cpuMax.size != capabilities.cpus.size) {
            return false
        }
        val cpuAccepted = capabilities.cpus.indices.all { index ->
            val actual = state.cpuMax[index]
            val checkpoint = baseline.cpuMax[index]
            val domain = capabilities.cpus[index]
            actual == checkpoint || actual > 0L &&
                (actual == domain.stockMax && domain.stockMax > 0L ||
                    actual == domain.selectableMax && domain.selectableMax > 0L)
        }
        if (!cpuAccepted) return false
        val checkpointGpu = baseline.gpuMax
        val actualGpu = state.gpuMax
        val gpu = capabilities.gpu
        if (checkpointGpu == null || actualGpu == null || gpu == null) return checkpointGpu == actualGpu
        return actualGpu == checkpointGpu || actualGpu > 0L &&
            (actualGpu == gpu.stockMax && gpu.stockMax > 0L ||
                actualGpu == gpu.selectableMax && gpu.selectableMax > 0L)
    }

    private fun HostAutoTelemetry.toJson(): JSONObject = JSONObject()
        .put("sequence", sequence)
        .put("timestampNanos", timestampNanos)
        .putNullable("frameBackend", frameBackend)
        .put("frameConfidencePermille", frameConfidencePermille)
        .putNullable("frameLayer", frameLayer)
        .put("frameCount", frameCount)
        .putNullable("fpsMilli", fpsMilli)
        .putNullable("frameTimeP95Nanos", frameTimeP95Nanos)
        .putNullable("slowFrameRatioPermille", slowFrameRatioPermille)
        .put("frameStale", frameStale)
        .put("cpuLoadPermille", nullableArray(cpuLoadPermille))
        .put("cpuClockKHz", nullableArray(cpuClockKHz))
        .putNullable("gpuBusyPermille", gpuBusyPermille)
        .putNullable("gpuClockHz", gpuClockHz)
        .put(
            "thermal",
            JSONArray(
                thermal.map { reading ->
                    JSONObject()
                        .put("type", reading.type)
                        .put("temperatureMilliCelsius", reading.temperatureMilliCelsius)
                },
            ),
        )
        .put("unsupportedMetrics", JSONArray(unsupportedMetrics))

    private fun AdaptiveTuneDecision.toJson(): JSONObject = JSONObject()
        .put("kind", javaClass.simpleName)
        .put("status", status.name)
        .put("reason", reason.name)
        .put("ceilings", ceilings.toJson())
        .also { output ->
            when (this) {
                is AdaptiveTuneDecision.Apply -> output.put(
                    "change",
                    JSONObject()
                        .put("actuator", change.actuator.label())
                        .put("fromCeiling", change.fromCeiling)
                        .put("toCeiling", change.toCeiling)
                        .put("stepDelta", change.stepDelta),
                )
                is AdaptiveTuneDecision.Hold -> output
                    .put("warmupSamplesRemaining", warmupSamplesRemaining)
                    .put("healthySamplesRemaining", healthySamplesRemaining)
                is AdaptiveTuneDecision.Stop -> Unit
            }
        }

    private fun AdaptiveFrequencyCeilings.toJson(): JSONObject = JSONObject()
        .put("cpuKHz", JSONObject(cpuKHz.mapKeys { it.key.toString() } as Map<*, *>))
        .putNullable("gpuHz", gpuHz)

    private fun AdaptiveActuator.label(): String = when (this) {
        is AdaptiveActuator.CpuPolicy -> "cpu:$policyId"
        is AdaptiveActuator.Gpu -> "gpu:$id"
    }

    private fun AdaptiveFrameMetrics.isUnhealthy(config: AdaptiveTuneConfig): Boolean {
        if (isStale || !fps.isFinite() || fps <= 0.0) return false
        val slowRatio = slowFrameRatio?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val pacingUnhealthy = slowRatio?.let { it > config.recoverySlowFrameRatio } == true
        return fps < config.targetFps * config.recoveryFpsRatio || pacingUnhealthy
    }

    private fun AdaptiveFrequencyCeilings.hasHeadroom(
        base: AdaptiveFrequencyCeilings,
    ): Boolean = cpuKHz.any { (policyId, ceiling) ->
        ceiling < base.cpuKHz.getValue(policyId)
    } || (gpuHz != null && base.gpuHz != null && gpuHz < base.gpuHz)

    private fun HostAutoSessionSnapshot.toJson(): JSONObject = JSONObject()
        .putNullable("sessionId", sessionId)
        .put("hostEpoch", hostEpoch)
        .put("status", status.name)
        .put("targetFps", targetFps)
        .put("restorationAttempted", restorationAttempted)
        .put("restorationComplete", restorationComplete)
        .putNullable("message", message)
        .putNullable("state", state?.toJson())

    private fun nullableArray(values: List<*>): JSONArray = JSONArray().also { array ->
        values.forEach { value -> array.put(value ?: JSONObject.NULL) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private companion object {
        const val TRACE_FILE_NAME = "external-autotune-trace.jsonl"
        const val MAX_TRIAL_SETTLE_SAMPLES = 12
    }
}
