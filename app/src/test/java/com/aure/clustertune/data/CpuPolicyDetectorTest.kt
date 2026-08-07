package com.aure.clustertune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuPolicyDetectorTest {

    @Test
    fun `detects and sorts policies from sysfs`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf(
                "/sys/devices/system/cpu/cpufreq/policy6",
                "/sys/devices/system/cpu/cpufreq/policy0",
            ),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "3532800",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq" to "307200",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "998400",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "998400 1785600 2227200 2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/affected_cpus" to "0 1 2 3 4 5",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq" to "3072000",
                "/sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq" to "4320000",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq" to "1075200",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies" to "1075200 1958400 2246400 3072000",
                "/sys/devices/system/cpu/cpufreq/policy6/affected_cpus" to "6 7",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies()

        assertEquals(listOf(0, 6), result.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result.first().cpuIds)
        assertEquals(listOf(6, 7), result.last().cpuIds)
        assertEquals(listOf(998400, 1785600, 2227200, 2745600), result.first().supportedFrequencies)
        assertEquals(307200, result.first().hardwareMinFreq)
        assertEquals(3072000, result.last().selectableMaxFreq)
        assertEquals(4320000, result.last().observedMaxFreq)
    }

    @Test
    fun `keeps hidden max values out of selectable frequencies`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy3"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq" to "2841600",
                "/sys/devices/system/cpu/cpufreq/policy3/cpuinfo_max_freq" to "2956800",
                "/sys/devices/system/cpu/cpufreq/policy3/scaling_min_freq" to "710400",
                "/sys/devices/system/cpu/cpufreq/policy3/scaling_available_frequencies" to "710400 940800 1209600 1420800 1785600 2150400",
                "/sys/devices/system/cpu/cpufreq/policy3/stats/time_in_state" to "710400 1\n2956800 5\n",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies().single()

        assertEquals(
            listOf(710400, 940800, 1209600, 1420800, 1785600, 2150400),
            result.supportedFrequencies,
        )
        assertEquals(2_150_400, result.selectableMaxFreq)
        assertEquals(2_956_800, result.observedMaxFreq)
        assertEquals(2_841_600, result.currentMaxFreq)
    }

    @Test
    fun `falls back when scaling_available_frequencies is missing`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy2"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy2/scaling_max_freq" to "2100000",
                "/sys/devices/system/cpu/cpufreq/policy2/cpuinfo_max_freq" to "2500000",
                "/sys/devices/system/cpu/cpufreq/policy2/cpuinfo_min_freq" to "800000",
                "/sys/devices/system/cpu/cpufreq/policy2/scaling_min_freq" to "800000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies().single()

        assertEquals(listOf(800000, 2100000, 2500000), result.supportedFrequencies)
    }

    @Test
    fun `keeps policy when scaling max is missing but other fields exist`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy0"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "614400",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "614400 902400 1209600 1593600 2016000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies().single()

        assertEquals(0, result.id)
        assertEquals(2_016_000, result.currentMaxFreq)
        assertEquals(2_016_000, result.selectableMaxFreq)
        assertEquals(listOf(614400, 902400, 1209600, 1593600, 2016000), result.supportedFrequencies)
    }

    @Test
    fun `uses privileged reader for protected scaling max and min values`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy0"),
            files = emptyMap(),
        )
        val privilegedReader = FakePrivilegedSysfsReader(
            mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "307200 614400 902400 1209600 1459200 2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "1459200",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
        )

        val result = detector.detectPolicies().single()

        assertEquals(2_016_000, result.currentMaxFreq)
        assertEquals(1_459_200, result.minFreq)
        assertEquals(listOf(307200, 614400, 902400, 1209600, 1459200, 2016000), result.supportedFrequencies)
    }

    @Test
    fun `keeps lower supported steps even when scaling min is raised`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy6"),
            files = emptyMap(),
        )
        val privilegedReader = FakePrivilegedSysfsReader(
            mapOf(
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies" to "1017600 1209600 1401600 1689600 1958400 2246400 2438400",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq" to "2246400",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq" to "1958400",
                "/sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq" to "4320000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
        )

        val result = detector.detectPolicies().single()

        assertEquals(1_958_400, result.minFreq)
        assertEquals(1_017_600, result.hardwareMinFreq)
        assertEquals(
            listOf(1017600, 1209600, 1401600, 1689600, 1958400, 2246400, 2438400),
            result.supportedFrequencies,
        )
        assertEquals(2_438_400, result.selectableMaxFreq)
        assertEquals(4_320_000, result.observedMaxFreq)
    }

    @Test
    fun `collects ordered minimum candidates from advertised and active frequencies`() {
        val policyPath = "/sys/devices/system/cpu/cpufreq/policy7"
        val detector = CpuPolicyDetector(
            fileSystem = FakeSysfsFileSystem(listOf(policyPath), emptyMap()),
            privilegedReader = FakePrivilegedSysfsReader(
                mapOf(
                    "$policyPath/scaling_available_frequencies" to "729600 960000 1200000",
                    "$policyPath/cpuinfo_min_freq" to "595200",
                    "$policyPath/cpuinfo_max_freq" to "3187200",
                    "$policyPath/scaling_max_freq" to "3187200",
                    "$policyPath/scaling_min_freq" to "960000",
                    "$policyPath/stats/time_in_state" to "729600 0\n864000 12\n960000 0\n",
                ),
            ),
        )

        val result = detector.detectPolicies().single()

        assertEquals(listOf(595200, 729600, 864000, 960000, 1200000), result.minimumCandidates)
        assertEquals(595200, result.hardwareMinFreq)
    }

    @Test
    fun `uses direct sysfs reads before privileged reader`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy0"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "307200 614400 902400 1209600 1459200 2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "1459200",
            ),
        )
        val privilegedReader = FakePrivilegedSysfsReader(emptyMap())

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
        )

        val result = detector.detectPolicies().single()

        assertEquals(0, result.id)
        assertEquals(2_016_000, result.currentMaxFreq)
    }

    @Test
    fun `ignores malformed policy directories`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf(
                "/sys/devices/system/cpu/cpufreq/policyX",
                "/sys/devices/system/cpu/cpufreq/policy1",
            ),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq" to "1500000",
                "/sys/devices/system/cpu/cpufreq/policy1/cpuinfo_max_freq" to "2000000",
                "/sys/devices/system/cpu/cpufreq/policy1/cpuinfo_min_freq" to "500000",
                "/sys/devices/system/cpu/cpufreq/policy1/scaling_min_freq" to "500000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies()

        assertEquals(1, result.size)
        assertTrue(result.all { it.id == 1 })
    }

    @Test
    fun `does not use privileged lister when normal policy listing is empty`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = emptyList(),
            files = emptyMap(),
        )
        val privilegedReader = FakePrivilegedSysfsReader(
            mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "300000 1228800 2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "1228800",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "300000",
                "/sys/devices/system/cpu/cpufreq/policy0/affected_cpus" to "0 1 2",
                "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_frequencies" to "806400 1612800 3187200",
                "/sys/devices/system/cpu/cpufreq/policy7/cpuinfo_max_freq" to "3187200",
                "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq" to "3187200",
                "/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq" to "806400",
                "/sys/devices/system/cpu/cpufreq/policy7/affected_cpus" to "7",
            ),
        )
        val privilegedLister = FakePrivilegedSysfsLister(
            listings = mapOf(
                ("/sys/devices/system/cpu/cpufreq" to "policy") to listOf(
                    "/sys/devices/system/cpu/cpufreq/policy7",
                    "/sys/devices/system/cpu/cpufreq/policy0",
                ),
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
            privilegedLister = privilegedLister,
        )

        val result = detector.detectPolicies()

        assertTrue(result.isEmpty())
        assertEquals(0, privilegedLister.callCount)
    }

    @Test
    fun `does not consult privileged lister when normal policy listing succeeds`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy0"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "300000 1228800 2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "1228800",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "300000",
            ),
        )
        val privilegedLister = FakePrivilegedSysfsLister(emptyMap())

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
            privilegedLister = privilegedLister,
        )

        val result = detector.detectPolicies()

        assertEquals(listOf(0), result.map { it.id })
        assertEquals(0, privilegedLister.callCount)
    }

    @Test
    fun `reads world-readable policy files before using privileged reader`() {
        val policyPath = "/sys/devices/system/cpu/cpufreq/policy0"
        val detector = CpuPolicyDetector(
            fileSystem = FakeSysfsFileSystem(
                directories = listOf(policyPath),
                files = mapOf(
                    "$policyPath/scaling_available_frequencies" to "300000 1228800 2745600",
                    "$policyPath/cpuinfo_max_freq" to "2745600",
                    "$policyPath/scaling_max_freq" to "1228800",
                    "$policyPath/scaling_min_freq" to "300000",
                ),
            ),
            privilegedReader = FakePrivilegedSysfsReader(emptyMap()),
        )

        val result = detector.detectPolicies().single()

        assertEquals(0, result.id)
        assertEquals(1_228_800, result.currentMaxFreq)
        assertEquals(listOf(300_000, 1_228_800, 2_745_600), result.supportedFrequencies)
    }

    @Test
    fun `reads protected files through privileged reader without changing permissions`() {
        val policyPath = "/sys/devices/system/cpu/cpufreq/policy0"
        val protectedFiles = mapOf(
            "$policyPath/scaling_available_frequencies" to "300000 1228800 2745600",
            "$policyPath/cpuinfo_max_freq" to "2745600",
            "$policyPath/scaling_max_freq" to "1228800",
            "$policyPath/scaling_min_freq" to "300000",
        )
        val fileSystem = FakeSysfsFileSystem(listOf(policyPath), emptyMap())
        val privilegedReader = RecordingPrivilegedSysfsReader(protectedFiles)
        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
        )

        val result = detector.detectPolicies().single()

        assertEquals(0, result.id)
        assertEquals(1_228_800, result.currentMaxFreq)
        assertEquals(listOf(300_000, 1_228_800, 2_745_600), result.supportedFrequencies)
        assertTrue(privilegedReader.readCalls.any { it in protectedFiles.keys })
        assertTrue(privilegedReader.chmodCalls.isEmpty())
    }

    private class FakeSysfsFileSystem(
        private val directories: List<String>,
        val files: Map<String, String>,
    ) : SysfsFileSystem {
        override fun listPolicyDirectories(root: String): List<String> = directories
        override fun readText(path: String): String? = files[path]
    }

    private class FakePrivilegedSysfsReader(
        private val files: Map<String, String>,
    ) : PrivilegedSysfsReader {
        override fun readText(path: String): String? = files[path]
    }

    private class RecordingPrivilegedSysfsReader(
        private val files: Map<String, String>,
    ) : PrivilegedSysfsReader {
        val readCalls = mutableListOf<String>()
        val chmodCalls = emptyList<String>()

        override fun readText(path: String): String? {
            readCalls += path
            return files[path]
        }
    }

    private class FakePrivilegedSysfsLister(
        private val listings: Map<Pair<String, String>, List<String>>,
    ) : PrivilegedSysfsLister {
        var callCount = 0
            private set

        override fun listChildrenWithPrefix(directoryPath: String, prefix: String): List<String>? {
            callCount += 1
            return listings[directoryPath to prefix]
        }
    }
}
