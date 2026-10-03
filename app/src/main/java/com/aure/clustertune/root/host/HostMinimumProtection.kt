package com.aure.clustertune.root.host

import android.os.Process
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class ProtectedMinimum(val policyId: String, val originalMode: Int)
internal data class MinimumProtectionRecord(val bootId: String, val nodes: List<ProtectedMinimum>)

internal interface MinimumProtectionStore {
    fun load(): MinimumProtectionRecord?
    fun save(record: MinimumProtectionRecord)
}

/** Root-private, atomic write-ahead record; the lifetime lock excludes replacement hosts. */
internal class FileMinimumProtectionStore(private val directory: File) : MinimumProtectionStore, AutoCloseable {
    private val lockFile = RandomAccessFile(File(directory, "lock"), "rw")
    private val lock = try {
        checkNotNull(lockFile.channel.tryLock()) { "another host owns CPU minimum protection" }
    } catch (failure: Throwable) {
        lockFile.close()
        throw failure
    }

    override fun load(): MinimumProtectionRecord? {
        val file = File(directory, "minimums.properties")
        if (!file.exists()) return null
        require(file.length() <= 8192) { "invalid minimum recovery record" }
        val properties = Properties().apply { file.inputStream().use(::load) }
        require(properties.getProperty("version") == "1") { "unknown minimum recovery format" }
        val count = properties.getProperty("count").toInt()
        require(count in 0..32) { "invalid minimum recovery count" }
        val nodes = (0 until count).map { index ->
            ProtectedMinimum(properties.getProperty("id.$index"), properties.getProperty("mode.$index").toInt())
        }
        return MinimumProtectionRecord(requireNotNull(properties.getProperty("boot")), nodes)
    }

