package com.aure.clustertune.root.host

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

interface HostFilesystem {
    fun read(path: String): String?
    fun write(path: String, value: String): Boolean
    fun mode(path: String): Int?
    fun chmod(path: String, mode: Int): Boolean
    fun exists(path: String): Boolean

    /**
     * Whether this process could successfully chmod [path].
     *
     * chmod requires ownership or CAP_FOWNER. The host may be running as
     * a non-root uid such as system, and some sysfs nodes
     * are owned by root — notably /sys/class/kgsl/kgsl-3d0/max_gpuclk, which
     * produced "chmod ... to 0644: Operation not permitted" and aborted the
     * whole apply, CPU work included.
     *
     * Defaults to true so existing implementations and fakes are unchanged.
     */
    fun canChmod(path: String): Boolean = true

    /**
     * Whether this process can realistically change [path]: either it is
     * already writable, or we own it and can chmod it writable.
     */
    fun canControl(path: String): Boolean = true

    /**
     * `mode uid:gid` for [path], or null if it cannot be stat'd.
     *
     * Used only to annotate failures. The long-running "C0 applies on some
     * devices but not others" reports were never diagnosable from the app side,
     * because the failure message said what went wrong but not what the node
     * looked like — and the whole hypothesis was that policy0 ships different
     * permissions or ownership from policy3/7 on affected devices.
     */
    fun describe(path: String): String? = null

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

/**
 * Write attempts before a read-back mismatch is treated as a real failure.
 * Covers a vendor service rewriting a cpufreq node between our write and our
 * verification; a genuinely refused write still fails on every attempt.
 */
private const val WRITE_ATTEMPTS = 3

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

    override fun canChmod(path: String): Boolean = runCatching {
        val uid = Os.getuid()
        uid == 0 || Os.stat(path).st_uid == uid
    }.getOrDefault(false)

    override fun canControl(path: String): Boolean = runCatching {
        canChmod(path) || Os.access(path, OsConstants.W_OK)
    }.getOrDefault(false)

    override fun describe(path: String): String? = runCatching {
        val stat = Os.stat(path)
        "0" + Integer.toOctalString(stat.st_mode and 0x1ff) + " ${stat.st_uid}:${stat.st_gid}"
    }.getOrNull()

