package com.aure.clustertune.root.host

import android.content.Context
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.root.PrivilegedExecutionResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.math.min

private class HostDexRuntime(private val context: Context) {
    fun extract(generation: Long): List<File> {
        val dir = File(context.codeCacheDir, "clustertune-host").apply { mkdirs() }
        val marker = File(dir, ".generation")
        val metadata = marker.takeIf { it.isFile }?.runCatching { readLines() }?.getOrNull()
        if (metadata?.firstOrNull()?.toLongOrNull() == generation) {
            val cached = metadata.drop(1).filter { it.matches(Regex("classes(\\d*)\\.dex")) }
            if (cached.isNotEmpty() && cached.all { File(dir, it).isFile && File(dir, it).length() > 0L }) {
                return cached.map { File(dir, it) }
            }
        }
        // Extract all dex entries in one pass. A marker is written only after every output is
        // complete, so an interrupted extraction cannot be mistaken for a valid cache.
        val outputs = LinkedHashMap<String, File>()
        val ordered = ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val names = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                .map { it.name }
                .sortedWith(compareBy<String> { if (it == "classes.dex") 0 else 1 }
                    .thenBy { it.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 0 })
                .toList()
            require(names.isNotEmpty()) { "application dex missing" }
            names.forEach { name ->
                val tmp = File.createTempFile(".${name}.", ".tmp", dir)
                outputs[name] = tmp
                val entry = zip.getEntry(name) ?: error("missing dex entry $name")
                zip.getInputStream(entry).use { input -> tmp.outputStream().use(input::copyTo) }
                check(tmp.length() > 0L) { "empty dex entry $name" }
            }
            names
        }
        try {
            ordered.forEach { name ->
                val out = File(dir, name)
                val tmp = outputs.getValue(name)
                out.setWritable(true, true)
                check(!out.exists() || out.delete()) { "unable to replace ${out.path}" }
                check(tmp.renameTo(out)) { "unable to install ${out.path}" }
                out.setReadable(true, false)
                out.setWritable(false, false)
            }
            marker.writeText(generation.toString() + "\n" + ordered.joinToString("\n"))
        } finally {
            outputs.values.forEach { it.delete() }
        }
        return ordered.map { File(dir, it) }
    }
}