    override fun save(record: MinimumProtectionRecord) {
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("boot", record.bootId)
            setProperty("count", record.nodes.size.toString())
            record.nodes.forEachIndexed { index, node ->
                setProperty("id.$index", node.policyId)
                setProperty("mode.$index", node.originalMode.toString())
            }
        }
        val temporary = File(directory, "minimums.tmp")
        FileOutputStream(temporary).use { output ->
            properties.store(output, "ClusterTune CPU minimum permission ownership")
            output.fd.sync()
        }
        Files.move(temporary.toPath(), File(directory, "minimums.properties").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun close() {
        lock.release()
        lockFile.close()
    }
}

/**
 * Owns minimum permissions independently of Auto Tune's ceiling checkpoint. We never save an
 * effective minimum as a value to restore: it may include a transient kernel boost. Releasing
 * permissions returns write access to the system. Firmware may then reapply its chosen floor;
 * without such a writer, the lowest supported minimum remains until another owner changes it.
 */
class HostMinimumProtection internal constructor(
    private val fs: HostFilesystem,
    private val store: MinimumProtectionStore,
    private val bootId: String,
) : AutoCloseable {
    private val owned = linkedMapOf<String, ProtectedMinimum>()
    private var recovered = false

    @Synchronized
    fun recoverOrThrow(capabilities: HostCapabilities) {
        if (recovered) return
        if (owned.isEmpty()) {
            val record = store.load()
            if (record != null && record.bootId == bootId) {
                require(record.nodes.map { it.policyId }.distinct().size == record.nodes.size)
                record.nodes.forEach { node ->
                    require(capabilities.cpus.any { it.id == node.policyId }) { "minimum recovery policy disappeared" }
                    require(node.originalMode in 0..0x1ff && node.originalMode and WRITE_BITS != 0) {
                        "invalid minimum recovery permissions"
                    }
                }
                record.nodes.forEach { owned[it.policyId] = it }
            }
        }
        releaseOrThrow(capabilities)
        // Discard records from previous boots: sysfs nodes and permissions have been recreated.
        persist()
        recovered = true
    }

    @Synchronized
    fun applyOrThrow(engine: HostApplyEngine, capabilities: HostCapabilities, request: ApplyRequest) {
        recoverOrThrow(capabilities)
        if (request.maximumsOnly) {
            HostProfileApplyDispatcher.applyOrThrow(engine, capabilities, request)
            return
        }
        // Reject malformed targets/identities before acquiring any minimum permissions.
        val targets = engine.validateProfileTargets(capabilities, request)
        val desired = capabilities.cpus.filterIndexed { index, _ -> !targets.cpuStock[index] }
        val previousIds = owned.keys.toSet()
        try {
            desired.forEach { cpu ->
                val index = capabilities.cpus.indexOf(cpu)
                val floor = cpu.minimumCandidates.filter { it > 0 && it <= targets.cpuSafetyCeilings[index] }.minOrNull()
                    ?: error("no supported minimum for ${cpu.id}")
                val existing = owned[cpu.id]
                if (existing != null) {
                    check(fs.mode(cpu.minPath) == protectedMode(existing.originalMode)) {
                        "CPU minimum protection changed externally for ${cpu.id}; release the profile before retrying"
                    }
                    return@forEach
                }
                val mode = fs.mode(cpu.minPath) ?: error("cannot read minimum permissions for ${cpu.id}")
                check(mode and WRITE_BITS != 0) { "${cpu.id} minimum is protected by another owner" }
                owned[cpu.id] = ProtectedMinimum(cpu.id, mode)
                persist() // Must reach disk before even the first chmod/write.
                check(fs.mutate(listOf(
                    HostMutation.Chmod(cpu.minPath, mode or OWNER_WRITE),
                    HostMutation.WriteCandidatesNoReadback(cpu.minPath, listOf(floor.toString())),
                    HostMutation.Chmod(cpu.minPath, protectedMode(mode)),
                ))) { "cannot protect ${cpu.id} minimum: ${fs.lastMutationError()}" }
                check(fs.mode(cpu.minPath) == protectedMode(mode)) { "minimum protection verification failed for ${cpu.id}" }
                // Effective readback may legitimately include a stronger, independent QoS vote.
            }
            engine.applyWithManagedCpuMinimumsOrThrow(capabilities, request, owned.keys.toSet())
        } catch (failure: Throwable) {
            val rollback = runCatching { releaseIds(capabilities, owned.keys.toSet() - previousIds) }
            val original = failure as? HostApplyFailure
            throw HostApplyFailure(
                if (rollback.isFailure) HostApplyPhase.ROLLBACK else original?.phase ?: HostApplyPhase.MUTATION,
                true, rollback.isSuccess && (original?.rollbackComplete != false),
                original?.indeterminate ?: false,
                "CPU minimum/profile update failed: ${failure.message}" +
                    (rollback.exceptionOrNull()?.let { "; minimum permission recovery pending: ${it.message}" } ?: ""), failure,
            )
        }
        // Stock ceilings must be in place before handing the corresponding minimum back to the system.
        try {
            releaseIds(capabilities, owned.keys.toSet() - desired.map { it.id }.toSet())
        } catch (failure: Throwable) {
            throw HostApplyFailure(HostApplyPhase.ROLLBACK, true, false, false,
                "Profile ceilings applied; CPU minimum permission release is pending: ${failure.message}", failure)
        }
    }

    @Synchronized
    fun releaseOrThrow(capabilities: HostCapabilities) = releaseIds(capabilities, owned.keys.toSet())

    private fun releaseIds(capabilities: HostCapabilities, ids: Set<String>) {
        val failures = mutableListOf<String>()
        ids.forEach { id ->
            val node = owned.getValue(id)
            val cpu = capabilities.cpus.single { it.id == id }
            try {
                val mode = fs.mode(cpu.minPath) ?: error("cannot read permissions")
                // Do not overwrite an unrelated permission change from another privileged tool.
                if (mode == protectedMode(node.originalMode) || mode == (node.originalMode or OWNER_WRITE)) {
                    check(fs.chmod(cpu.minPath, node.originalMode)) { "cannot restore permissions" }
                    check(fs.mode(cpu.minPath) == node.originalMode) { "permission restore did not stick" }
                }
                owned.remove(id)
                try { persist() } catch (failure: Throwable) { owned[id] = node; throw failure }
            } catch (failure: Throwable) {
                failures += "$id: ${failure.message}"
            }
        }
        check(failures.isEmpty()) { "minimum recovery pending: ${failures.joinToString()}" }
    }

    private fun persist() = store.save(MinimumProtectionRecord(bootId, owned.values.toList()))
    override fun close() { (store as? AutoCloseable)?.close() }

    companion object {
        private const val WRITE_BITS = 0x92
        private const val OWNER_WRITE = 0x80
        private fun protectedMode(mode: Int) = mode and 0x16d

        @JvmStatic
        fun createIfSupported(fs: HostFilesystem, ownerUid: Int): HostMinimumProtection? {
            if (Process.myUid() != 0) return null
            // Protection depends on privileged sysfs access and recoverable ownership, not an
            // OEM, model, or firmware fingerprint. Individual policy writes are verified below.
            val directory = File("/data/local/tmp/clustertune-minimums-$ownerUid")
            if (!directory.exists()) Os.mkdir(directory.path, 448) // 0700
            val stat = Os.lstat(directory.path)
            check(stat.st_uid == 0 && stat.st_mode and 0xf000 == 0x4000 && stat.st_mode and 0x1ff == 448) {
                "untrusted CPU minimum recovery directory"
            }
            val boot = requireNotNull(fs.read("/proc/sys/kernel/random/boot_id")) { "cannot identify boot for minimum recovery" }
            return HostMinimumProtection(fs, FileMinimumProtectionStore(directory), boot)
        }
    }
}
