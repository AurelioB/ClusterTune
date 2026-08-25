package com.aure.clustertune.root.host

import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

interface HostFilesystem {
    fun read(path: String): String?
    fun write(path: String, value: String): Boolean
    fun mode(path: String): Int?
    fun chmod(path: String, mode: Int): Boolean
    fun exists(path: String): Boolean
    fun lastMutationError(): String? = null
    fun lastMutationFailure(): Throwable? = null
    fun mutate(operations: List<HostMutation>): Boolean {
        operations.forEach { operation ->
            val ok = when (operation) {
                is HostMutation.Chmod -> chmod(operation.path, operation.mode)
                is HostMutation.Write -> write(operation.path, operation.value)
                is HostMutation.WriteCandidatesNoReadback -> operation.candidates.any { write(operation.path, it) }
                is HostMutation.WritePreferred -> {
                    fun accepted(value: String) = write(operation.path, value) && read(operation.path) == value
                    accepted(operation.preferred) || accepted(operation.fallback)
                }
            }
            if (!ok) return false
        }
        return true
    }
}

sealed interface HostMutation {
    data class Chmod(val path: String, val mode: Int) : HostMutation
    data class Write(val path: String, val value: String) : HostMutation
    /** Tries numeric candidates in order, accepting kernel write success without readback. */
    data class WriteCandidatesNoReadback(val path: String, val candidates: List<String>) : HostMutation
    /** Writes the preferred value, falling back when the kernel rejects it or readback differs. */
    data class WritePreferred(val path: String, val preferred: String, val fallback: String) : HostMutation
}

