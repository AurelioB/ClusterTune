package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterTuneHostGpuDiscoveryTest {
    @Test
    fun `valid KGSL power level resolves the frequency at that kernel index`() {
        assertEquals(
            400_000_000L,
            ClusterTuneHostEntry.kgslObservedMin(
                listOf(200_000_000L, 400_000_000L, 800_000_000L),
                1L,
            ),
        )
    }

    @Test
    fun `invalid KGSL power levels keep the unknown sentinel`() {
        val frequencies = listOf(200_000_000L, 400_000_000L)

        assertEquals(-1L, ClusterTuneHostEntry.kgslObservedMin(frequencies, -1L))
        assertEquals(-1L, ClusterTuneHostEntry.kgslObservedMin(frequencies, 2L))
        assertEquals(-1L, ClusterTuneHostEntry.kgslObservedMin(frequencies, Long.MAX_VALUE))
    }

    @Test
    fun `empty KGSL frequency list keeps the unknown sentinel`() {
        assertEquals(-1L, ClusterTuneHostEntry.kgslObservedMin(emptyList(), 0L))
    }

    @Test
    fun `Thor descending KGSL list resolves level four to 401 MHz`() {
        val thorFrequencies = listOf(
            680_000_000L,
            615_000_000L,
            550_000_000L,
            475_000_000L,
            401_000_000L,
            348_000_000L,
            295_000_000L,
            220_000_000L,
            124_800_000L,
        )

        assertEquals(
            401_000_000L,
            ClusterTuneHostEntry.kgslObservedMin(thorFrequencies, 4L),
        )
    }

    @Test
    fun `duplicate KGSL frequencies retain their power level positions`() {
        val rawFrequencies = ClusterTuneHostEntry.parseRawFrequencies(
            "680000000 615000000 615000000 550000000 401000000",
        )

        assertEquals(
            listOf(680_000_000L, 615_000_000L, 615_000_000L, 550_000_000L, 401_000_000L),
            rawFrequencies,
        )
        assertEquals(
            550_000_000L,
            ClusterTuneHostEntry.kgslObservedMin(rawFrequencies, 3L),
        )
    }

    @Test
    fun `invalid raw KGSL frequency vectors fail closed`() {
        assertEquals(emptyList<Long>(), ClusterTuneHostEntry.parseRawFrequencies(""))
        assertEquals(
            emptyList<Long>(),
            ClusterTuneHostEntry.parseRawFrequencies("680000000 invalid 401000000"),
        )
        assertEquals(
            emptyList<Long>(),
            ClusterTuneHostEntry.parseRawFrequencies("680000000 0 401000000"),
        )
    }

    @Test
    fun `GPU snapshot uses its live writable minimum when a path exists`() {
        assertEquals(
            300_000_000L,
            ClusterTuneHostEntry.gpuSnapshotMin(
                true,
                300_000_000L,
                true,
                listOf(680_000_000L, 401_000_000L),
                1L,
            ),
        )
        assertEquals(
            -1L,
            ClusterTuneHostEntry.gpuSnapshotMin(
                true,
                -1L,
                true,
                listOf(680_000_000L, 401_000_000L),
                1L,
            ),
        )
    }

    @Test
    fun `pathless KGSL snapshot follows its current raw list and power level`() {
        assertEquals(
            401_000_000L,
            ClusterTuneHostEntry.gpuSnapshotMin(
                false,
                -1L,
                true,
                listOf(680_000_000L, 615_000_000L, 550_000_000L, 401_000_000L),
                3L,
            ),
        )
        assertEquals(
            348_000_000L,
            ClusterTuneHostEntry.gpuSnapshotMin(
                false,
                -1L,
                true,
                listOf(680_000_000L, 615_000_000L, 550_000_000L, 348_000_000L),
                3L,
            ),
        )
    }

    @Test
    fun `pathless GPU snapshot fails closed without valid live KGSL inputs`() {
        assertEquals(
            -1L,
            ClusterTuneHostEntry.gpuSnapshotMin(
                false,
                -1L,
                true,
                emptyList(),
                0L,
            ),
        )
        assertEquals(
            -1L,
            ClusterTuneHostEntry.gpuSnapshotMin(
                false,
                -1L,
                true,
                listOf(680_000_000L, 401_000_000L),
                2L,
            ),
        )
        assertEquals(
            -1L,
            ClusterTuneHostEntry.gpuSnapshotMin(
                false,
                -1L,
                false,
                listOf(680_000_000L, 401_000_000L),
                1L,
            ),
        )
    }
}
