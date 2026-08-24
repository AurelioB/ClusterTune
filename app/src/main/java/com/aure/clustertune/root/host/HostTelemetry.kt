package com.aure.clustertune.root.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

fun interface HostMonotonicClock {
    fun nanoTime(): Long
}

object SystemHostMonotonicClock : HostMonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}

data class HostCommandResult(
    val exitCode: Int,
    val stdout: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

fun interface HostCommandRunner {
    fun run(arguments: List<String>): HostCommandResult
}

/** Executes a fixed argv vector directly. It never invokes a shell or interpolates layer names. */
class ProcessHostCommandRunner @JvmOverloads constructor(
    private val timeoutMs: Long = 750L,
    private val maximumOutputBytes: Int = 4 * 1024 * 1024,
) : HostCommandRunner {
    override fun run(arguments: List<String>): HostCommandResult {
        require(arguments.isNotEmpty() && arguments.size <= 16) { "invalid command arguments" }
        require(arguments.all { it.length <= 1024 && '\u0000' !in it }) { "invalid command argument" }
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        var truncated = false
        val drain = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = maximumOutputBytes - output.size()
                    if (remaining > 0) output.write(buffer, 0, count.coerceAtMost(remaining))
                    if (count > remaining) truncated = true
                }
            }
        }.apply {
            isDaemon = true
            name = "ClusterTune-command-drain"
            start()
        }
        return try {
            if (!process.waitFor(timeoutMs.coerceAtLeast(100L), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(250L, TimeUnit.MILLISECONDS)
                drain.join(250L)
                HostCommandResult(-1, output.toString(Charsets.UTF_8.name()), timedOut = true, truncated = truncated)
            } else {
                drain.join(250L)
                HostCommandResult(process.exitValue(), output.toString(Charsets.UTF_8.name()), truncated = truncated)
            }
        } finally {
            process.destroy()
        }
    }
}

data class HostCpuTicks(val total: Long, val idle: Long)

data class HostFrameWindow(
    val refreshPeriodNanos: Long?,
    val presentedNanos: List<Long>,
)

data class HostGpuBusyValue(
    val instantaneousPermille: Int? = null,
    val busyTicks: Long? = null,
    val totalTicks: Long? = null,
)

data class HostTimeStatsLayer(
    val layerName: String,
    val packageName: String?,
    val totalFrames: Long?,
    val totalTimelineFrames: Long?,
    val averageFpsMilli: Int?,
    val jankyFrames: Long?,
)

object HostTelemetryParsers {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private const val MAX_SURFACE_LAYER_CANDIDATES = 8

    fun isValidPackageName(value: String): Boolean =
        value.length in 1..HostProtocol.MAX_PACKAGE_LENGTH && packagePattern.matches(value)