class ClusterTuneHostClient(
    private val context: Context,
    private val resolver: PrivilegedExecutionResolver,
) {
    /** The lifecycle method currently selected for starting the host. */
    val selectedMethodId: String?
        get() = attachedMethod ?: resolver.configuredMethodIdSnapshot

    private val lock = START_LOCKS.computeIfAbsent(Process.myUid()) { Any() }
    private val serviceName = HostProtocol.SERVICE_PREFIX + Process.myUid()
    private val generation = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    @Volatile private var binder: IBinder? = null
    @Volatile private var attachedMethod: String? = null
    private var death: IBinder.DeathRecipient? = null

    fun ensureStarted(timeoutMs: Long = 3000): Result<Unit> = synchronized(lock) {
        runCatching {
            val configuredMethod = resolver.configuredMethodIdSnapshot
            binder?.takeIf { it.isBinderAlive && (configuredMethod == null || attachedMethod == configuredMethod) }?.let {
                return@runCatching
            }
            detach()
            (HostRendezvous.lookup(serviceName) ?: service())?.let { existing ->
                val legacyService = HostRendezvous.lookup(serviceName) == null
                val pingAttempt = runCatching { ping(existing, configuredMethod) }
                val pingFailure = pingAttempt.exceptionOrNull()
                if (pingAttempt.isSuccess) {
                    if (!legacyService) sendLease(existing)
                    attach(existing, pingAttempt.getOrThrow().method, requireLegacyService = legacyService)
                    return@runCatching
                }
                val remoteVersion = (pingFailure as? HostProtocolMismatch)?.remoteVersion
                if (remoteVersion != null || pingFailure is HostIdentityMismatch) {
                    runCatching { transact(existing, HostProtocol.STOP, wireVersion = remoteVersion ?: HostProtocol.VERSION, expectedVersion = remoteVersion ?: HostProtocol.VERSION) { } }
                    detach(existing)
                    check(waitForServiceReplacement(existing, timeoutMs)) { "previous privileged host is still registered" }
                } else if (existing.isBinderAlive) {
                    error("existing privileged host did not respond")
                } else {
                    detach(existing)
                }
            }
            val selection = resolver.selectionSnapshot()
            val method = selection.methodId ?: error("no privileged execution method")
            val dex = HostDexRuntime(context).extract(generation)
            // Keep the classpath as a raw colon-delimited value. The launcher quotes the
            // complete assignment once; quoting each entry here would produce literal quote
            // characters in CLASSPATH and prevent app_process from loading the host.
            val classpath = dex.joinToString(":") { it.absolutePath }
            val dexDirectory = dex.first().parentFile!!.absolutePath
            val handoffNonce = HostRendezvous.prepare(context, serviceName, generation, method)
            File(dexDirectory, "host-startup.log").apply { writeText("") }
            val launcher = File(dexDirectory, "launch-host-${System.nanoTime().toString(16)}.sh")
            launcher.writeText("#!/system/bin/sh\nCT_HOST_LOG='./host-startup.log' CLASSPATH='${classpath.replace("'", "'\\''")}' /system/bin/app_process /system/bin ${ClusterTuneHostEntry::class.java.name} '${serviceName.replace("'", "'\\''")}' ${Process.myUid()} $generation '${method.replace("'", "'\\''")}' '${context.packageName.replace("'", "'\\''")}' '${handoffNonce.replace("'", "'\\''")}' >'./host-startup.log' 2>&1 </dev/null &\n")
            launcher.setExecutable(true, false)
            launcher.setWritable(false, false)
            try {
                resolver.launchHost(
                    selection,
                    HostLaunchRequest(
                        workingDirectory = dexDirectory,
                        launcherScript = launcher.name,
                    ),
                ).getOrThrow()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val local = HostRendezvous.lookup(serviceName)
                    local?.let { found ->
                        val legacyService = false
                        val pingAttempt = runCatching { ping(found, method) }
                        val pingFailure = pingAttempt.exceptionOrNull()
                        if (pingAttempt.isSuccess) {
                            sendLease(found)
                            attach(found, pingAttempt.getOrThrow().method, requireLegacyService = legacyService)
                            return@runCatching
                        } else if (pingFailure is HostProtocolMismatch || pingFailure is HostIdentityMismatch) {
                            runCatching {
                                transact(
                                    found,
                                    HostProtocol.STOP,
                                wireVersion = (pingFailure as? HostProtocolMismatch)?.remoteVersion ?: HostProtocol.VERSION,
                                expectedVersion = (pingFailure as? HostProtocolMismatch)?.remoteVersion ?: HostProtocol.VERSION,
                                ) { }
                            }
                        }
                    }
                    Thread.sleep(40)
                }
                val startup = File(dexDirectory, "host-startup.log").takeIf { it.isFile }
                    ?.runCatching { readText().takeLast(4096) }?.getOrNull().orEmpty()
                error("privileged host registration failed${startup.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            } finally {
                launcher.delete()
                HostRendezvous.clearPending(serviceName, handoffNonce)
            }
        }
    }

    fun readSnapshot(): Result<HostSnapshot> = call(HostProtocol.READ_SNAPSHOT) { p ->
        val epoch = p.readLong()
        val capabilities = readCapabilitiesPayload(p)
        val state = readStatePayload(p)
        HostSnapshot(capabilities, state, epoch)
    }
    private fun readCapabilitiesPayload(p: Parcel): HostCapabilities {
        val count = readCount(p, 64, "CPU domains")
        require(count > 0) { "host returned no CPU domains" }
        val cpus = List(count) {
            val id = readRequiredString(p, "CPU id")
            val min = readRequiredString(p, "CPU minimum path")
            val max = readRequiredString(p, "CPU maximum path")
            val candidates = readLongList(p, "CPU minimum candidates")
            val supported = readLongList(p, "CPU supported frequencies")
            val stock = p.readLong()
            val observed = p.readLong()
            val observedMin = p.readLong()
            val selectable = p.readLong()
            val current = p.readLong()
            CpuDomain(id, min, max, null, candidates, supported, stock, observed, observedMin, selectable, current)
        }
        val gpuFlag = p.readInt()
        require(gpuFlag == 0 || gpuFlag == 1) { "invalid GPU presence flag" }
        val gpu = if (gpuFlag == 1) {
            val id = readRequiredString(p, "GPU id")
            val minPath = p.readString()
            val maxPath = readRequiredString(p, "GPU maximum path")
            val curPath = p.readString()
            val frequencies = readLongList(p, "GPU supported frequencies")
            GpuDomain(
                id = id,
                minPath = minPath,
                maxPath = maxPath,
                curPath = curPath,
                supportedFrequencies = frequencies,
                stockMax = p.readLong(),
                observedMax = p.readLong(),
                observedMin = p.readLong(),
                selectableMax = p.readLong(),
                currentMax = p.readLong(),
            )
        } else {
            null
        }
        return HostCapabilities(cpus, gpu)
    }
    private fun readStatePayload(p: Parcel): HostState {
        val count = readCount(p, 64, "CPU state")
        require(count > 0) { "host returned no CPU state" }
        val cpus = List(count) { p.readLong() }
        val mins = List(count) { p.readLong() }
        val currents = List(count) { p.readLong() }
        val gpuFlag = p.readInt()
        require(gpuFlag == 0 || gpuFlag == 1) { "invalid GPU presence flag" }
        val gpu = if (gpuFlag == 1) p.readLong() else null
        val gpuMin = if (gpuFlag == 1) decodeOptionalHostValue(p.readLong()) else null
        val gpuCur = if (gpuFlag == 1) decodeOptionalHostValue(p.readLong()) else null
        return HostState(cpus, mins, currents, gpu, gpuMin, gpuCur)
    }
    fun applyProfile(request: ApplyRequest): Result<HostState> = call(HostProtocol.APPLY_PROFILE, writer = { p ->
        p.writeInt(request.cpuMax.size); request.cpuMax.forEachIndexed { i, value -> p.writeString(request.cpuIds.getOrNull(i).orEmpty()); p.writeLong(value) }
        p.writeInt(if(request.gpuMax!=null)1 else 0); request.gpuMax?.let(p::writeLong); p.writeInt(if(request.resetToStock)1 else 0)
        p.writeString(request.gpuId); p.writeString(request.gpuMaxPath); p.writeLong(request.stabilizedStockCeiling ?: -1L)
    }, reader = { p -> readStatePayload(p) })
    fun stop(): Result<Unit> = call(HostProtocol.STOP) { Unit }.also { if (it.isSuccess) detach() }
    private fun <T> call(code: Int, reader: (Parcel) -> T): Result<T> = call(code, {}, reader)
    private fun <T> call(
        code: Int,
        writer: (Parcel) -> Unit = {},
        reader: (Parcel) -> T,
    ): Result<T> = runCatching {
        ensureStarted().getOrThrow()
        transactWithRetry(code, writer, reader)
    }

    private fun <T> transactWithRetry(
        code: Int,
        writer: (Parcel) -> Unit,
        reader: (Parcel) -> T,
    ): T = try {
        transact(binder ?: error("host unavailable"), code, writer = writer, reader = reader)
    } catch (dead: DeadObjectException) {
        if (code == HostProtocol.APPLY_PROFILE) {
            throw RemoteHostApplyFailure(
                HostApplyPhase.MUTATION,
                mutationStarted = true,
                rollbackComplete = false,
                indeterminate = true,
                message = "privileged host transport lost during mutation",
            )
        }
        detach()
        ensureStarted().getOrThrow()
        transact(binder ?: error("host unavailable"), code, writer = writer, reader = reader)
    }

    private fun <T> transact(
        target: IBinder,
        code: Int,
        wireVersion: Int = HostProtocol.VERSION,
        expectedVersion: Int = HostProtocol.VERSION,
        writer: (Parcel) -> Unit = {},
        reader: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(HostProtocol.DESCRIPTOR)
            data.writeInt(wireVersion)
            writer(data)
            check(target.transact(code, data, reply, 0))
            val remoteVersion = reply.readInt()
            if (remoteVersion != expectedVersion) {
                throw HostProtocolMismatch(remoteVersion)
            }
            if (reply.readInt() == 0) {
                val type = reply.readString().orEmpty()
                val message = reply.readString().orEmpty()
                if (type == HostApplyFailure::class.java.name && reply.dataAvail() >= 16) {
                    val phase = HostApplyPhase.values().getOrElse(reply.readInt()) { HostApplyPhase.MUTATION }
                    val started = reply.readInt() != 0
                    val rolledBack = reply.readInt() != 0
                    val indeterminate = reply.readInt() != 0
                    throw RemoteHostApplyFailure(phase, started, rolledBack, indeterminate, message)
                }
                throw IllegalStateException("host request failed: $type $message")
            }
            return reader(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private data class PingInfo(val method: String)

    private fun ping(target: IBinder, expectedMethod: String?): PingInfo {
        return transact(target, HostProtocol.PING, reader = { parcel ->
            val remoteGeneration = parcel.readLong()
            val remoteMethod = parcel.readString().orEmpty()
            val hostUid = parcel.readInt()
            if (remoteGeneration != generation) {
                throw HostIdentityMismatch("host generation mismatch")
            }
            if (hostUid != 0 && hostUid != 1000) {
                throw HostIdentityMismatch("unprivileged host")
            }
            if (expectedMethod != null && remoteMethod != expectedMethod) {
                throw HostIdentityMismatch("execution method mismatch")
            }
            PingInfo(remoteMethod)
            })
    }
    private fun attach(found: IBinder, method: String, requireLegacyService: Boolean = false) {
        synchronized(lock) {
            if (binder === found && attachedMethod == method) return
            detachLocked()
            binder = found
            attachedMethod = method
            val recipient = IBinder.DeathRecipient {
                synchronized(lock) {
                    if (binder === found) {
                        binder = null
                        attachedMethod = null
                        death = null
                    }
                }
            }
            death = recipient
            runCatching { found.linkToDeath(recipient, 0) }.onFailure {
                binder = null
                attachedMethod = null
                death = null
            }
            // A binder can die or be replaced between lookup and linkToDeath. Do not cache a
            // handle unless both the binder and the ServiceManager registration still match.
            if (binder === found && (!found.isBinderAlive || (requireLegacyService && service() !== found))) {
                runCatching { found.unlinkToDeath(recipient, 0) }
                binder = null
                attachedMethod = null
                death = null
            }
        }
    }

    private fun detach(target: IBinder? = null) {
        synchronized(lock) {
            if (target == null || binder === target) detachLocked()
        }
    }
    private fun sendLease(target: IBinder) {
        val lease = HostRendezvous.lease(serviceName) ?: error("host lease unavailable")
        transact(target, HostProtocol.LEASE, writer = { it.writeStrongBinder(lease) }) { Unit }
    }

    private fun detachLocked() {
        val current = binder
        val recipient = death
        binder = null
        attachedMethod = null
        death = null
        if (current != null && recipient != null) {
            runCatching { current.unlinkToDeath(recipient, 0) }
        }
    }
    private fun waitForServiceReplacement(previous: IBinder, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + min(timeoutMs, 1000L)
        while (System.currentTimeMillis() < deadline) {
            if (service() == null || service() !== previous) return true
            Thread.sleep(20)
        }
        return service() == null || service() !== previous
    }
    private fun service(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, serviceName) as? IBinder
    }.getOrNull()

    private fun readCount(parcel: Parcel, maximum: Int, label: String): Int {
        val count = parcel.readInt()
        require(count in 0..maximum) { "invalid $label count: $count" }
        return count
    }

    private fun readLongList(parcel: Parcel, label: String): List<Long> {
        val count = readCount(parcel, 256, label)
        require(parcel.dataAvail() >= count * Long.SIZE_BYTES) { "truncated $label payload" }
        return List(count) { parcel.readLong() }
    }

    private fun readRequiredString(parcel: Parcel, label: String): String =
        parcel.readString()?.also { require(it.length <= 512) { "$label is too long" } }
            ?: error("missing $label")
    companion object { private val START_LOCKS=ConcurrentHashMap<Int,Any>() }
}

/** Host protocol uses -1 as the wire sentinel for an unavailable optional node. */
internal fun decodeOptionalHostValue(value: Long): Long? = value.takeUnless { it == -1L }

internal class HostProtocolMismatch(val remoteVersion: Int) : IllegalStateException(
    "host protocol mismatch (remote=$remoteVersion, local=${HostProtocol.VERSION})",
)

internal class HostIdentityMismatch(message: String) : IllegalStateException(message)

class RemoteHostApplyFailure(
    val phase: HostApplyPhase,
    val mutationStarted: Boolean,
    val rollbackComplete: Boolean,
    val indeterminate: Boolean,
    message: String,
) : IllegalStateException(message)