    override fun mutate(operations: List<HostMutation>): Boolean {
        mutationError = null
        mutationFailure = null
        if (operations.isEmpty() || operations.size > 96) {
            mutationError = "invalid operation count"
            return false
        }
        // Per-node isolation.
        //
        // Every mutation used to share one `set -e` batch, so the first node that
        // refused a write aborted everything after it — C3 and C7 were never
        // written because C0 failed, which is the wrong trade: a policy that can
        // be applied should be applied. The working 1.0.2-era build wrapped each
        // policy for exactly this reason.
        //
        // Failures are recorded on stderr as `ct-node-failed:<path>` instead of
        // aborting, and the read-back verification that follows is what decides
        // overall success — it already names the offending policy.
        val script = buildString {
            append("set -e; ")
            operations.forEach { operation ->
                when (operation) {
                    is HostMutation.Chmod -> {
                        // Non-fatal. Under `set -e` a refused chmod used to abort
                        // the entire batch, so one un-chmod-able node (a
                        // root-owned GPU path, when the host is not running as root)
                        // took every CPU write down with it. A chmod that
                        // actually mattered still surfaces, because every write
                        // is read back and verified immediately after.
                        append("chmod ")
                            .append(shellQuote(Integer.toOctalString(operation.mode and 0x1ff)))
                            .append(' ')
                            .append(shellQuote(operation.path))
                            .append(" 2>/dev/null || :; ")
                    }

                    is HostMutation.Write -> {
                        if (!operation.value.matches(Regex("[0-9]+"))) {
                            mutationError = "invalid numeric value"
                            return false
                        }
                        val quotedPath = shellQuote(operation.path)
                        val quotedValue = shellQuote(operation.value)
                        // Retry, then report — never abort the batch.
                        //
                        // A cpufreq node is shared: the vendor's own perf and
                        // thermal services write these same nodes, so a rewrite
                        // landing between our write and our read-back is
                        // indistinguishable from a rejected write. Retrying
                        // absorbs that race; a genuinely refused write still
                        // fails every attempt and is reported on stderr for the
                        // read-back verification to act on.
                        //
                        // Everything here is `if`-based on purpose. Under
                        // `set -e` a trailing `[ x ] && break` evaluates to 1
                        // when the test is false, which aborts the entire
                        // script — so the earlier `&&` form silently turned a
                        // single failing node into "nothing applied at all".
                        append("ct_i=0; while :; do echo ")
                            .append(quotedValue)
                            .append(" > ")
                            .append(quotedPath)
                            .append(" 2>/dev/null || :; if [ \"\$(cat ")
                            .append(quotedPath)
                            .append(" 2>/dev/null)\" = ")
                            .append(quotedValue)
                            .append(" ]; then break; fi; ct_i=\$((ct_i+1)); if [ \"\$ct_i\" -ge ")
                            .append(WRITE_ATTEMPTS.toString())
                            .append(" ]; then break; fi; sleep 0.05; done; if [ \"\$(cat ")
                            .append(quotedPath)
                            .append(" 2>/dev/null)\" != ")
                            .append(quotedValue)
                            .append(" ]; then printf '%s\\n' ")
                            .append(shellQuote("ct-node-failed:" + operation.path))
                            .append(" >&2; fi; ")
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
                            .append(" 2>/dev/null; then ok=1; break; fi; done; [ \"\$ok\" -eq 1 ] || printf '%s\\n' ")
                            .append(shellQuote("ct-node-failed:" + operation.path))
                            .append(" >&2; ")
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
                            .append(" ] || printf '%s\\n' ")
                            .append(shellQuote("ct-node-failed:" + operation.path))
                            .append(" >&2; fi; ")
                    }
                }
            }
        }
        if (script.length > 8192) {
            mutationError = "script too long"
            return false
        }
        return dispatchLocalScript(script)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun dispatchLocalScript(command: String): Boolean {
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
                    mutationError = "status=${process.exitValue()} output=${output.toByteArray().toString(Charsets.UTF_8).take(512)}"
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

}

class HostApplyEngine(private val fs: HostFilesystem) {
    fun applyOrThrow(capabilities: HostCapabilities, request: ApplyRequest) {
        apply(capabilities, request).getOrElse { failure ->
            if (failure is HostApplyFailure) throw failure
            throw HostApplyFailure(HostApplyPhase.PREFLIGHT, false, true, false, failure.message ?: "host preflight failed", failure)
        }
    }

    @Synchronized
    fun apply(capabilities: HostCapabilities, request: ApplyRequest): Result<Unit> = runCatching {
        require(request.cpuMax.size == capabilities.cpus.size)
        require(request.gpuMax == null || capabilities.gpu != null) {
            "GPU target requested but no GPU domain is available"
        }
        request.gpuId?.let { require(capabilities.gpu?.id == it) { "GPU identity mismatch" } }
        request.gpuMaxPath?.let { require(capabilities.gpu?.maxPath == it) { "GPU path mismatch" } }
        // The hint is only needed when the GPU does not enumerate selectable
        // frequencies. On enumerated domains the cached selectable ceiling is
        // authoritative; ignore the hint rather than rejecting an otherwise
        // valid request.
        val stabilizedStockCeiling = request.stabilizedStockCeiling
            ?.takeIf { capabilities.gpu?.supportedFrequencies?.isEmpty() == true }
        stabilizedStockCeiling?.let {
            require(it > 0L && it <= 4_000_000_000L) { "invalid stabilized GPU stock ceiling" }
            require(request.gpuId != null && request.gpuMaxPath != null) { "stabilized GPU stock ceiling requires domain identity" }
        }
        val cpuStock = capabilities.cpus.mapIndexed { index, cpu ->
            request.resetToStock || request.cpuMax[index] >= cpu.selectableMax
        }
        val cpuStockCandidates = capabilities.cpus.map { cpu ->
            listOf(cpu.stockMax.takeIf { it > 0 } ?: cpu.selectableMax, cpu.selectableMax).distinct()
        }
        val expected = capabilities.cpus.mapIndexed { index, cpu ->
            if (cpuStock[index]) cpu.stockMax.takeIf { it > 0 } ?: cpu.selectableMax else request.cpuMax[index]
        }
        val safetyCeilings = capabilities.cpus.mapIndexed { index, cpu ->
            if (cpuStock[index]) cpuStockCandidates[index].minOrNull() ?: cpu.selectableMax else expected[index]
        }
        val gpuStock = request.gpuMax?.let { requested ->
            val gpu = capabilities.gpu
            request.resetToStock || (gpu != null && requested >= (stabilizedStockCeiling ?: gpu.selectableMax))
        } ?: false
        val expectedGpu = request.gpuMax?.let {
            val gpu = capabilities.gpu
            if (gpu != null && (request.resetToStock || it >= gpu.selectableMax) && (gpuStock || stabilizedStockCeiling == null)) {
                stabilizedStockCeiling?.takeIf { value -> value >= gpu.selectableMax } ?: gpu.selectableMax.takeIf { value -> value > 0 }
            } else it
        }
        require(request.gpuMax == null || expectedGpu != null) {
            "GPU target cannot be resolved to a selectable ceiling"
        }
        val original = capabilities.cpus.map {
            fs.read(it.maxPath)?.toLongOrNull() ?: error("cannot read ${it.maxPath}")
        }
        val originalMins = capabilities.cpus.map {
            fs.read(it.minPath)?.toLongOrNull() ?: error("cannot read ${it.minPath}")
        }
        val originalModes = capabilities.cpus.map {
            fs.mode(it.maxPath) ?: error("cannot read mode for ${it.maxPath}")
        }
        val originalMinModes = capabilities.cpus.map {
            fs.mode(it.minPath) ?: error("cannot read mode for ${it.minPath}")
        }
        val originalGpu = if (expectedGpu != null) {
            capabilities.gpu?.let {
                fs.read(it.maxPath)?.toLongOrNull() ?: error("cannot read ${it.maxPath}")
            }
        } else {
            null
        }
        val originalGpuMin = if (expectedGpu != null) {
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
        val originalGpuMinMode = if (expectedGpu != null) {
            capabilities.gpu?.minPath?.let {
                fs.mode(it) ?: error("cannot read mode for $it")
            }
        } else {
            null
        }
        val cpuNeedsMinRepair = originalMins.mapIndexed { index, value -> value <= 0 || value > safetyCeilings[index] }
        val gpuNeedsMinRepair = expectedGpu != null && originalGpuMin != null &&
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
            val gpuAcceptedCeilings = if (gpuStock) {
                listOfNotNull(stabilizedStockCeiling?.takeIf { it > 0 }, gpu.stockMax.takeIf { it > 0 }, gpu.selectableMax).distinct()
            } else listOf(expectedGpu)
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
        )
        val journal = LinkedHashMap<String, JournalEntry>()
        var gpuMutationTarget: Long? = null
        fun journalBeforeMutation(path: String, value: Long, mode: Int, isMax: Boolean, pairedMaxPath: String?, restoreFallbacks: List<Long> = emptyList()) {
            journal.putIfAbsent(path, JournalEntry(path, value, mode, isMax, pairedMaxPath, restoreFallbacks))
        }
        var mutationStarted = false
        try {
            val cpuMaxMutations = mutableListOf<HostMutation>()
            capabilities.cpus.forEachIndexed { index, cpu ->
                val target = expected[index]
                if (cpuNeedsMinRepair[index]) {
                    journalBeforeMutation(cpu.minPath, originalMins[index], originalMinModes[index], false, cpu.maxPath)
                    journalBeforeMutation(cpu.maxPath, original[index], originalModes[index], true, null, if (original[index] > cpu.selectableMax) listOf(cpu.selectableMax) else emptyList())
                    cpuMaxMutations += HostMutation.Chmod(cpu.minPath, writableMode(originalMinModes[index]))
                    cpuMaxMutations += HostMutation.WriteCandidatesNoReadback(
                        cpu.minPath,
                        cpu.minimumCandidates.filter { it > 0 && it <= safetyCeilings[index] }.distinct().sorted().map { it.toString() }
                    )
                    // Leave the floor readable to non-owners. These ship 0660
                    // system:system on the Odin 2 Mini, so the app could not read
                    // them at all and every floor read had to go back through the
                    // privileged host. Other-read costs nothing — the floor is
                    // only ever read — and keeps the vendor's owner+group write.
                    cpuMaxMutations += HostMutation.Chmod(
                        cpu.minPath,
                        minimumProtectionMode(originalMinModes[index]),
                    )
                } else {
                    journalBeforeMutation(cpu.maxPath, original[index], originalModes[index], true, null, if (original[index] > cpu.selectableMax) listOf(cpu.selectableMax) else emptyList())
                }
                cpuMaxMutations += HostMutation.Chmod(cpu.maxPath, writableMode(originalModes[index]))
                cpuMaxMutations += if (cpuStock[index]) {
                    val candidates = cpuStockCandidates[index]
                    HostMutation.WritePreferred(cpu.maxPath, candidates.first().toString(), candidates.last().toString())
                } else HostMutation.Write(cpu.maxPath, target.toString())
                cpuMaxMutations += HostMutation.Chmod(cpu.maxPath, protectionMode(originalModes[index], cpuStock[index]))
            }
            gpuMutationTarget = expectedGpu
            capabilities.gpu?.let { gpu ->
                gpuMutationTarget?.let { target ->
                    if (gpu.minPath != null && gpuNeedsMinRepair) {
                        val minPath = gpu.minPath
                        val minMode = originalGpuMinMode ?: error("cannot read mode for $minPath")
                        journalBeforeMutation(minPath, originalGpuMin ?: error("cannot read $minPath"), minMode, false, gpu.maxPath)
                        journalBeforeMutation(gpu.maxPath, originalGpu ?: error("cannot read ${gpu.maxPath}"), originalGpuMode ?: error("cannot read mode for ${gpu.maxPath}"), true, null, if ((originalGpu ?: 0L) > gpu.selectableMax) listOf(gpu.selectableMax) else emptyList())
                        cpuMaxMutations += HostMutation.Chmod(minPath, writableMode(minMode))
                        cpuMaxMutations += HostMutation.WriteCandidatesNoReadback(
                            minPath,
                        (listOf(gpu.observedMin) + gpu.supportedFrequencies).filter { it > 0 && it <= (if (gpuStock) listOfNotNull(stabilizedStockCeiling?.takeIf { it > 0 }, gpu.stockMax.takeIf { it > 0 }, gpu.selectableMax).minOrNull() ?: target else target) }.distinct().sorted().map { it.toString() }
                        )
                        // Same final mode as CPU floors, and the same mode the
                        // verification below asserts. Only reachable with root or
                        // PServer, since the GPU domain is dropped at uid=system.
                        cpuMaxMutations += HostMutation.Chmod(minPath, minimumProtectionMode(minMode))
                    } else {
                        journalBeforeMutation(gpu.maxPath, originalGpu ?: error("cannot read ${gpu.maxPath}"), originalGpuMode ?: error("cannot read mode for ${gpu.maxPath}"), true, null, if ((originalGpu ?: 0L) > gpu.selectableMax) listOf(gpu.selectableMax) else emptyList())
                    }
                    cpuMaxMutations += HostMutation.Chmod(gpu.maxPath, writableMode(originalGpuMode ?: error("cannot read mode for ${gpu.maxPath}")))
                    cpuMaxMutations += if (gpuStock) {
                        val preferred = stabilizedStockCeiling?.takeIf { it > 0 } ?: gpu.stockMax.takeIf { it > 0 } ?: gpu.selectableMax
                        HostMutation.WritePreferred(gpu.maxPath, preferred.toString(), gpu.selectableMax.toString())
                    } else HostMutation.Write(gpu.maxPath, target.toString())
                    cpuMaxMutations += HostMutation.Chmod(gpu.maxPath, protectionMode(originalGpuMode ?: error("cannot read mode for ${gpu.maxPath}"), gpuStock))
                }
            }
            mutationStarted = true
            check(fs.mutate(cpuMaxMutations)) {
                // Include each node's mode/owner and this process's uid. Without
                // it a failure report says only that a write was refused, and the
                // one thing needed to act on it — whether that node differs from
                // the ones that worked — is missing.
                val nodes = capabilities.cpus.joinToString("; ") { cpu ->
                    "${cpu.id} max=${fs.describe(cpu.maxPath) ?: "?"} min=${fs.describe(cpu.minPath) ?: "?"}"
                }
                val gpuNode = capabilities.gpu?.let { " gpu=${fs.describe(it.maxPath) ?: "?"}" } ?: ""
                "cannot apply CPU maximum mutations: ${fs.lastMutationError() ?: "unknown failure"}" +
                    " [uid=${runCatching { Os.getuid() }.getOrDefault(-1)} $nodes$gpuNode]"
            }
            capabilities.cpus.forEachIndexed { index, cpu ->
                val target = expected[index]
                val finalMode = protectionMode(originalModes[index], cpuStock[index])
                val accepted = if (cpuStock[index]) cpuStockCandidates[index] else listOf(target)
                verifyMax(cpu.id, cpu.maxPath, accepted, finalMode)
                if (fs.read(cpu.minPath)?.toLongOrNull()?.let { it > 0 && it <= safetyCeilings[index] } != true) {
                    journalBeforeMutation(cpu.minPath, originalMins[index], originalMinModes[index], false, cpu.maxPath)
                    reconcileMinimum(
                        path = cpu.minPath,
                        candidates = cpu.minimumCandidates.filter { it > 0 && it <= safetyCeilings[index] }.distinct().sorted(),
                        ceiling = safetyCeilings[index],
                        originalMode = originalMinModes[index],
                        domainId = cpu.id,
                    )
                }
                if (cpuNeedsMinRepair[index]) check(fs.mode(cpu.minPath) == minimumProtectionMode(originalMinModes[index])) { "permission verification failed for ${cpu.id} minimum" }
            }
            capabilities.gpu?.let { gpu: GpuDomain ->
                // A null GPU value is an explicit "leave untouched" request, including when
                // the CPU transaction is a Stock reset. This preserves compatibility with
                // CPU-only profiles and callers.
                val target = gpuMutationTarget
                target?.let { requested: Long ->
                    val finalMode = protectionMode(originalGpuMode ?: error("cannot read mode for ${gpu.maxPath}"), gpuStock)
                    val accepted = if (gpuStock) listOfNotNull(stabilizedStockCeiling?.takeIf { it > 0 }, gpu.stockMax.takeIf { it > 0 }, gpu.selectableMax).distinct() else listOf(requested)
                    verifyMax(gpu.id, gpu.maxPath, accepted, finalMode)
                    val actualAcceptedMax = fs.read(gpu.maxPath)?.toLongOrNull()
                        ?: error("cannot read ${gpu.maxPath}")
                    gpu.minPath?.let {
                        if (fs.read(it)?.toLongOrNull()?.let { value -> value > 0 && value <= actualAcceptedMax } != true) {
                            journalBeforeMutation(it, originalGpuMin ?: error("cannot read $it"), originalGpuMinMode ?: error("cannot read mode for $it"), false, gpu.maxPath)
                            reconcileMinimum(
                                path = it,
                                candidates = (listOf(gpu.observedMin) + gpu.supportedFrequencies).filter { value -> value > 0 && value <= actualAcceptedMax }.distinct().sorted(),
                                ceiling = actualAcceptedMax,
                                originalMode = originalGpuMinMode ?: error("cannot read mode for $it"),
                                domainId = gpu.id,
                            )
                        }
                        if (gpuNeedsMinRepair) check(fs.mode(it) == minimumProtectionMode(originalGpuMinMode ?: error("cannot read mode for $it"))) { "permission verification failed for ${gpu.id} minimum" }
                    }
                }
            }
        } catch (t: Throwable) {
            val dispatchFailure = fs.lastMutationFailure() as? HostDispatchFailure
            if (dispatchFailure?.indeterminate == true) {
                throw HostApplyFailure(
                    phase = HostApplyPhase.MUTATION,
                    mutationStarted = mutationStarted,
                    rollbackComplete = false,
                    indeterminate = true,
                    message = "apply completion is indeterminate; state left untouched for reconciliation",
                    cause = t,
                )
            }
            val rollbackFailures = mutableListOf<String>()
            val maxRestored = mutableMapOf<String, Boolean>()
            // Restore ceilings before minima so a minimum whose original value is
            // above the temporary ceiling can be restored safely. This also
            // covers minima discovered as invalid after the max batch.
            val rollbackEntries = journal.values.toList().let { entries ->
                entries.filter { it.isMax }.asReversed() + entries.filterNot { it.isMax }.asReversed()
            }
            rollbackEntries.forEach { entry ->
                if (entry.isMax) {
                    val restored = restoreNode(entry.path, entry.value, entry.mode, fallbackValues = entry.restoreFallbacks)
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
                    indeterminate = (fs.lastMutationFailure() as? HostDispatchFailure)?.indeterminate == true,
                    message = "apply failed: ${t.message}; rollback incomplete for ${rollbackFailures.joinToString()}",
                    cause = t,
                )
            }
            if (t is HostApplyFailure) throw t
            throw HostApplyFailure(
                phase = if (!mutationStarted) HostApplyPhase.PREFLIGHT else HostApplyPhase.MUTATION,
                mutationStarted = mutationStarted,
                rollbackComplete = true,
                indeterminate = (fs.lastMutationFailure() as? HostDispatchFailure)?.indeterminate == true,
                message = t.message ?: "host apply failed",
                cause = t,
            )
        }
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
        // Must match the mode the batch leaves behind, including other-read.
        // These are asserted against each other, so a floor repaired here and a
        // floor repaired in the mutation batch have to agree exactly — v25
        // changed one and not the other, which is what produced
        // "permission verification failed for policy3 minimum" on any preset
        // whose ceiling forced a floor repair.
        val writable = minimumProtectionMode(originalMode)
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

    /**
     * Final mode for a ceiling node. Always keeps **other-read** (0004).
     *
     * Deriving purely from the current mode reproduces the original C0 bug: a
     * node that ships 0660 — which some Odin 2 Mini units do for policy0 —
     * becomes `0660 and 0555` = **0440**, readable by nobody but its owner. The
     * write still succeeds, so it fails silently, and because sysfs modes
     * survive until reboot every later apply re-applies the same broken mode.
     * That is precisely the "C0 applies but reads back null" report.
     *
     * The 1.0.2-era build fixed this by writing an absolute 444 / 644. Deriving
     * and then forcing other-read reaches the same place (0664 -> 0444,
     * 0660 -> 0444) while preserving any group-write the vendor shipped, so its
     * own services can still manage the node.
     */
    private fun protectionMode(mode: Int, stock: Boolean): Int =
        (if (stock) mode or 0x080 else mode and 0x16d) or 0x004

    /** Floors are left writable *and* readable — see [protectionMode]. */
    private fun minimumProtectionMode(mode: Int): Int = writableMode(mode) or 0x004

    private fun verifyMax(id: String, path: String, targets: List<Long>, mode: Int) {
        val actual = fs.read(path)?.toLongOrNull()
        check(actual != null && actual in targets) { "verification failed for $id: expected=${targets.joinToString("/")} actual=$actual" }
        val actualMode = fs.mode(path)
        // Only assert the protection mode when we could have set it. If chmod is
        // not permitted for us on this node, the mode simply stays as the kernel
        // shipped it, which is no worse than stock — failing the apply over it
        // would discard a write that actually succeeded.
        check(actualMode == mode || !fs.canChmod(path)) {
            "permission verification failed for $id: expected=$mode actual=$actualMode"
        }
    }

    private fun restoreNode(path: String, value: Long, originalMode: Int?, restoreValue: Boolean = true, fallbackValues: List<Long> = emptyList()): Boolean {
        var ok = true
        if (originalMode != null) ok = fs.chmod(path, writableMode(originalMode)) && ok
        if (restoreValue) {
            var restored = fs.write(path, value.toString()) && fs.read(path)?.toLongOrNull() == value
            if (!restored) {
                for (fallback in fallbackValues.distinct().filter { it > 0 && it != value }) {
                    if (fs.write(path, fallback.toString()) && fs.read(path)?.toLongOrNull() == fallback) {
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