class RealHostFilesystem @JvmOverloads constructor(
    private val pollTimeoutMs: Long = 2_000L,
    private val shellPath: String = "/system/bin/sh",
) : HostFilesystem {
    @Volatile private var mutationError: String? = null
    @Volatile private var mutationFailure: Throwable? = null
    override fun lastMutationError(): String? = mutationError
    override fun lastMutationFailure(): Throwable? = mutationFailure
    override fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()
    override fun write(path: String, value: String): Boolean {
        return mutate(listOf(HostMutation.Write(path, value)))
    }
    override fun mode(path: String): Int? = runCatching { Os.stat(path).st_mode and 0x1ff }.getOrNull()
    override fun chmod(path: String, mode: Int): Boolean {
        return mutate(listOf(HostMutation.Chmod(path, mode)))
    }
    override fun exists(path: String): Boolean = File(path).isFile

    override fun mutate(operations: List<HostMutation>): Boolean {
        mutationError = null
        mutationFailure = null
        if (operations.isEmpty() || operations.size > 96) {
            mutationError = "invalid operation count"
            return false
        }
        val script = buildString {
            append("set -e; ")
            operations.forEachIndexed { index, operation ->
                append("if { ")
                when (operation) {
                    is HostMutation.Chmod -> {
                        append("chmod ")
                            .append(shellQuote(Integer.toOctalString(operation.mode and 0x1ff)))
                            .append(' ')
                            .append(shellQuote(operation.path))
                            .append(" 2>/dev/null; ")
                    }

                    is HostMutation.Write -> {
                        if (!operation.value.matches(Regex("[0-9]+"))) {
                            mutationError = "invalid numeric value"
                            return false
                        }
                        val quotedPath = shellQuote(operation.path)
                        append("echo ")
                            .append(shellQuote(operation.value))
                            .append(" > ")
                            .append(quotedPath)
                            .append(" 2>/dev/null && [ \"\$(cat ")
                            .append(quotedPath)
                            .append(" 2>/dev/null)\" = ")
                            .append(shellQuote(operation.value))
                            .append(" ]; ")
                    }

                    is HostMutation.WriteCandidatesNoReadback -> {
                        if (operation.candidates.isEmpty() || operation.candidates.any { !it.matches(Regex("[0-9]+")) }) {
                            mutationError = "invalid numeric value"
                            return false
                        }
                        val quotedPath = shellQuote(operation.path)
                        append("ok=0; for candidate in ")
                        operation.candidates.forEach { candidate ->
                            append(shellQuote(candidate)).append(' ')
                        }
                        append("; do if echo \"\$candidate\" > ")
                            .append(quotedPath)
                            .append(" 2>/dev/null; then ok=1; break; fi; done; [ \"\$ok\" -eq 1 ]; ")
                    }

                    is HostMutation.WritePreferred -> {
                        if (!operation.preferred.matches(Regex("[0-9]+")) ||
                            !operation.fallback.matches(Regex("[0-9]+"))
                        ) {
                            mutationError = "invalid numeric value"
                            return false
                        }
                        val quotedPath = shellQuote(operation.path)
                        append("if echo ")
                            .append(shellQuote(operation.preferred))
                            .append(" > ")
                            .append(quotedPath)
                            .append(" 2>/dev/null && [ \"\$(cat ")
                            .append(quotedPath)
                            .append(" 2>/dev/null)\" = ")
                            .append(shellQuote(operation.preferred))
                            .append(" ]; then :; else echo ")
                            .append(shellQuote(operation.fallback))
                            .append(" > ")
                            .append(quotedPath)
                            .append(" 2>/dev/null && [ \"\$(cat ")
                            .append(quotedPath)
                            .append(" 2>/dev/null)\" = ")
                            .append(shellQuote(operation.fallback))
                            .append(" ]; fi; ")
                    }
                }
                append("}; then :; else status=\$?; printf '%s\\n' ")
                    .append(shellQuote("$MUTATION_FAILURE_MARKER${index + 1}"))
                    .append(" >&2; exit \"\$status\"; fi; ")
            }
        }
        if (script.length > 8192) {
            mutationError = "script too long"
            return false
        }
        return dispatchLocalScript(script, operations)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun dispatchLocalScript(command: String, operations: List<HostMutation>): Boolean {
        val process = try {
            ProcessBuilder(shellPath, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            mutationError = e.message ?: "unable to start shell"
            return false
        }
        return try {
            val output = ByteArrayOutputStream()
            val drain = Thread {
                process.inputStream.use { input ->
                    val buffer = ByteArray(1024)
                    var retained = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (retained < 4096) {
                            val keep = count.coerceAtMost(4096 - retained)
                            output.write(buffer, 0, keep)
                            retained += keep
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            if (!process.waitFor(pollTimeoutMs.coerceAtLeast(250L), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(250L, TimeUnit.MILLISECONDS)
                drain.join(250L)
                mutationError = "timeout"
                mutationFailure = HostDispatchFailure(true, "local shell transaction timed out")
                false
            } else {
                drain.join()
                if (process.exitValue() != 0) {
                    val rawOutput = output.toByteArray().toString(Charsets.UTF_8)
                    val failedOperation = MUTATION_FAILURE_PATTERN.findAll(rawOutput)
                        .lastOrNull()
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.minus(1)
                        ?.takeIf { it in operations.indices }
                    mutationError = failedOperation?.let { operationIndex ->
                        val operation = operations[operationIndex]
                        "status=${process.exitValue()} operation=${operationIndex + 1}/${operations.size} " +
                            "kind=${operation.diagnosticKind()} path=${diagnosticPath(operation.diagnosticPath())}"
                    } ?: "status=${process.exitValue()} operation=unknown"
                    mutationFailure = HostDispatchFailure(
                        indeterminate = false,
                        message = mutationError!!,
                        firstFailedOperationIndex = failedOperation,
                    )
                    false
                } else {
                    true
                }
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            mutationError = "interrupted"
            Thread.currentThread().interrupt()
            mutationFailure = HostDispatchFailure(true, "local shell transaction interrupted")
            false
        } catch (e: Exception) {
            mutationError = e.message ?: "transaction error"
            mutationFailure = HostDispatchFailure(true, mutationError!!, e)
            false
        } finally {
            process.destroy()
        }
    }

    private fun HostMutation.diagnosticPath(): String = when (this) {
        is HostMutation.Chmod -> path
        is HostMutation.Write -> path
        is HostMutation.WriteCandidatesNoReadback -> path
        is HostMutation.WritePreferred -> path
    }

    private fun HostMutation.diagnosticKind(): String = when (this) {
        is HostMutation.Chmod -> "chmod"
        is HostMutation.Write -> "write"
        is HostMutation.WriteCandidatesNoReadback -> "write-candidates"
        is HostMutation.WritePreferred -> "write-preferred"
    }

    private fun diagnosticPath(path: String): String {
        val sanitized = buildString(path.length.coerceAtMost(MAX_DIAGNOSTIC_PATH_LENGTH)) {
            path.take(MAX_DIAGNOSTIC_PATH_LENGTH).forEach { character ->
                append(if (character.code in 0x21..0x7e) character else '?')
            }
        }
        return if (path.length > MAX_DIAGNOSTIC_PATH_LENGTH) "$sanitized..." else sanitized
    }

    private companion object {
        const val MUTATION_FAILURE_MARKER = "__CLUSTERTUNE_MUTATION_FAILED__="
        const val MAX_DIAGNOSTIC_PATH_LENGTH = 240
        val MUTATION_FAILURE_PATTERN = Regex("(?:^|\\n)${MUTATION_FAILURE_MARKER}(\\d+)(?:\\r?\\n|$)")
    }

}

/** The exact ceiling values a profile transaction may leave after Stock fallbacks. */
internal data class HostResolvedMaximumTargets(
    val stabilizedStockCeiling: Long?,
    val cpuStock: List<Boolean>,
    val cpuStockCandidates: List<List<Long>>,
    val cpuExpected: List<Long>,
    val cpuSafetyCeilings: List<Long>,
    val gpuStock: Boolean,
    val gpuExpected: Long?,
    val gpuAcceptedCeilings: List<Long>,
) {
    val cpuAcceptedCeilings: List<Set<Long>>
        get() = cpuExpected.mapIndexed { index, expected ->
            if (cpuStock[index]) cpuStockCandidates[index].toSet() else setOf(expected)
        }
}

/**
 * Resolves Stock aliases and hidden GPU ceilings once for both mutation and session ownership.
 * Keeping this shared prevents startup recovery from claiming a value the apply engine could
 * never have written (or overlooking a valid preferred-write fallback).
 */
internal fun resolveHostMaximumTargets(
    capabilities: HostCapabilities,
    request: ApplyRequest,
): HostResolvedMaximumTargets {
    require(request.cpuMax.size == capabilities.cpus.size)
    require(request.gpuMax == null || capabilities.gpu != null) {
        "GPU target requested but no GPU domain is available"
    }
    val stabilizedStockCeiling = request.stabilizedStockCeiling
        ?.takeIf { capabilities.gpu?.supportedFrequencies?.isEmpty() == true }
    stabilizedStockCeiling?.let {
        require(it > 0L && it <= 4_000_000_000L) { "invalid stabilized GPU stock ceiling" }
        require(request.gpuId != null && request.gpuMaxPath != null) {
            "stabilized GPU stock ceiling requires domain identity"
        }
    }
    val cpuStock = capabilities.cpus.mapIndexed { index, cpu ->
        request.resetToStock || request.cpuMax[index] >= cpu.selectableMax
    }
    val cpuStockCandidates = capabilities.cpus.map { cpu ->
        listOf(cpu.stockMax.takeIf { it > 0 } ?: cpu.selectableMax, cpu.selectableMax).distinct()
    }
    val cpuExpected = capabilities.cpus.mapIndexed { index, cpu ->
        if (cpuStock[index]) cpu.stockMax.takeIf { it > 0 } ?: cpu.selectableMax else request.cpuMax[index]
    }
    val cpuSafetyCeilings = capabilities.cpus.mapIndexed { index, cpu ->
        if (cpuStock[index]) cpuStockCandidates[index].minOrNull() ?: cpu.selectableMax else cpuExpected[index]
    }
    val gpuStock = request.gpuMax?.let { requested ->
        val gpu = capabilities.gpu
        request.resetToStock || (gpu != null && requested >= (stabilizedStockCeiling ?: gpu.selectableMax))
    } ?: false
    val gpuExpected = request.gpuMax?.let { requested ->
        val gpu = capabilities.gpu
        if (gpu != null && (request.resetToStock || requested >= gpu.selectableMax) &&
            (gpuStock || stabilizedStockCeiling == null)
        ) {
            stabilizedStockCeiling?.takeIf { it >= gpu.selectableMax }
                ?: gpu.selectableMax.takeIf { it > 0 }
        } else {
            requested
        }
    }
    require(request.gpuMax == null || gpuExpected != null) {
        "GPU target cannot be resolved to a selectable ceiling"
    }
    val gpuAcceptedCeilings = when {
        gpuExpected == null -> emptyList()
        gpuStock -> {
            val gpu = requireNotNull(capabilities.gpu)
            listOfNotNull(
                stabilizedStockCeiling?.takeIf { it > 0 },
                gpu.stockMax.takeIf { it > 0 },
                gpu.selectableMax,
            ).distinct()
        }
        else -> listOf(gpuExpected)
    }
    return HostResolvedMaximumTargets(
        stabilizedStockCeiling = stabilizedStockCeiling,
        cpuStock = cpuStock,
        cpuStockCandidates = cpuStockCandidates,
        cpuExpected = cpuExpected,
        cpuSafetyCeilings = cpuSafetyCeilings,
        gpuStock = gpuStock,
        gpuExpected = gpuExpected,
        gpuAcceptedCeilings = gpuAcceptedCeilings,
    )
}

/** Exact maximum nodes whose mode or value operations were dispatched by a max-only apply. */
internal data class HostMaximumMutationReceipt(
    val modePaths: Set<String>,
    val valuePaths: Set<String>,
)

class HostApplyEngine(private val fs: HostFilesystem) {
    fun applyOrThrow(capabilities: HostCapabilities, request: ApplyRequest) {
        apply(capabilities, request).getOrElse { failure ->
            if (failure is HostApplyFailure) throw failure
            throw HostApplyFailure(HostApplyPhase.PREFLIGHT, false, true, false, failure.message ?: "host preflight failed", failure)
        }
    }

    /**
     * Applies only maximum-frequency nodes. This is the automatic tuner's actuator path:
     * live OEM/kernel minimum votes remain authoritative even when they exceed a requested cap.
     */
    fun applyMaxOnlyOrThrow(capabilities: HostCapabilities, request: ApplyRequest) {
        applyMaxOnlyTrackedOrThrow(capabilities, request)
    }

    @Synchronized
    internal fun applyMaxOnlyTrackedOrThrow(
        capabilities: HostCapabilities,
        request: ApplyRequest,
        stockModeOverrides: Map<String, Boolean> = emptyMap(),
        preserveModePaths: Set<String> = emptySet(),
    ): HostMaximumMutationReceipt {
        return applyInternal(
            capabilities,
            request,
            repairMinimums = false,
            stockModeOverrides = stockModeOverrides,
            preserveModePaths = preserveModePaths,
        ).getOrElse { failure ->
            if (failure is HostApplyFailure) throw failure
            throw HostApplyFailure(HostApplyPhase.PREFLIGHT, false, true, false, failure.message ?: "host preflight failed", failure)
        }
    }

    @Synchronized
    fun apply(capabilities: HostCapabilities, request: ApplyRequest): Result<Unit> =
        applyInternal(capabilities, request, repairMinimums = true).map { Unit }

    @Synchronized
    fun applyMaxOnly(capabilities: HostCapabilities, request: ApplyRequest): Result<Unit> =
        applyInternal(capabilities, request, repairMinimums = false).map { Unit }

    private fun applyInternal(
        capabilities: HostCapabilities,
        request: ApplyRequest,
        repairMinimums: Boolean,
        stockModeOverrides: Map<String, Boolean> = emptyMap(),
        preserveModePaths: Set<String> = emptySet(),
    ): Result<HostMaximumMutationReceipt> = runCatching {
        request.gpuId?.let { require(capabilities.gpu?.id == it) { "GPU identity mismatch" } }
        request.gpuMaxPath?.let { require(capabilities.gpu?.maxPath == it) { "GPU path mismatch" } }
        val maximumPaths = buildSet {
            capabilities.cpus.forEach { add(it.maxPath) }
            capabilities.gpu?.let { add(it.maxPath) }
        }
        require(stockModeOverrides.keys.all { it in maximumPaths } && preserveModePaths.all { it in maximumPaths }) {
            "mode intent does not match discovered maximum domains"
        }
        val resolved = resolveHostMaximumTargets(capabilities, request)
        val stabilizedStockCeiling = resolved.stabilizedStockCeiling
        val cpuStock = resolved.cpuStock
        val cpuStockCandidates = resolved.cpuStockCandidates
        val expected = resolved.cpuExpected
        val safetyCeilings = resolved.cpuSafetyCeilings
        val gpuStock = resolved.gpuStock
        val expectedGpu = resolved.gpuExpected
        val original = capabilities.cpus.map {
            fs.read(it.maxPath)?.toLongOrNull() ?: error("cannot read ${it.maxPath}")
        }
        val originalMins = if (repairMinimums) {
            capabilities.cpus.map {
                fs.read(it.minPath)?.toLongOrNull() ?: error("cannot read ${it.minPath}")
            }
        } else {
            List(capabilities.cpus.size) { 0L }
        }
        val originalModes = capabilities.cpus.map {
            fs.mode(it.maxPath) ?: error("cannot read mode for ${it.maxPath}")
        }
        val originalMinModes = if (repairMinimums) {
            capabilities.cpus.map {
                fs.mode(it.minPath) ?: error("cannot read mode for ${it.minPath}")
            }
        } else {
            List(capabilities.cpus.size) { 0 }
        }
        val originalGpu = if (expectedGpu != null) {
            capabilities.gpu?.let {
                fs.read(it.maxPath)?.toLongOrNull() ?: error("cannot read ${it.maxPath}")
            }
        } else {
            null
        }
        val originalGpuMin = if (repairMinimums && expectedGpu != null) {
            capabilities.gpu?.minPath?.let {
                fs.read(it)?.toLongOrNull() ?: error("cannot read $it")
            }
        } else {
            null
        }
        val originalGpuMode = if (expectedGpu != null) {
            capabilities.gpu?.let {
                fs.mode(it.maxPath) ?: error("cannot read mode for ${it.maxPath}")
            }
        } else {
            null
        }
        val originalGpuMinMode = if (repairMinimums && expectedGpu != null) {
            capabilities.gpu?.minPath?.let {
                fs.mode(it) ?: error("cannot read mode for $it")
            }
        } else {
            null
        }
        val cpuNeedsMinRepair = originalMins.mapIndexed { index, value ->
            repairMinimums && (value <= 0 || value > safetyCeilings[index])
        }
        val gpuNeedsMinRepair = repairMinimums && expectedGpu != null && originalGpuMin != null &&
            (originalGpuMin <= 0 || originalGpuMin > (if (gpuStock) {
                val gpu = capabilities.gpu
                listOfNotNull(stabilizedStockCeiling?.takeIf { it > 0 }, gpu?.stockMax?.takeIf { it > 0 }, gpu?.selectableMax).minOrNull() ?: expectedGpu
            } else expectedGpu))

        // Validate every requested value and every required repair before touching a sysfs node.
        // This keeps malformed multi-domain requests fail-closed instead of partially applying
        // an earlier CPU domain and relying on rollback for an avoidable validation error.
        capabilities.cpus.forEachIndexed { index, cpu ->
            validateCpuTarget(cpu, expected[index], cpuStock[index])
            require(!cpuNeedsMinRepair[index] || cpu.minimumCandidates.any { it > 0 && it <= safetyCeilings[index] }) {
                "no safe minimum candidate for ${cpu.minPath}"
            }
        }
        if (expectedGpu != null) {
            val gpu = capabilities.gpu
                ?: error("GPU target requested but no GPU domain is available")
            val gpuAcceptedCeilings = resolved.gpuAcceptedCeilings
            // Validation accepts any candidate the forward write may select; minimum
            // safety below is intentionally based on the *lowest* accepted ceiling.
            val allowedCeiling = gpuAcceptedCeilings.maxOrNull() ?: gpu.selectableMax
            validateGpuTarget(gpu, expectedGpu, allowedCeiling)
            if (gpu.minPath != null && gpuNeedsMinRepair) {
                val minimumCeiling = if (gpuStock) {
                    listOfNotNull(stabilizedStockCeiling?.takeIf { it > 0 }, gpu.stockMax.takeIf { it > 0 }, gpu.selectableMax).minOrNull() ?: expectedGpu
                } else expectedGpu
                require((listOf(gpu.observedMin) + gpu.supportedFrequencies)
                    .any { it > 0 && it <= minimumCeiling }) {
                    "no safe minimum candidate for ${gpu.minPath}"
                }
            }
        }
        data class JournalEntry(
            val path: String,
            val value: Long,
            val mode: Int,
            val isMax: Boolean,
            val pairedMaxPath: String?,
            val restoreFallbacks: List<Long> = emptyList(),
            val ownedTargets: Set<Long> = emptySet(),
            val ownedModes: Set<Int> = emptySet(),
        )
        val journal = LinkedHashMap<String, JournalEntry>()
        val maximumModeMutationPaths = linkedSetOf<String>()
        val maximumValueMutationPaths = linkedSetOf<String>()
        val maximumMutations = mutableListOf<HostMutation>()
        var gpuMutationTarget: Long? = null
        fun journalBeforeMutation(
            path: String,
            value: Long,
            mode: Int,
            isMax: Boolean,
            pairedMaxPath: String?,
            restoreFallbacks: List<Long> = emptyList(),
            ownedTargets: Set<Long> = emptySet(),
            ownedModes: Set<Int> = emptySet(),
        ) {
            journal.putIfAbsent(
                path,
                JournalEntry(path, value, mode, isMax, pairedMaxPath, restoreFallbacks, ownedTargets, ownedModes),
            )
        }
        var mutationStarted = false
        var maximumDispatchFailed = false
        try {
            capabilities.cpus.forEachIndexed { index, cpu ->
                val target = expected[index]
                val acceptedTargets = if (cpuStock[index]) cpuStockCandidates[index] else listOf(target)
                val stockMode = stockModeOverrides[cpu.maxPath] ?: cpuStock[index]
                val finalMode = if (!repairMinimums && cpu.maxPath in preserveModePaths) {
                    originalModes[index]
                } else {
                    protectionMode(originalModes[index], stockMode)
                }
                val valueAlreadyAccepted = original[index] in acceptedTargets
                val shouldMutateMaximum = repairMinimums || !valueAlreadyAccepted || originalModes[index] != finalMode
                if (cpuNeedsMinRepair[index]) {
                    journalBeforeMutation(cpu.minPath, originalMins[index], originalMinModes[index], false, cpu.maxPath)
                    maximumMutations += HostMutation.Chmod(cpu.minPath, writableMode(originalMinModes[index]))
                    maximumMutations += HostMutation.WriteCandidatesNoReadback(
                        cpu.minPath,
                        cpu.minimumCandidates.filter { it > 0 && it <= safetyCeilings[index] }.distinct().sorted().map { it.toString() }
                    )
                }
                if (shouldMutateMaximum) {
                    maximumModeMutationPaths += cpu.maxPath
                    if (repairMinimums || !valueAlreadyAccepted) {
                        maximumValueMutationPaths += cpu.maxPath
                    }
                    journalBeforeMutation(
                        cpu.maxPath,
                        original[index],
                        originalModes[index],
                        true,
                        null,
                        if (original[index] > cpu.selectableMax) listOf(cpu.selectableMax) else emptyList(),
                        if (!repairMinimums && valueAlreadyAccepted) emptySet() else acceptedTargets.toSet(),
                        setOf(writableMode(originalModes[index]), finalMode),
                    )
                    if (!repairMinimums && valueAlreadyAccepted) {
                        // The maximum already has an accepted value; only its protection mode
                        // needs repair. Avoid a needless sysfs write on the live Auto Tune path.
                        maximumMutations += HostMutation.Chmod(cpu.maxPath, finalMode)
                    } else {
                        maximumMutations += HostMutation.Chmod(cpu.maxPath, writableMode(originalModes[index]))
                        maximumMutations += if (cpuStock[index]) {
                            val candidates = cpuStockCandidates[index]
                            HostMutation.WritePreferred(cpu.maxPath, candidates.first().toString(), candidates.last().toString())
                        } else HostMutation.Write(cpu.maxPath, target.toString())
                        maximumMutations += HostMutation.Chmod(cpu.maxPath, finalMode)
                    }
                }
            }
            gpuMutationTarget = expectedGpu
            capabilities.gpu?.let { gpu ->
                gpuMutationTarget?.let { target ->
                    val acceptedTargets = if (gpuStock) {
                        listOfNotNull(
                            stabilizedStockCeiling?.takeIf { it > 0 },
                            gpu.stockMax.takeIf { it > 0 },
                            gpu.selectableMax,
                        ).distinct()
                    } else {
                        listOf(target)
                    }
                    val gpuMode = originalGpuMode ?: error("cannot read mode for ${gpu.maxPath}")
                    val stockMode = stockModeOverrides[gpu.maxPath] ?: gpuStock
                    val finalMode = if (!repairMinimums && gpu.maxPath in preserveModePaths) {
                        gpuMode
                    } else {
                        protectionMode(gpuMode, stockMode)
                    }
                    val valueAlreadyAccepted = originalGpu != null && originalGpu in acceptedTargets
                    val shouldMutateMaximum = repairMinimums || !valueAlreadyAccepted || gpuMode != finalMode
                    if (gpu.minPath != null && gpuNeedsMinRepair) {
                        val minPath = gpu.minPath
                        val minMode = originalGpuMinMode ?: error("cannot read mode for $minPath")
                        journalBeforeMutation(minPath, originalGpuMin ?: error("cannot read $minPath"), minMode, false, gpu.maxPath)
                        maximumMutations += HostMutation.Chmod(minPath, writableMode(minMode))
                        maximumMutations += HostMutation.WriteCandidatesNoReadback(
                            minPath,
                        (listOf(gpu.observedMin) + gpu.supportedFrequencies).filter { it > 0 && it <= (if (gpuStock) listOfNotNull(stabilizedStockCeiling?.takeIf { it > 0 }, gpu.stockMax.takeIf { it > 0 }, gpu.selectableMax).minOrNull() ?: target else target) }.distinct().sorted().map { it.toString() }
                        )
                    }
                    if (shouldMutateMaximum) {
                        maximumModeMutationPaths += gpu.maxPath
                        if (repairMinimums || !valueAlreadyAccepted) {
                            maximumValueMutationPaths += gpu.maxPath
                        }
                        journalBeforeMutation(
                            gpu.maxPath,
                            originalGpu ?: error("cannot read ${gpu.maxPath}"),
                            gpuMode,
                            true,
                            null,
                            if ((originalGpu ?: 0L) > gpu.selectableMax) listOf(gpu.selectableMax) else emptyList(),
                            if (!repairMinimums && valueAlreadyAccepted) emptySet() else acceptedTargets.toSet(),
                            setOf(writableMode(gpuMode), finalMode),
                        )
                        if (!repairMinimums && valueAlreadyAccepted) {
                            maximumMutations += HostMutation.Chmod(gpu.maxPath, finalMode)
                        } else {
                            maximumMutations += HostMutation.Chmod(gpu.maxPath, writableMode(gpuMode))
                            maximumMutations += if (gpuStock) {
                                val preferred = stabilizedStockCeiling?.takeIf { it > 0 } ?: gpu.stockMax.takeIf { it > 0 } ?: gpu.selectableMax
                                HostMutation.WritePreferred(gpu.maxPath, preferred.toString(), gpu.selectableMax.toString())
                            } else {
                                HostMutation.Write(gpu.maxPath, target.toString())
                            }
                            maximumMutations += HostMutation.Chmod(gpu.maxPath, finalMode)
                        }
                    }
                }
            }
            if (maximumMutations.isNotEmpty()) {
                mutationStarted = true
                val applied = fs.mutate(maximumMutations)
                maximumDispatchFailed = !applied
                check(applied) { "cannot apply maximum mutations: ${fs.lastMutationError() ?: "unknown failure"}" }
            }
            capabilities.cpus.forEachIndexed { index, cpu ->
                val target = expected[index]
                val stockMode = stockModeOverrides[cpu.maxPath] ?: cpuStock[index]
                val finalMode = if (!repairMinimums && cpu.maxPath in preserveModePaths) {
                    originalModes[index]
                } else {
                    protectionMode(originalModes[index], stockMode)
                }
                val accepted = if (cpuStock[index]) cpuStockCandidates[index] else listOf(target)
                verifyMax(cpu.id, cpu.maxPath, accepted, finalMode)
                if (repairMinimums && fs.read(cpu.minPath)?.toLongOrNull()?.let { it > 0 && it <= safetyCeilings[index] } != true) {
                    journalBeforeMutation(cpu.minPath, originalMins[index], originalMinModes[index], false, cpu.maxPath)
                    reconcileMinimum(
                        path = cpu.minPath,
                        candidates = cpu.minimumCandidates.filter { it > 0 && it <= safetyCeilings[index] }.distinct().sorted(),
                        ceiling = safetyCeilings[index],
                        originalMode = originalMinModes[index],
                        domainId = cpu.id,
                    )
                }
                if (cpuNeedsMinRepair[index]) check(fs.mode(cpu.minPath) == writableMode(originalMinModes[index])) { "permission verification failed for ${cpu.id} minimum" }
            }
            capabilities.gpu?.let { gpu: GpuDomain ->
                // A null GPU value is an explicit "leave untouched" request, including when
                // the CPU transaction is a Stock reset. This preserves compatibility with
                // CPU-only profiles and callers.
                val target = gpuMutationTarget
                target?.let { requested: Long ->
                    val stockMode = stockModeOverrides[gpu.maxPath] ?: gpuStock
                    val gpuMode = originalGpuMode ?: error("cannot read mode for ${gpu.maxPath}")
                    val finalMode = if (!repairMinimums && gpu.maxPath in preserveModePaths) {
                        gpuMode
                    } else {
                        protectionMode(gpuMode, stockMode)
                    }
                    val accepted = if (gpuStock) listOfNotNull(stabilizedStockCeiling?.takeIf { it > 0 }, gpu.stockMax.takeIf { it > 0 }, gpu.selectableMax).distinct() else listOf(requested)
                    verifyMax(gpu.id, gpu.maxPath, accepted, finalMode)
                    val actualAcceptedMax = fs.read(gpu.maxPath)?.toLongOrNull()
                        ?: error("cannot read ${gpu.maxPath}")
                    gpu.minPath?.let {
                        if (repairMinimums && fs.read(it)?.toLongOrNull()?.let { value -> value > 0 && value <= actualAcceptedMax } != true) {
                            journalBeforeMutation(it, originalGpuMin ?: error("cannot read $it"), originalGpuMinMode ?: error("cannot read mode for $it"), false, gpu.maxPath)
                            reconcileMinimum(
                                path = it,
                                candidates = (listOf(gpu.observedMin) + gpu.supportedFrequencies).filter { value -> value > 0 && value <= actualAcceptedMax }.distinct().sorted(),
                                ceiling = actualAcceptedMax,
                                originalMode = originalGpuMinMode ?: error("cannot read mode for $it"),
                                domainId = gpu.id,
                            )
                        }
                        if (gpuNeedsMinRepair) check(fs.mode(it) == writableMode(originalGpuMinMode ?: error("cannot read mode for $it"))) { "permission verification failed for ${gpu.id} minimum" }
                    }
                }
            }
        } catch (t: Throwable) {
            // RealHostFilesystem retains the last dispatch error until the next mutation.
            // A preflight-only failure must not inherit indeterminate state from an older call.
            val dispatchFailure = if (mutationStarted) fs.lastMutationFailure() as? HostDispatchFailure else null
            fun HostMutation.path(): String = when (this) {
                is HostMutation.Chmod -> path
                is HostMutation.Write -> path
                is HostMutation.WriteCandidatesNoReadback -> path
                is HostMutation.WritePreferred -> path
            }
            val attemptedPrefix = dispatchFailure
                ?.takeIf { maximumDispatchFailed && !it.indeterminate }
                ?.firstFailedOperationIndex
                ?.takeIf { it in maximumMutations.indices }
                ?.let { failedIndex -> maximumMutations.take(failedIndex + 1) }
            val attemptedMutationPaths = attemptedPrefix?.mapTo(linkedSetOf()) { it.path() }
            val attemptedValueMutationPaths = attemptedPrefix
                ?.filterNot { it is HostMutation.Chmod }
                ?.mapTo(linkedSetOf()) { it.path() }
            val attemptedModes = attemptedPrefix
                ?.filterIsInstance<HostMutation.Chmod>()
                ?.groupBy(HostMutation.Chmod::path, HostMutation.Chmod::mode)
                ?.mapValues { (_, modes) -> modes.toSet() }
            val attemptedMaximumModePaths = attemptedPrefix?.asSequence()
                ?.filterIsInstance<HostMutation.Chmod>()
                ?.map { it.path }
                ?.filter { it in maximumModeMutationPaths }
                ?.toSet()
                ?: maximumModeMutationPaths.toSet()
            val attemptedMaximumValuePaths = attemptedPrefix?.asSequence()
                ?.filterNot { it is HostMutation.Chmod }
                ?.map { it.path() }
                ?.filter { it in maximumValueMutationPaths }
                ?.toSet()
                ?: maximumValueMutationPaths.toSet()
            val rollbackJournalEntries = journal.values.mapNotNull { entry ->
                if (attemptedMutationPaths == null) {
                    entry
                } else {
                    entry.takeIf { it.path in attemptedMutationPaths }?.let {
                        it.copy(
                            ownedTargets = if (it.path in attemptedValueMutationPaths.orEmpty()) {
                                it.ownedTargets
                            } else {
                                emptySet()
                            },
                            ownedModes = attemptedModes?.get(it.path).orEmpty(),
                        )
                    }
                }
            }
            if (dispatchFailure?.indeterminate == true) {
                throw HostApplyFailure(
                    phase = HostApplyPhase.MUTATION,
                    mutationStarted = mutationStarted,
                    rollbackComplete = false,
                    indeterminate = true,
                    message = "apply completion is indeterminate; state left untouched for reconciliation",
                    cause = t,
                    attemptedMaximumModePaths = attemptedMaximumModePaths,
                    attemptedMaximumValuePaths = attemptedMaximumValuePaths,
                )
            }
            val rollbackFailures = mutableListOf<String>()
            val rollbackOwnedFallbacks = mutableMapOf<String, MutableSet<Long>>()
            fun recordRollbackFallback(path: String, value: Long) {
                rollbackOwnedFallbacks.getOrPut(path) { linkedSetOf() }.add(value)
            }
            fun rollbackOwnershipSnapshot(): Map<String, Set<Long>> =
                rollbackOwnedFallbacks.mapValues { (_, values) -> values.toSet() }
            fun restoreOwnedMaximumMode(entry: JournalEntry): Boolean {
                val currentMode = fs.mode(entry.path) ?: return false
                if (currentMode == entry.mode || currentMode !in entry.ownedModes) return true
                fs.chmod(entry.path, entry.mode)
                return fs.mode(entry.path) == entry.mode
            }
            val maxRestored = mutableMapOf<String, Boolean>()
            // Restore ceilings before minima so a minimum whose original value is
            // above the temporary ceiling can be restored safely. This also
            // covers minima discovered as invalid after the max batch.
            val rollbackEntries = rollbackJournalEntries.let { entries ->
                entries.filter { it.isMax }.asReversed() + entries.filterNot { it.isMax }.asReversed()
            }
            rollbackEntries.forEach { entry ->
                if (entry.isMax) {
                    val restored = if (repairMinimums) {
                        restoreNode(
                            entry.path,
                            entry.value,
                            entry.mode,
                            fallbackValues = entry.restoreFallbacks,
                            onFallbackRestored = { recordRollbackFallback(entry.path, it) },
                        )
                    } else {
                        val current = fs.read(entry.path)?.toLongOrNull()
                        when {
                            current == null -> {
                                // Value ownership is unresolved, but a mode left by this
                                // transaction can still be restored independently.
                                restoreOwnedMaximumMode(entry)
                                false
                            }
                            current == entry.value -> restoreOwnedMaximumMode(entry)
                            current in entry.ownedTargets ->
                                restoreNode(
                                    entry.path,
                                    entry.value,
                                    entry.mode,
                                    fallbackValues = entry.restoreFallbacks,
                                    onFallbackRestored = { recordRollbackFallback(entry.path, it) },
                                )
                            // A different value belongs to an external policy. Relinquish only
                            // value ownership; an Auto-owned protection mode must still return
                            // to the original mode without touching the external value.
                            else -> restoreOwnedMaximumMode(entry)
                        }
                    }
                    maxRestored[entry.path] = restored
                    if (!restored) rollbackFailures += entry.path
                } else {
                    val maxPath = entry.pairedMaxPath
                    val maxSafe = maxPath != null && maxRestored[maxPath] != false &&
                        (fs.read(maxPath)?.toLongOrNull()?.let { it >= entry.value } == true)
                    val restored = restoreNode(entry.path, entry.value, entry.mode, restoreValue = maxSafe)
                    if (!restored) rollbackFailures += entry.path
                    if (!maxSafe) rollbackFailures += "${entry.path} (value restore skipped: unsafe ceiling)"
                }
            }
            if (rollbackFailures.isNotEmpty()) {
                throw HostApplyFailure(
                    phase = HostApplyPhase.ROLLBACK,
                    mutationStarted = mutationStarted,
                    rollbackComplete = false,
                    indeterminate = dispatchFailure?.indeterminate == true,
                    message = "apply failed: ${t.message}; rollback incomplete for ${rollbackFailures.joinToString()}",
                    cause = t,
                    rollbackOwnedValues = rollbackOwnershipSnapshot(),
                    attemptedMaximumModePaths = attemptedMaximumModePaths,
                    attemptedMaximumValuePaths = attemptedMaximumValuePaths,
                )
            }
            if (t is HostApplyFailure) {
                throw HostApplyFailure(
                    phase = t.phase,
                    mutationStarted = t.mutationStarted,
                    rollbackComplete = t.rollbackComplete,
                    indeterminate = t.indeterminate,
                    message = t.message ?: "host apply failed",
                    cause = t,
                    rollbackOwnedValues = t.rollbackOwnedValues + rollbackOwnershipSnapshot(),
                    attemptedMaximumModePaths = t.attemptedMaximumModePaths + attemptedMaximumModePaths,
                    attemptedMaximumValuePaths = t.attemptedMaximumValuePaths + attemptedMaximumValuePaths,
                )
            }
            throw HostApplyFailure(
                phase = if (!mutationStarted) HostApplyPhase.PREFLIGHT else HostApplyPhase.MUTATION,
                mutationStarted = mutationStarted,
                rollbackComplete = true,
                indeterminate = dispatchFailure?.indeterminate == true,
                message = t.message ?: "host apply failed",
                cause = t,
                rollbackOwnedValues = rollbackOwnershipSnapshot(),
                attemptedMaximumModePaths = attemptedMaximumModePaths,
                attemptedMaximumValuePaths = attemptedMaximumValuePaths,
            )
        }
        HostMaximumMutationReceipt(
            modePaths = maximumModeMutationPaths.toSet(),
            valuePaths = maximumValueMutationPaths.toSet(),
        )
    }

    private fun validateCpuTarget(cpu: CpuDomain, target: Long, stock: Boolean = false) {
        require(target > 0) { "invalid CPU target for ${cpu.id}" }
        require(stock || target <= cpu.selectableMax) { "unsupported CPU target for ${cpu.id}" }
        require(stock || cpu.supportedFrequencies.isEmpty() || target == cpu.selectableMax || cpu.supportedFrequencies.contains(target)) {
            "unsupported CPU target for ${cpu.id}"
        }
    }

    private fun validateGpuTarget(gpu: GpuDomain, target: Long, allowedCeiling: Long = gpu.selectableMax) {
        require(target > 0) { "invalid GPU target" }
        require(target <= allowedCeiling || target == gpu.stockMax) { "unsupported GPU target" }
        require(gpu.supportedFrequencies.isEmpty() || target == gpu.selectableMax || gpu.supportedFrequencies.contains(target)) {
            "unsupported GPU target"
        }
    }

    /** Reconcile an OEM-raised minimum after the max batch, without delaying the normal path. */
    private fun reconcileMinimum(
        path: String,
        candidates: List<Long>,
        ceiling: Long,
        originalMode: Int,
        domainId: String,
    ) {
        val writable = writableMode(originalMode)
        var writeAccepted = false
        repeat(5) {
            val current = fs.read(path)?.toLongOrNull()
            if (current != null && current > 0 && current <= ceiling) return
            if (fs.mode(path) != writable) {
                check(fs.chmod(path, writable)) { "cannot make $path writable" }
            }
            for (candidate in candidates) {
                writeAccepted = fs.write(path, candidate.toString()) || writeAccepted
                val actual = fs.read(path)?.toLongOrNull()
                if (actual != null && actual > 0 && actual <= ceiling) break
            }
            // Give OEM policy workers a chance to settle, then verify the value
            // again. If it was raised, retry the bounded repair.
            Thread.sleep(40L)
            val settled = fs.read(path)?.toLongOrNull()
            if (settled != null && settled > 0 && settled <= ceiling) {
                check(fs.mode(path) == writable) { "permission verification failed for $domainId minimum" }
                return
            }
        }
        val actual = fs.read(path)?.toLongOrNull()
        error("invalid minimum for $domainId: actual=$actual ceiling=$ceiling writeAccepted=$writeAccepted")
    }

    private fun writableMode(mode: Int): Int = mode or 0x080

    private fun protectionMode(mode: Int, stock: Boolean): Int =
        if (stock) mode or 0x080 else mode and 0x16d

    private fun verifyMax(id: String, path: String, targets: List<Long>, mode: Int) {
        val actual = fs.read(path)?.toLongOrNull()
        check(actual != null && actual in targets) { "verification failed for $id: expected=${targets.joinToString("/")} actual=$actual" }
        val actualMode = fs.mode(path)
        check(actualMode == mode) { "permission verification failed for $id: expected=$mode actual=$actualMode" }
    }

    private fun restoreNode(
        path: String,
        value: Long,
        originalMode: Int?,
        restoreValue: Boolean = true,
        fallbackValues: List<Long> = emptyList(),
        onFallbackRestored: (Long) -> Unit = {},
    ): Boolean {
        var ok = true
        if (originalMode != null) ok = fs.chmod(path, writableMode(originalMode)) && ok
        if (restoreValue) {
            var restored = fs.write(path, value.toString()) && fs.read(path)?.toLongOrNull() == value
            if (!restored) {
                for (fallback in fallbackValues.distinct().filter { it > 0 && it != value }) {
                    if (fs.write(path, fallback.toString()) && fs.read(path)?.toLongOrNull() == fallback) {
                        onFallbackRestored(fallback)
                        restored = true
                        break
                    }
                }
            }
            ok = restored && ok
        }
        if (originalMode != null) ok = fs.chmod(path, originalMode) && ok
        if (restoreValue) ok = (fs.read(path)?.toLongOrNull() == value || fallbackValues.any { fs.read(path)?.toLongOrNull() == it }) && ok
        if (originalMode != null) ok = (fs.mode(path) == originalMode) && ok
        return ok
    }
}