    fun surfaceLayers(
        text: String,
        packageName: String,
        maximum: Int = MAX_SURFACE_LAYER_CANDIDATES,
    ): List<String> {
        if (!isValidPackageName(packageName) || maximum <= 0) return emptyList()
        return text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= HostProtocol.MAX_METADATA_LENGTH && it.none(Char::isISOControl) }
            .filter { containsPackageToken(it, packageName) }
            .distinct()
            .sortedWith(
                compareByDescending<String> { "SurfaceView" in it || "BLAST" in it }
                    .thenByDescending { packageName + "/" in it || packageName + ":" in it }
                    .thenBy { it.length }
                    .thenBy { it },
            )
            .take(maximum.coerceAtMost(MAX_SURFACE_LAYER_CANDIDATES))
            .toList()
    }

    private fun containsPackageToken(value: String, packageName: String): Boolean {
        var start = value.indexOf(packageName)
        while (start >= 0) {
            val before = value.getOrNull(start - 1)
            val after = value.getOrNull(start + packageName.length)
            val beforeOk = before == null || !(before.isLetterOrDigit() || before == '_' || before == '.')
            val afterOk = after == null || !(after.isLetterOrDigit() || after == '_' || after == '.')
            if (beforeOk && afterOk) return true
            start = value.indexOf(packageName, start + 1)
        }
        return false
    }

    fun surfaceLatency(text: String): HostFrameWindow {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).iterator()
        if (!lines.hasNext()) return HostFrameWindow(null, emptyList())
        val first = lines.next().split(Regex("\\s+"))
        val refresh = first.singleOrNull()?.toLongOrNull()?.takeIf { it in 1L..1_000_000_000L }
        val presented = mutableListOf<Long>()
        while (lines.hasNext() && presented.size < 512) {
            val values = lines.next().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
            val actualPresent = values.getOrNull(1)?.takeIf { it > 0L && it != Long.MAX_VALUE }
            if (actualPresent != null) presented += actualPresent
        }
        return HostFrameWindow(refresh, presented.distinct().sorted())
    }

    fun rollingPresentedNanos(
        presentedNanos: List<Long>,
        windowNanos: Long = 2_000_000_000L,
        maximumFrames: Int = 240,
    ): List<Long> {
        if (presentedNanos.isEmpty() || windowNanos <= 0L || maximumFrames <= 0) return emptyList()
        val ordered = presentedNanos.asSequence().filter { it > 0L }.distinct().sorted().toList()
        val latest = ordered.lastOrNull() ?: return emptyList()
        val cutoff = if (latest < Long.MIN_VALUE + windowNanos) Long.MIN_VALUE else latest - windowNanos
        return ordered.filter { it >= cutoff }.takeLast(maximumFrames.coerceAtMost(512))
    }

    /** Parses SurfaceFlinger TimeStats as unordered key/value records and ignores new fields. */
    fun surfaceTimeStats(text: String): List<HostTimeStatsLayer> {
        val result = mutableListOf<HostTimeStatsLayer>()
        var fields = linkedMapOf<String, String>()
        fun flush() {
            val layer = fields["layername"]?.trim()?.takeIf(String::isNotEmpty)
            if (layer != null && layer.length <= HostProtocol.MAX_METADATA_LENGTH && layer.none(Char::isISOControl)) {
                val average = fields["averagefps"]?.let(::firstDecimal)?.let { (it * 1000.0).roundToInt() }
                result += HostTimeStatsLayer(
                    layerName = layer,
                    packageName = fields["packagename"]?.trim()?.takeIf(String::isNotEmpty),
                    totalFrames = fields["totalframes"]?.let(::nonNegativeFirstLong),
                    totalTimelineFrames = fields["totaltimelineframes"]?.let(::nonNegativeFirstLong),
                    averageFpsMilli = average?.takeIf { it in 0..1_000_000 },
                    jankyFrames = fields["jankyframes"]?.let(::nonNegativeFirstLong)
                        ?: fields["totaljankyframes"]?.let(::nonNegativeFirstLong),
                )
            }
            fields = linkedMapOf()
        }
        text.lineSequence().forEach { raw ->
            if (raw.isBlank()) {
                if (fields.isNotEmpty()) flush()
                return@forEach
            }
            val delimiter = raw.indexOf('=').takeIf { it > 0 } ?: raw.indexOf(':').takeIf { it > 0 } ?: return@forEach
            val key = raw.substring(0, delimiter).trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            val value = raw.substring(delimiter + 1).trim()
            if (key == "layername" && fields.containsKey("layername")) flush()
            if (key.isNotEmpty() && key.length <= 64 && value.length <= 2048) fields[key] = value
        }
        flush()
        return result.take(512)
    }

    fun matchingTimeStatsLayer(text: String, packageName: String): HostTimeStatsLayer? {
        return matchingTimeStatsLayers(text, packageName).firstOrNull()
    }

    /** Returns one aggregate per logical layer, combining refresh/render-rate TimeStats slices. */
    fun matchingTimeStatsLayers(text: String, packageName: String): List<HostTimeStatsLayer> {
        if (!isValidPackageName(packageName)) return emptyList()
        return surfaceTimeStats(text)
            .filter { it.packageName == packageName || containsPackageToken(it.layerName, packageName) }
            .groupBy(HostTimeStatsLayer::layerName)
            .values
            .map(::aggregateTimeStatsSlices)
            .sortedWith(
                compareByDescending<HostTimeStatsLayer> { "SurfaceView" in it.layerName || "BLAST" in it.layerName }
                    .thenByDescending { it.totalFrames ?: it.totalTimelineFrames ?: -1L }
                    .thenBy { it.layerName },
            )
    }

    fun procStat(text: String): Map<Int?, HostCpuTicks> {
        val result = linkedMapOf<Int?, HostCpuTicks>()
        text.lineSequence().forEach { raw ->
            val values = raw.trim().split(Regex("\\s+"))
            val name = values.firstOrNull() ?: return@forEach
            if (name != "cpu" && !name.matches(Regex("cpu[0-9]+"))) return@forEach
            val ticks = values.drop(1).mapNotNull(String::toLongOrNull)
            if (ticks.size < 4) return@forEach
            val total = ticks.take(8).fold(0L) { sum, tick -> saturatingAdd(sum, tick) }
            val idle = saturatingAdd(ticks.getOrElse(3) { 0L }, ticks.getOrElse(4) { 0L })
            result[name.removePrefix("cpu").toIntOrNull()] = HostCpuTicks(total, idle)
        }
        return result
    }

    fun cpuLoadPermille(previous: HostCpuTicks?, current: HostCpuTicks?): Int? {
        if (previous == null || current == null) return null
        val total = current.total - previous.total
        val idle = current.idle - previous.idle
        if (total <= 0L || idle < 0L) return null
        return (((total - idle).coerceIn(0L, total) * 1000L) / total).toInt()
    }

    fun cpuPolicyLoadPermille(
        previous: Map<Int?, HostCpuTicks>,
        current: Map<Int?, HostCpuTicks>,
        cpuIds: List<Int>,
    ): Int? {
        val commonIds = cpuIds.distinct().filter { previous[it] != null && current[it] != null }
        if (commonIds.isEmpty()) return null
        val previousCombined = combineTicks(commonIds.mapNotNull(previous::get)) ?: return null
        val currentCombined = combineTicks(commonIds.mapNotNull(current::get)) ?: return null
        return cpuLoadPermille(previousCombined, currentCombined)
    }

    fun cpuList(text: String): List<Int> {
        val values = sortedSetOf<Int>()
        text.trim().split(Regex("[\\s,]+")).filter(String::isNotEmpty).forEach { token ->
            val range = token.split('-', limit = 2)
            val first = range.firstOrNull()?.toIntOrNull() ?: return@forEach
            val last = range.getOrNull(1)?.toIntOrNull() ?: first
            if (first in 0..255 && last in first..255 && last - first <= 255) {
                for (cpu in first..last) values += cpu
            }
        }
        return values.toList()
    }

    fun gpuBusy(text: String, percentStyle: Boolean = false): HostGpuBusyValue? {
        val numbers = Regex("[0-9]+").findAll(text).mapNotNull { it.value.toLongOrNull() }.take(2).toList()
        if (numbers.isEmpty()) return null
        if (percentStyle || numbers.size == 1) {
            val raw = numbers.first()
            val permille = when {
                raw <= 100L -> (raw * 10L).toInt()
                raw <= 1000L -> raw.toInt()
                else -> return null
            }
            return HostGpuBusyValue(instantaneousPermille = permille.coerceIn(0, 1000))
        }
        val busy = numbers[0]
        val total = numbers[1]
        if (total <= 0L || busy < 0L || busy > total) return null
        return HostGpuBusyValue(busyTicks = busy, totalTicks = total)
    }

    fun gpuBusyPermille(current: HostGpuBusyValue?): Int? {
        current?.instantaneousPermille?.let { return it.coerceIn(0, 1000) }
        val busy = current?.busyTicks ?: return null
        val total = current.totalTicks ?: return null
        if (total <= 0L) return null
        return ((busy.coerceIn(0L, total) * 1000L) / total).toInt()
    }

    fun thermalMilliCelsius(text: String): Long? {
        val value = text.trim().toLongOrNull() ?: return null
        if (value !in -200_000L..300_000L) return null
        val magnitude = kotlin.math.abs(value)
        return when {
            magnitude <= 300L -> value * 1000L
            magnitude <= 2_000L -> value * 100L
            else -> value
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun combineTicks(values: List<HostCpuTicks>): HostCpuTicks? {
        if (values.isEmpty()) return null
        return HostCpuTicks(
            total = values.fold(0L) { total, value -> saturatingAdd(total, value.total) },
            idle = values.fold(0L) { idle, value -> saturatingAdd(idle, value.idle) },
        )
    }

    private fun aggregateTimeStatsSlices(slices: List<HostTimeStatsLayer>): HostTimeStatsLayer {
        require(slices.isNotEmpty())
        val weightedFps = slices.mapNotNull { slice ->
            val fps = slice.averageFpsMilli ?: return@mapNotNull null
            val weight = (slice.totalFrames ?: slice.totalTimelineFrames)?.takeIf { it > 0L } ?: 1L
            fps to weight
        }
        val totalWeight = weightedFps.sumOf { it.second.toDouble() }
        val averageFps = if (totalWeight > 0.0) {
            (weightedFps.sumOf { (fps, weight) -> fps.toDouble() * weight.toDouble() } / totalWeight)
                .roundToInt()
                .coerceIn(0, 1_000_000)
        } else {
            null
        }
        return HostTimeStatsLayer(
            layerName = slices.first().layerName,
            packageName = slices.mapNotNull(HostTimeStatsLayer::packageName).distinct().singleOrNull(),
            totalFrames = sumNullable(slices.map(HostTimeStatsLayer::totalFrames)),
            totalTimelineFrames = sumNullable(slices.map(HostTimeStatsLayer::totalTimelineFrames)),
            averageFpsMilli = averageFps,
            jankyFrames = sumNullable(slices.map(HostTimeStatsLayer::jankyFrames)),
        )
    }

    private fun sumNullable(values: List<Long?>): Long? {
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        return present.fold(0L, ::saturatingAdd)
    }

    private fun firstLong(value: String): Long? = Regex("-?[0-9]+").find(value)?.value?.toLongOrNull()
    private fun nonNegativeFirstLong(value: String): Long? = firstLong(value)?.takeIf { it >= 0L }
    private fun firstDecimal(value: String): Double? = Regex("[0-9]+(?:\\.[0-9]+)?").find(value)?.value?.toDoubleOrNull()
}

data class HostRawTelemetry(
    val timestampNanos: Long,
    val frameBackend: String?,
    val frameConfidencePermille: Int,
    val frameLayer: String?,
    val frameCount: Int,
    val fpsMilli: Int?,
    val frameTimeP95Nanos: Long?,
    val slowFrameRatioPermille: Int?,
    val frameStale: Boolean,
    val cpuLoadPermille: List<Int?>,
    val cpuClockKHz: List<Long?>,
    val gpuBusyPermille: Int?,
    val gpuClockHz: Long?,
    val thermal: List<HostThermalReading>,
    val unsupportedMetrics: List<String>,
)

interface HostTelemetrySource {
    fun capabilities(): HostAutoCapabilities
    fun begin(packageName: String, targetFps: Int): Result<Unit>
    fun sample(): HostRawTelemetry
    fun end() = Unit
}

/** Privileged, package-agnostic telemetry backed by SurfaceFlinger and allowlisted kernel nodes. */
class SystemHostTelemetrySource(
    private val fs: HostFilesystem,
    private val hostCapabilities: HostCapabilities,
    private val runner: HostCommandRunner = ProcessHostCommandRunner(),
    private val clock: HostMonotonicClock = SystemHostMonotonicClock,
    thermalZones: List<Pair<String, String>>? = null,
    private val dumpsys: String = "/system/bin/dumpsys",
) : HostTelemetrySource {
    private val cpuIdsByDomain = hostCapabilities.cpus.map { cpu ->
        val parent = File(cpu.maxPath).parentFile
        parent?.let { HostTelemetryParsers.cpuList(fs.read(File(it, "related_cpus").path).orEmpty()) }.orEmpty()
    }
    private val gpuBusyNode = resolveGpuBusyNode(hostCapabilities.gpu)
    private val thermalNodes = (thermalZones ?: discoverThermalZones()).take(HostProtocol.MAX_THERMAL_READINGS)
    private var packageName: String? = null
    private var targetFps: Int = 0
    private var frameLayer: String? = null
    private var frameBackend: String? = null
    private var frameSessionStartedNanos = 0L
    private var lastPresentedNanos = 0L
    private var previousTimeStats = emptyMap<String, TimeStatsCounters>()
    private var previousTimeStatsSampleNanos: Long? = null
    private var staleFrameSamples = 0
    private var previousCpu = emptyMap<Int?, HostCpuTicks>()

    override fun capabilities(): HostAutoCapabilities {
        val frameSupported = File(dumpsys).canExecute()
        return HostAutoCapabilities(
            frameStats = frameSupported,
            cpuLoad = fs.exists("/proc/stat"),
            cpuClocks = hostCapabilities.cpus.any { it.curPath?.let(fs::exists) == true },
            gpuBusy = gpuBusyNode != null,
            gpuClock = hostCapabilities.gpu?.curPath?.let(fs::exists) == true,
            thermal = thermalNodes.isNotEmpty(),
            frameBackend = if (frameSupported) "surfaceflinger-latency,timestats" else null,
            unsupportedReason = if (frameSupported) null else "SurfaceFlinger frame statistics are unavailable",
        )
    }

    override fun begin(packageName: String, targetFps: Int): Result<Unit> = runCatching {
        require(HostTelemetryParsers.isValidPackageName(packageName)) { "invalid target package" }
        require(targetFps > 0) { "invalid target FPS" }
        check(capabilities().frameStats) { "SurfaceFlinger frame statistics are unavailable" }
        frameSessionStartedNanos = clock.nanoTime()
        previousTimeStats = emptyMap()
        previousTimeStatsSampleNanos = null
        val layers = listLayers(packageName)
        this.packageName = packageName
        this.targetFps = targetFps
        frameLayer = chooseLatencyLayer(layers)
        frameBackend = if (frameLayer != null) "surfaceflinger-latency" else null
        if (frameBackend == null) {
            switchToTimeStats(packageName, layers)
        }
        check(frameBackend != null) { "no SurfaceFlinger frame statistics for $packageName" }
        lastPresentedNanos = frameSessionStartedNanos
        staleFrameSamples = 0
        previousCpu = HostTelemetryParsers.procStat(fs.read("/proc/stat").orEmpty())
    }

    override fun end() {
        packageName = null
        targetFps = 0
        frameLayer = null
        frameBackend = null
        frameSessionStartedNanos = 0L
        lastPresentedNanos = 0L
        previousTimeStats = emptyMap()
        previousTimeStatsSampleNanos = null
    }

    override fun sample(): HostRawTelemetry {
        val now = clock.nanoTime()
        val frame = sampleFrames()
        val cpuNow = HostTelemetryParsers.procStat(fs.read("/proc/stat").orEmpty())
        val aggregateLoad = HostTelemetryParsers.cpuLoadPermille(previousCpu[null], cpuNow[null])
        val cpuLoads = hostCapabilities.cpus.mapIndexed { index, _ ->
            val ids = cpuIdsByDomain[index]
            if (ids.isEmpty()) aggregateLoad
            else HostTelemetryParsers.cpuPolicyLoadPermille(previousCpu, cpuNow, ids) ?: aggregateLoad
        }
        previousCpu = cpuNow
        val gpuLoad = HostTelemetryParsers.gpuBusyPermille(readGpuBusy())
        val cpuClocks = hostCapabilities.cpus.map { cpu -> cpu.curPath?.let { fs.read(it)?.toLongOrNull()?.takeIf { value -> value >= 0L } } }
        val gpuClock = hostCapabilities.gpu?.curPath?.let { fs.read(it)?.toLongOrNull()?.takeIf { value -> value >= 0L } }
        val thermals = thermalNodes.mapNotNull { (type, path) ->
            HostTelemetryParsers.thermalMilliCelsius(fs.read(path).orEmpty())?.let { HostThermalReading(type, it) }
        }
        val capabilities = capabilities()
        val unsupported = buildList {
            if (!capabilities.cpuLoad) add("cpu_load")
            if (!capabilities.cpuClocks) add("cpu_clocks")
            if (!capabilities.gpuBusy) add("gpu_busy")
            if (!capabilities.gpuClock) add("gpu_clock")
            if (!capabilities.thermal) add("thermal")
            frame.reason?.let { add(it.take(HostProtocol.MAX_METADATA_LENGTH)) }
        }.distinct().take(HostProtocol.MAX_UNSUPPORTED_METRICS)
        return HostRawTelemetry(
            timestampNanos = now,
            frameBackend = frame.backend,
            frameConfidencePermille = frame.confidencePermille,
            frameLayer = frame.layer,
            frameCount = frame.newFrames,
            fpsMilli = frame.fpsMilli,
            frameTimeP95Nanos = frame.p95Nanos,
            slowFrameRatioPermille = frame.slowRatioPermille,
            frameStale = frame.stale,
            cpuLoadPermille = cpuLoads,
            cpuClockKHz = cpuClocks,
            gpuBusyPermille = gpuLoad,
            gpuClockHz = gpuClock,
            thermal = thermals,
            unsupportedMetrics = unsupported,
        )
    }

    private data class FrameReading(
        val backend: String?,
        val confidencePermille: Int,
        val layer: String?,
        val newFrames: Int,
        val fpsMilli: Int?,
        val p95Nanos: Long?,
        val slowRatioPermille: Int?,
        val stale: Boolean,
        val reason: String? = null,
    )

    private fun sampleFrames(): FrameReading {
        val targetPackage = packageName ?: return FrameReading(null, 0, null, 0, null, null, null, true, "frame_session_not_started")
        if (frameBackend == "surfaceflinger-timestats") return sampleTimeStats(targetPackage)
        if (frameLayer == null || staleFrameSamples >= 3) {
            val layers = listLayers(targetPackage)
            frameLayer = chooseLatencyLayer(layers, newerThanNanos = lastPresentedNanos)
            if (frameLayer != null) {
                staleFrameSamples = 0
            } else if (switchToTimeStats(targetPackage, layers)) {
                // The activation dump is the new counter baseline. Avoid a second large dump in the
                // same binder call; the next sample will report deltas from this transition point.
                return FrameReading("surfaceflinger-timestats", 500, frameLayer, 0, null, null, null, true)
            }
        }
        val layer = frameLayer ?: return FrameReading(frameBackend, 0, null, 0, null, null, null, true, "surface_layer_unavailable")
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--latency", layer)) }.getOrNull()
            ?: return FrameReading(frameBackend, 0, layer, 0, null, null, null, true, "surfaceflinger_command_failed")
        if (result.timedOut || result.exitCode != 0 || result.truncated) {
            staleFrameSamples++
            return FrameReading(frameBackend, 0, layer, 0, null, null, null, true, if (result.timedOut) "surfaceflinger_timeout" else "surfaceflinger_command_failed")
        }
        val window = HostTelemetryParsers.surfaceLatency(result.stdout)
        val sessionFrames = window.presentedNanos.filter { it >= frameSessionStartedNanos }
        val recent = HostTelemetryParsers.rollingPresentedNanos(sessionFrames)
        val newFrames = recent.count { it > lastPresentedNanos }
        val latest = recent.lastOrNull()
        if (latest != null && latest > lastPresentedNanos) lastPresentedNanos = latest
        val fpsMilli = if (recent.size >= 2) {
            val span = recent.last() - recent.first()
            if (span > 0L) (((recent.size - 1).toDouble() * 1_000_000_000_000.0) / span.toDouble()).roundToInt().coerceIn(0, 1_000_000) else null
        } else null
        val intervals = recent.zipWithNext { before, after -> after - before }.filter { it > 0L }
        val p95 = intervals.sorted().let { sorted ->
            if (sorted.isEmpty()) null else sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        }
        val targetPeriod = targetFps.takeIf { it > 0 }
            ?.let { (1_000_000_000L / it).coerceAtLeast(1L) }
        val slowRatio = targetPeriod?.let { budget ->
            if (intervals.isEmpty()) null else (intervals.count { it > budget + budget / 2L } * 1000 / intervals.size)
        }
        val stale = newFrames == 0
        staleFrameSamples = if (stale) staleFrameSamples + 1 else 0
        return FrameReading("surfaceflinger-latency", 1000, layer, newFrames, fpsMilli, p95, slowRatio, stale)
    }

    private fun sampleTimeStats(targetPackage: String): FrameReading {
        val dump = dumpTimeStats()
            ?: return FrameReading("surfaceflinger-timestats", 250, frameLayer, 0, null, null, null, true, "surfaceflinger_timestats_failed")
        val sampleTimestampNanos = clock.nanoTime()
        val layers = HostTelemetryParsers.matchingTimeStatsLayers(dump, targetPackage)
        val layer = layers.maxWithOrNull(
            compareBy<HostTimeStatsLayer> { candidate ->
                counterDelta(candidate.frameCounter(), previousTimeStats[candidate.layerName]?.frameCounter) ?: 0L
            }.thenBy { candidate -> candidate.layerName == frameLayer }
                .thenBy { candidate -> "SurfaceView" in candidate.layerName || "BLAST" in candidate.layerName }
                .thenBy { candidate -> candidate.frameCounter() ?: -1L },
        )
            ?: return FrameReading("surfaceflinger-timestats", 250, frameLayer, 0, null, null, null, true, "surface_layer_unavailable")
        frameLayer = layer.layerName
        val previous = previousTimeStats[layer.layerName]
        val frameDelta = counterDelta(layer.frameCounter(), previous?.frameCounter)
        val frames = frameDelta?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        val timelineDelta = counterDelta(layer.totalTimelineFrames, previous?.totalTimelineFrames)
        val jankyDelta = counterDelta(layer.jankyFrames, previous?.jankyFrames)
        val slow = if (timelineDelta != null && timelineDelta > 0L && jankyDelta != null) {
            ((jankyDelta.coerceAtMost(timelineDelta) * 1000L) / timelineDelta).toInt()
        } else {
            null
        }
        val elapsed = previousTimeStatsSampleNanos?.let { sampleTimestampNanos - it }
        val fpsMilli = if (frames > 0 && elapsed != null && elapsed > 0L) {
            (frames.toDouble() * 1_000_000_000_000.0 / elapsed.toDouble())
                .roundToInt()
                .coerceIn(0, 1_000_000)
        } else {
            null
        }
        previousTimeStats = layers.associate { it.layerName to it.counters() }
        previousTimeStatsSampleNanos = sampleTimestampNanos
        return FrameReading(
            backend = "surfaceflinger-timestats",
            confidencePermille = 500,
            layer = layer.layerName,
            newFrames = frames,
            fpsMilli = fpsMilli,
            p95Nanos = null,
            slowRatioPermille = slow,
            stale = frames == 0,
            reason = if (frames > 0 && fpsMilli == null) "timestats_fps_unavailable" else null,
        )
    }

    private fun listLayers(packageName: String): List<String> {
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--list")) }.getOrNull() ?: return emptyList()
        if (result.exitCode != 0 || result.timedOut || result.truncated) return emptyList()
        return HostTelemetryParsers.surfaceLayers(result.stdout, packageName)
    }

    private fun chooseLatencyLayer(layers: List<String>, newerThanNanos: Long? = null): String? {
        if (layers.isEmpty()) return null
        // The layer parser applies the command-safety bound. Probe every retained candidate before
        // choosing so lexical ordering cannot hide a later, actively presenting application layer.
        return layers.map { layer ->
            val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--latency", layer)) }.getOrNull()
            val latest = result?.takeIf { it.exitCode == 0 && !it.timedOut && !it.truncated }
                ?.let { HostTelemetryParsers.surfaceLatency(it.stdout).presentedNanos.lastOrNull() } ?: Long.MIN_VALUE
            layer to latest
        }.filter { (_, latest) ->
            latest != Long.MIN_VALUE && (newerThanNanos == null || latest > newerThanNanos)
        }.maxWithOrNull(compareBy<Pair<String, Long>> { it.second }.thenByDescending { it.first })?.first
    }

    private fun switchToTimeStats(targetPackage: String, fallbackLayers: List<String>): Boolean {
        val dump = dumpTimeStats()
        val dumpTimestampNanos = clock.nanoTime()
        val existing = dump?.let { HostTelemetryParsers.matchingTimeStatsLayers(it, targetPackage) }.orEmpty()
        // TimeStats enable is idempotent, but its ownership is global and cannot be queried.
        // Re-enable for every fallback and deliberately leave it enabled at end so an unrelated
        // consumer that already owned collection is never disabled by ClusterTune.
        if (!enableTimeStats()) return false
        frameLayer = existing.firstOrNull()?.layerName ?: fallbackLayers.firstOrNull()
        frameBackend = "surfaceflinger-timestats"
        previousTimeStats = existing.associate { it.layerName to it.counters() }
        previousTimeStatsSampleNanos = if (existing.isEmpty()) clock.nanoTime() else dumpTimestampNanos
        staleFrameSamples = 0
        return true
    }

    private fun dumpTimeStats(): String? {
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--timestats", "-dump")) }.getOrNull() ?: return null
        if (result.exitCode != 0 || result.timedOut) return null
        if (!result.truncated) return result.stdout
        // AOSP separates complete layer records with a blank line. When the bounded command output
        // is clipped, retain only the complete prefix; never parse the possibly partial tail.
        val boundary = result.stdout.lastIndexOf("\n\n")
        return result.stdout.takeIf { boundary >= 0 }?.substring(0, boundary + 1)
    }

    private fun enableTimeStats(): Boolean {
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--timestats", "-enable")) }.getOrNull() ?: return false
        return result.exitCode == 0 && !result.timedOut && !result.truncated
    }

    private data class TimeStatsCounters(
        val frameCounter: Long?,
        val totalTimelineFrames: Long?,
        val jankyFrames: Long?,
    )

    private fun HostTimeStatsLayer.frameCounter(): Long? = totalFrames ?: totalTimelineFrames

    private fun HostTimeStatsLayer.counters() = TimeStatsCounters(
        frameCounter = frameCounter(),
        totalTimelineFrames = totalTimelineFrames,
        jankyFrames = jankyFrames,
    )

    private fun counterDelta(current: Long?, previous: Long?): Long? =
        if (current != null && previous != null && current >= previous) current - previous else null

    private data class GpuBusyNode(val path: String, val percentStyle: Boolean)

    private fun resolveGpuBusyNode(gpu: GpuDomain?): GpuBusyNode? {
        gpu ?: return null
        val candidates = when {
            gpu.maxPath.startsWith("/sys/class/kgsl/kgsl-3d0/") -> listOf(
                GpuBusyNode("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", true),
                GpuBusyNode("/sys/class/kgsl/kgsl-3d0/gpubusy", false),
            )
            gpu.maxPath.startsWith("/sys/class/devfreq/") && '/' !in gpu.id -> listOf(
                GpuBusyNode("/sys/class/devfreq/${gpu.id}/load", true),
                GpuBusyNode("/sys/class/devfreq/${gpu.id}/device/load", true),
            )
            else -> emptyList()
        }
        return candidates.firstOrNull { fs.exists(it.path) }
    }

    private fun readGpuBusy(): HostGpuBusyValue? = gpuBusyNode?.let { node ->
        fs.read(node.path)?.let { HostTelemetryParsers.gpuBusy(it, node.percentStyle) }
    }

    private fun discoverThermalZones(): List<Pair<String, String>> {
        val root = File("/sys/class/thermal")
        val entries = root.listFiles { file -> file.name.matches(Regex("thermal_zone[0-9]+")) }.orEmpty()
            .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
        val preferred = Regex("cpu|gpu|soc|skin|battery|ap|cluster|little|big|silver|gold", RegexOption.IGNORE_CASE)
        val values = entries.mapNotNull { entry ->
            val typePath = File(entry, "type").path
            val tempPath = File(entry, "temp").path
            val type = fs.read(typePath)?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: return@mapNotNull null
            if (!fs.exists(tempPath)) return@mapNotNull null
            Triple(type, tempPath, preferred.containsMatchIn(type))
        }
        return (values.filter { it.third } + values.filterNot { it.third })
            .distinctBy { it.first to it.second }
            .take(HostProtocol.MAX_THERMAL_READINGS)
            .map { it.first to it.second }
    }
}
