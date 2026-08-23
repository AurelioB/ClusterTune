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
    private val maximumOutputBytes: Int = 1024 * 1024,
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
    val averageFpsMilli: Int?,
    val jankyFrames: Long?,
)

object HostTelemetryParsers {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")

    fun isValidPackageName(value: String): Boolean =
        value.length in 1..HostProtocol.MAX_PACKAGE_LENGTH && packagePattern.matches(value)

    fun surfaceLayers(text: String, packageName: String, maximum: Int = 4): List<String> {
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
            .take(maximum.coerceAtMost(8))
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
                    totalFrames = fields["totalframes"]?.let(::firstLong),
                    averageFpsMilli = average?.takeIf { it in 0..1_000_000 },
                    jankyFrames = fields["jankyframes"]?.let(::firstLong),
                )
            }
            fields = linkedMapOf()
        }
        text.lineSequence().forEach { raw ->
            if (raw.isBlank()) {
                if (fields.containsKey("layername")) flush()
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
        if (!isValidPackageName(packageName)) return null
        return surfaceTimeStats(text)
            .filter { it.packageName == packageName || containsPackageToken(it.layerName, packageName) }
            .sortedWith(
                compareByDescending<HostTimeStatsLayer> { "SurfaceView" in it.layerName || "BLAST" in it.layerName }
                    .thenByDescending { it.totalFrames ?: -1L }
                    .thenBy { it.layerName },
            )
            .firstOrNull()
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

    fun gpuBusyPermille(previous: HostGpuBusyValue?, current: HostGpuBusyValue?): Int? {
        current?.instantaneousPermille?.let { return it.coerceIn(0, 1000) }
        val busy = current?.busyTicks ?: return null
        val total = current.totalTicks ?: return null
        val previousBusy = previous?.busyTicks
        val previousTotal = previous?.totalTicks
        val deltaBusy = if (previousBusy != null && busy >= previousBusy) busy - previousBusy else busy
        val deltaTotal = if (previousTotal != null && total >= previousTotal) total - previousTotal else total
        if (deltaTotal <= 0L) return null
        return ((deltaBusy.coerceIn(0L, deltaTotal) * 1000L) / deltaTotal).toInt()
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

    private fun firstLong(value: String): Long? = Regex("-?[0-9]+").find(value)?.value?.toLongOrNull()
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
) : HostTelemetrySource {
    private val dumpsys = "/system/bin/dumpsys"
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
    private var lastPresentedNanos = 0L
    private var previousTimeStatsFrames: Long? = null
    private var previousTimeStatsJanky: Long? = null
    private var timeStatsEnabledByHost = false
    private var staleFrameSamples = 0
    private var previousCpu = emptyMap<Int?, HostCpuTicks>()
    private var previousGpuBusy: HostGpuBusyValue? = null

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
        require(targetFps in 15..240) { "invalid target FPS" }
        check(capabilities().frameStats) { "SurfaceFlinger frame statistics are unavailable" }
        previousTimeStatsFrames = null
        previousTimeStatsJanky = null
        val layers = listLayers(packageName)
        this.packageName = packageName
        this.targetFps = targetFps
        frameLayer = chooseLatencyLayer(layers)
        frameBackend = if (frameLayer != null) "surfaceflinger-latency" else null
        if (frameBackend == null) {
            val dump = dumpTimeStats()
            val existing = dump?.let { HostTelemetryParsers.matchingTimeStatsLayer(it, packageName) }
            if (existing != null) {
                frameLayer = existing.layerName
                frameBackend = "surfaceflinger-timestats"
                previousTimeStatsFrames = existing.totalFrames
                previousTimeStatsJanky = existing.jankyFrames
            } else if (layers.isNotEmpty() && enableTimeStats()) {
                frameLayer = layers.first()
                frameBackend = "surfaceflinger-timestats"
            }
        }
        check(frameBackend != null) { "no SurfaceFlinger frame statistics for $packageName" }
        lastPresentedNanos = 0L
        staleFrameSamples = 0
        previousCpu = HostTelemetryParsers.procStat(fs.read("/proc/stat").orEmpty())
        previousGpuBusy = readGpuBusy()
    }

    override fun end() {
        if (timeStatsEnabledByHost) {
            runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--timestats", "-disable")) }
        }
        timeStatsEnabledByHost = false
        packageName = null
        targetFps = 0
        frameLayer = null
        frameBackend = null
    }

    override fun sample(): HostRawTelemetry {
        val now = clock.nanoTime()
        val frame = sampleFrames()
        val cpuNow = HostTelemetryParsers.procStat(fs.read("/proc/stat").orEmpty())
        val aggregateLoad = HostTelemetryParsers.cpuLoadPermille(previousCpu[null], cpuNow[null])
        val cpuLoads = hostCapabilities.cpus.mapIndexed { index, _ ->
            val ids = cpuIdsByDomain[index]
            if (ids.isEmpty()) aggregateLoad else {
                val previous = combineTicks(ids.mapNotNull(previousCpu::get))
                val current = combineTicks(ids.mapNotNull(cpuNow::get))
                HostTelemetryParsers.cpuLoadPermille(previous, current) ?: aggregateLoad
            }
        }
        previousCpu = cpuNow
        val currentGpuBusy = readGpuBusy()
        val gpuLoad = HostTelemetryParsers.gpuBusyPermille(previousGpuBusy, currentGpuBusy)
        previousGpuBusy = currentGpuBusy
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
            frameLayer = chooseLatencyLayer(listLayers(targetPackage))
            staleFrameSamples = 0
        }
        val layer = frameLayer ?: return FrameReading(frameBackend, 0, null, 0, null, null, null, true, "surface_layer_unavailable")
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--latency", layer)) }.getOrNull()
            ?: return FrameReading(frameBackend, 0, layer, 0, null, null, null, true, "surfaceflinger_command_failed")
        if (result.timedOut || result.exitCode != 0 || result.truncated) {
            staleFrameSamples++
            return FrameReading(frameBackend, 0, layer, 0, null, null, null, true, if (result.timedOut) "surfaceflinger_timeout" else "surfaceflinger_command_failed")
        }
        val window = HostTelemetryParsers.surfaceLatency(result.stdout)
        val recent = HostTelemetryParsers.rollingPresentedNanos(window.presentedNanos)
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
        val targetPeriod = targetFps.takeIf { it > 0 }?.let { 1_000_000_000L / it }
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
        val layer = HostTelemetryParsers.matchingTimeStatsLayer(dump, targetPackage)
            ?: return FrameReading("surfaceflinger-timestats", 250, frameLayer, 0, null, null, null, true, "surface_layer_unavailable")
        frameLayer = layer.layerName
        val total = layer.totalFrames
        val previousTotal = previousTimeStatsFrames
        val frames = if (total != null && previousTotal != null && total >= previousTotal) (total - previousTotal).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        val janky = layer.jankyFrames
        val previousJanky = previousTimeStatsJanky
        val deltaJanky = if (janky != null && previousJanky != null && janky >= previousJanky) janky - previousJanky else null
        val slow = if (frames > 0 && deltaJanky != null) ((deltaJanky.coerceAtMost(frames.toLong()) * 1000L) / frames).toInt() else null
        previousTimeStatsFrames = total
        previousTimeStatsJanky = janky
        return FrameReading(
            backend = "surfaceflinger-timestats",
            confidencePermille = 500,
            layer = layer.layerName,
            newFrames = frames,
            fpsMilli = layer.averageFpsMilli,
            p95Nanos = null,
            slowRatioPermille = slow,
            stale = frames == 0,
            reason = if (layer.averageFpsMilli == null) "timestats_fps_unavailable" else null,
        )
    }

    private fun listLayers(packageName: String): List<String> {
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--list")) }.getOrNull() ?: return emptyList()
        if (result.exitCode != 0 || result.timedOut || result.truncated) return emptyList()
        return HostTelemetryParsers.surfaceLayers(result.stdout, packageName)
    }

    private fun chooseLatencyLayer(layers: List<String>): String? {
        if (layers.isEmpty()) return null
        // Query at most four bounded candidates and choose the layer with the freshest frame data.
        return layers.map { layer ->
            val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--latency", layer)) }.getOrNull()
            val latest = result?.takeIf { it.exitCode == 0 && !it.timedOut && !it.truncated }
                ?.let { HostTelemetryParsers.surfaceLatency(it.stdout).presentedNanos.lastOrNull() } ?: Long.MIN_VALUE
            layer to latest
        }.filter { it.second != Long.MIN_VALUE }.maxWithOrNull(compareBy<Pair<String, Long>> { it.second }.thenByDescending { it.first })?.first
    }

    private fun dumpTimeStats(): String? {
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--timestats", "-dump")) }.getOrNull() ?: return null
        return result.stdout.takeIf { result.exitCode == 0 && !result.timedOut && !result.truncated }
    }

    private fun enableTimeStats(): Boolean {
        val result = runCatching { runner.run(listOf(dumpsys, "SurfaceFlinger", "--timestats", "-enable")) }.getOrNull() ?: return false
        timeStatsEnabledByHost = result.exitCode == 0 && !result.timedOut
        return timeStatsEnabledByHost
    }

    private fun combineTicks(values: List<HostCpuTicks>): HostCpuTicks? {
        if (values.isEmpty()) return null
        return HostCpuTicks(values.sumOf { it.total }, values.sumOf { it.idle })
    }

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
