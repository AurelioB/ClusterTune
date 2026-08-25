package com.aure.clustertune.root.host

import org.junit.Assert.*
import org.junit.Test

class HostProtocolTest {
    @Test fun `maximums-only profile dispatch preserves CPU and GPU minimum votes`() {
        val fs = FakeFs(
            mutableMapOf(
                "min" to "500",
                "max" to "1000",
                "gmin" to "800",
                "gmax" to "900",
            ),
        )
        val cpu = CpuDomain(
            "p0",
            "min",
            "max",
            null,
            listOf(200),
            listOf(400, 800),
            1000,
            1000,
            500,
        )
        val gpu = GpuDomain(
            "g",
            "gmin",
            "gmax",
            null,
            listOf(300, 600, 900),
            stockMax = 900,
            selectableMax = 900,
            observedMin = 800,
        )

        HostProfileApplyDispatcher.applyOrThrow(
            HostApplyEngine(fs),
            HostCapabilities(listOf(cpu), gpu),
            ApplyRequest(
                cpuMax = listOf(400),
                gpuMax = 600,
                resetToStock = false,
                maximumsOnly = true,
            ),
        )

        assertEquals("400", fs.read("max"))
        assertEquals("600", fs.read("gmax"))
        assertEquals("500", fs.read("min"))
        assertEquals("800", fs.read("gmin"))
        assertFalse(fs.operations.any { it.contains("min") })
    }

    @Test fun `full profile dispatch keeps minimum repair behavior`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(200), listOf(400, 800), 1000, 1000, 500)

        HostProfileApplyDispatcher.applyOrThrow(
            HostApplyEngine(fs),
            HostCapabilities(listOf(cpu), null),
            ApplyRequest(listOf(400), null, false),
        )

        assertEquals("400", fs.read("max"))
        assertEquals("200", fs.read("min"))
        assertTrue(fs.operations.any { it == "write:min=200" })
    }

    @Test fun `maximums-only profile does not require readable minimum nodes`() {
        val fs = FakeFs(mutableMapOf("max" to "1000"))
        val cpu = CpuDomain("p0", "missing-min", "max", null, emptyList(), listOf(400, 800), 1000, 1000, -1)

        HostProfileApplyDispatcher.applyOrThrow(
            HostApplyEngine(fs),
            HostCapabilities(listOf(cpu), null),
            ApplyRequest(listOf(400), null, false, maximumsOnly = true),
        )

        assertEquals("400", fs.read("max"))
        assertFalse(fs.operations.any { it.contains("missing-min") })
    }

    @Test fun `maximums-only step mutates only the changed domain`() {
        val fs = FakeFs(
            mutableMapOf(
                "max0" to "600",
                "max1" to "1000",
                "gmax" to "600",
            ),
        )
        fs.modes.putAll(mapOf("max0" to 292, "max1" to 292, "gmax" to 292))
        val cpu0 = CpuDomain(
            "p0", "unused-min0", "max0", null, emptyList(),
            listOf(400, 600, 800), 800, 800, -1, selectableMax = 800,
        )
        val cpu1 = CpuDomain(
            "p1", "unused-min1", "max1", null, emptyList(),
            listOf(600, 800, 1000, 1200), 1200, 1200, -1, selectableMax = 1200,
        )
        val gpu = GpuDomain(
            "g", null, "gmax", null, listOf(300, 600, 900),
            stockMax = 900, selectableMax = 900,
        )

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(listOf(cpu0, cpu1), gpu),
            ApplyRequest(listOf(600, 800), 600, false, maximumsOnly = true),
        )

        assertTrue(result.toString(), result.isSuccess)
        assertEquals("600", fs.read("max0"))
        assertEquals("800", fs.read("max1"))
        assertEquals("600", fs.read("gmax"))
        assertFalse(fs.operations.any { it.contains("max0") || it.contains("gmax") })
        assertEquals(
            listOf("chmod:max1", "write:max1=800", "chmod:max1"),
            fs.operations,
        )
    }

    @Test fun `maximums-only exact transaction is a true no-op`() {
        val fs = FakeFs(mutableMapOf("max" to "600", "gmax" to "600"))
        fs.modes.putAll(mapOf("max" to 292, "gmax" to 292))
        val cpu = CpuDomain(
            "p0", "unused-min", "max", null, emptyList(),
            listOf(400, 600, 800), 800, 800, -1, selectableMax = 800,
        )
        val gpu = GpuDomain(
            "g", null, "gmax", null, listOf(300, 600, 900),
            stockMax = 900, selectableMax = 900,
        )

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(listOf(cpu), gpu),
            ApplyRequest(listOf(600), 600, false, maximumsOnly = true),
        )

        assertTrue(result.toString(), result.isSuccess)
        assertTrue(fs.operations.isEmpty())
    }

    @Test fun `maximums-only matching value repairs only its mode`() {
        val fs = FakeFs(mutableMapOf("max" to "600"))
        fs.modes["max"] = 420
        val cpu = CpuDomain(
            "p0", "unused-min", "max", null, emptyList(),
            listOf(400, 600, 800), 800, 800, -1, selectableMax = 800,
        )

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(listOf(cpu), null),
            ApplyRequest(listOf(600), null, false, maximumsOnly = true),
        )

        assertTrue(result.toString(), result.isSuccess)
        assertEquals("600", fs.read("max"))
        assertEquals(292, fs.modes["max"])
        assertEquals(listOf("chmod:max"), fs.operations)
    }

    @Test fun `maximums-only receipt separates no-op mode-only and value paths`() {
        val fs = FakeFs(mutableMapOf("max0" to "600", "max1" to "1200", "gmax" to "900"))
        fs.modes.putAll(mapOf("max0" to 292, "max1" to 292, "gmax" to 420))
        val cpu0 = CpuDomain(
            "p0", "unused-min0", "max0", null, emptyList(),
            listOf(400, 600, 800), 800, 800, -1, selectableMax = 800,
        )
        val cpu1 = CpuDomain(
            "p1", "unused-min1", "max1", null, emptyList(),
            listOf(500, 900, 1200), 1200, 1200, -1, selectableMax = 1200,
        )
        val gpu = GpuDomain(
            "g", null, "gmax", null, listOf(300, 600, 900),
            stockMax = 900, selectableMax = 900,
        )

        val receipt = HostApplyEngine(fs).applyMaxOnlyTrackedOrThrow(
            HostCapabilities(listOf(cpu0, cpu1), gpu),
            ApplyRequest(listOf(600, 1200), 600, false, maximumsOnly = true),
        )

        assertEquals(setOf("max1", "gmax"), receipt.modePaths)
        assertEquals(setOf("gmax"), receipt.valuePaths)
        assertFalse(fs.operations.any { it.contains("max0") })
        assertFalse(fs.operations.any { it.startsWith("write:max1=") })
        assertTrue(fs.operations.any { it == "write:gmax=600" })
    }

    @Test fun `full profile still rewrites an unchanged maximum`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "600"))
        fs.modes.putAll(mapOf("min" to 420, "max" to 292))
        val cpu = CpuDomain(
            "p0", "min", "max", null, listOf(100),
            listOf(400, 600, 800), 800, 800, 100, selectableMax = 800,
        )

        val result = HostApplyEngine(fs).apply(
            HostCapabilities(listOf(cpu), null),
            ApplyRequest(listOf(600), null, false),
        )

        assertTrue(result.toString(), result.isSuccess)
        assertEquals(
            listOf("chmod:max", "write:max=600", "chmod:max"),
            fs.operations,
        )
    }

    @Test fun `selective maximum rollback restores owned changes without touching no-op domains`() {
        val fs = FakeFs(
            values = mutableMapOf("max0" to "600", "max1" to "1000", "gmax" to "900"),
            failChmodPath = "gmax",
        )
        fs.modes.putAll(mapOf("max0" to 292, "max1" to 420, "gmax" to 420))
        val cpu0 = CpuDomain(
            "p0", "unused-min0", "max0", null, emptyList(),
            listOf(400, 600, 800), 800, 800, -1, selectableMax = 800,
        )
        val cpu1 = CpuDomain(
            "p1", "unused-min1", "max1", null, emptyList(),
            listOf(600, 800, 1000, 1200), 1200, 1200, -1, selectableMax = 1200,
        )
        val gpu = GpuDomain(
            "g", null, "gmax", null, listOf(300, 600, 900),
            stockMax = 900, selectableMax = 900,
        )

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(listOf(cpu0, cpu1), gpu),
            ApplyRequest(listOf(600, 800), 600, false, maximumsOnly = true),
        )

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as HostApplyFailure
        assertTrue(failure.rollbackComplete)
        assertEquals("600", fs.read("max0"))
        assertEquals(292, fs.modes["max0"])
        assertEquals("1000", fs.read("max1"))
        assertEquals(420, fs.modes["max1"])
        assertEquals("900", fs.read("gmax"))
        assertEquals(420, fs.modes["gmax"])
        assertFalse(fs.operations.any { it.contains("max0") })
    }

    @Test fun `determinate early failure does not claim later requested values or modes`() {
        val fs = FakeFs(
            values = mutableMapOf(
                "max0" to "1000",
                "max1" to "1000",
                "max2" to "800",
            ),
            determinateFailureOperationIndex = 1,
            rewritesAfterDeterminateFailure = listOf(
                ExternalRewrite("max1", "800", 384),
                ExternalRewrite("max2", "800", 384),
            ),
        )
        fs.modes.putAll(mapOf("max0" to 420, "max1" to 420, "max2" to 420))
        val cpus = listOf("max0", "max1", "max2").mapIndexed { index, path ->
            CpuDomain(
                "p$index", "unused-min$index", path, null, emptyList(),
                listOf(400, 800, 1000), 1000, 1000, -1, selectableMax = 1000,
            )
        }

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(cpus, null),
            ApplyRequest(listOf(800, 800, 800), null, false, maximumsOnly = true),
        )

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as HostApplyFailure
        assertTrue(failure.rollbackComplete)
        assertEquals(setOf("max0"), failure.attemptedMaximumModePaths)
        assertEquals(setOf("max0"), failure.attemptedMaximumValuePaths)
        assertEquals("800", fs.read("max1"))
        assertEquals(384, fs.modes["max1"])
        assertEquals("800", fs.read("max2"))
        assertEquals(384, fs.modes["max2"])
        val firstExternalRewrite = fs.operations.indexOfFirst { it.startsWith("external:max1=") }
        assertTrue(firstExternalRewrite >= 0)
        assertFalse(
            fs.operations.drop(firstExternalRewrite + 1).any {
                it == "chmod:max1" || it.startsWith("write:max1=") ||
                    it == "chmod:max2" || it.startsWith("write:max2=")
            },
        )
    }

    @Test fun `CPU mode-only stock repair does not claim a later OEM alias`() {
        val fs = FakeFs(
            values = mutableMapOf("max0" to "1000", "max1" to "1200"),
            failChmodPath = "max1",
            rewriteAfterChmod = ExternalRewrite("max0", "800", 384),
        )
        fs.modes.putAll(mapOf("max0" to 292, "max1" to 420))
        val hiddenStock = CpuDomain(
            "p0", "unused-min0", "max0", null, emptyList(),
            listOf(400, 800), 1000, 1000, -1, selectableMax = 800,
        )
        val changed = CpuDomain(
            "p1", "unused-min1", "max1", null, emptyList(),
            listOf(500, 900, 1200), 1200, 1200, -1, selectableMax = 1200,
        )

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(listOf(hiddenStock, changed), null),
            ApplyRequest(listOf(1000, 900), null, false, maximumsOnly = true),
        )

        assertTrue(result.isFailure)
        assertEquals("800", fs.read("max0"))
        assertEquals(384, fs.modes["max0"])
        assertFalse(fs.operations.any { it.startsWith("write:max0=") })
    }

    @Test fun `GPU mode-only stock repair does not claim a later OEM alias`() {
        val fs = FakeFs(
            values = mutableMapOf("max" to "800", "gmax" to "1000"),
            rewriteAfterChmod = ExternalRewrite("gmax", "900", 384),
        )
        fs.modes.putAll(mapOf("max" to 420, "gmax" to 292))
        val cpu = CpuDomain(
            "p0", "unused-min", "max", null, emptyList(),
            listOf(400, 800), 800, 800, -1, selectableMax = 800,
        )
        val gpu = GpuDomain(
            "g", null, "gmax", null, listOf(300, 600, 900),
            stockMax = 1000, observedMax = 1000, selectableMax = 900,
        )

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(listOf(cpu), gpu),
            ApplyRequest(listOf(800), 1000, false, maximumsOnly = true),
        )

        assertTrue(result.isFailure)
        assertEquals("900", fs.read("gmax"))
        assertEquals(384, fs.modes["gmax"])
        assertFalse(fs.operations.any { it.startsWith("write:gmax=") })
    }

    @Test fun `maximums-only rollback preserves an external maximum and permission mode`() {
        val fs = FakeFs(
            values = mutableMapOf("max" to "1000"),
            rewriteAfterWriteOnRead = ExternalRewrite("max", "700", 384),
        )
        fs.modes["max"] = 420
        val cpu = CpuDomain(
            "p0",
            "unused-min",
            "max",
            null,
            emptyList(),
            listOf(400, 800),
            1000,
            1000,
            -1,
        )

        val result = HostApplyEngine(fs).applyMaxOnly(
            HostCapabilities(listOf(cpu), null),
            ApplyRequest(listOf(400), null, false),
        )

        assertTrue(result.isFailure)
        assertEquals(
            setOf("max"),
            (result.exceptionOrNull() as HostApplyFailure).relinquishedMaximumValuePaths,
        )
        assertEquals("700", fs.read("max"))
        assertEquals(384, fs.modes["max"])
        val rewrite = fs.operations.indexOf("external:max=700 mode=384")
        assertTrue(rewrite >= 0)
        assertFalse(fs.operations.drop(rewrite + 1).any { it == "chmod:max" })
    }

    @Test fun `protocol mismatch preserves remote wire version for stale stop`() {
        val mismatch = HostProtocolMismatch(7)
        assertEquals(7, mismatch.remoteVersion)
        assertTrue(mismatch.message!!.contains("remote=7"))
    }

    @Test fun `apply ignores non-positive minimum candidates`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(-1, 0, 200), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false)).isSuccess)
        assertEquals("200", fs.read("min"))
    }

    @Test fun `initial minimum repair tolerates transient OEM raises before max`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"), reRaiseMinWrites = 3)
        val cpu = CpuDomain("p0", "min", "max", null, listOf(200), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false)).isSuccess)
        assertEquals("200", fs.read("min"))
        assertEquals("400", fs.read("max"))
    }

    @Test fun `stock then lower profile repairs OEM minimum in same transaction`() {
        // A stock write can cause the device worker to raise policy0's minimum.
        // The following lower cap must repair that minimum before writing its max,
        // otherwise the kernel rejects the max with "Invalid minimum".
        val fs = FakeFs(
            mutableMapOf("min" to "200", "max" to "400"),
            raiseMinOnMaxWrite = true,
        )
        val cpu = CpuDomain("p0", "min", "max", null, listOf(200), listOf(400, 800), 1000, 1000, 100)
        val engine = HostApplyEngine(fs)
        val capabilities = HostCapabilities(listOf(cpu), null)

        assertTrue(engine.apply(capabilities, ApplyRequest(listOf(1000), null, true)).isSuccess)
        assertEquals("1000", fs.read("max"))
        assertEquals("900", fs.read("min")) // OEM raise after stock max write.

        val beforeLower = fs.orderedOperations.size
        assertTrue(engine.apply(capabilities, ApplyRequest(listOf(400), null, false)).isSuccess)
        assertEquals("400", fs.read("max"))
        assertEquals("200", fs.read("min"))

        val lowerOps = fs.orderedOperations.drop(beforeLower)
        val minWrite = lowerOps.indexOfFirst { it.startsWith("write:min=") }
        val maxWrite = lowerOps.indexOf("write:max=400")
        assertTrue("minimum must be repaired before lowering max", minWrite >= 0 && maxWrite > minWrite)
        assertFalse(lowerOps.subList(minWrite + 1, maxWrite).any { it.startsWith("read:") || it.startsWith("mode:") })
    }

    @Test fun `persistent initial minimum raises abort before max mutation`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"), reRaiseMinWrites = 10)
        val cpu = CpuDomain("p0", "min", "max", null, listOf(200), listOf(400, 800), 1000, 1000, 100)
        val result = HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false))
        assertTrue(result.isFailure)
        assertEquals("1000", fs.read("max"))
    }

    @Test fun `time in state parser uses frequency column only`() {
        assertEquals(listOf(300L, 600L), HostDiscovery.parseTimeInState("300 12\n600 4\n"))
    }

    @Test fun `invalid numeric batch is rejected before filesystem access`() {
        val fs = RealHostFilesystem()
        assertFalse(fs.mutate(listOf(HostMutation.Write("/proc/does-not-exist", "not-numeric"))))
        assertEquals("invalid numeric value", fs.lastMutationError())
    }

    @Test fun `shell batch writes and reads back`() {
        val file = kotlin.io.path.createTempFile("ct-host", ".value").toFile()
        try { assertTrue(RealHostFilesystem(shellPath = "/bin/sh").mutate(listOf(HostMutation.Write(file.absolutePath, "123")))); assertEquals("123", file.readText().trim()) } finally { file.delete() }
    }

    @Test fun `shell candidates stop at first success and report failure`() {
        val fs = RealHostFilesystem(shellPath = "/bin/sh"); val file = kotlin.io.path.createTempFile("ct-host", ".value").toFile()
        try { assertTrue(fs.mutate(listOf(HostMutation.WriteCandidatesNoReadback(file.absolutePath, listOf("200", "300"))))); assertEquals("200", file.readText().trim()); assertFalse(fs.mutate(listOf(HostMutation.WriteCandidatesNoReadback("/proc/does-not-exist", listOf("200"))))) } finally { file.delete() }
    }

    @Test fun `shell batch failure identifies operation and path without values`() {
        val fs = RealHostFilesystem(shellPath = "/bin/sh")
        val file = kotlin.io.path.createTempFile("ct-host", ".value").toFile()
        try {
            assertFalse(
                fs.mutate(
                    listOf(
                        HostMutation.Write(file.absolutePath, "731731"),
                        HostMutation.Write("/proc/does-not-exist", "947947"),
                        HostMutation.Write(file.absolutePath, "159159"),
                    ),
                ),
            )
            val error = requireNotNull(fs.lastMutationError())
            assertTrue(error, error.contains("operation=2/3"))
            assertTrue(error, error.contains("kind=write"))
            assertTrue(error, error.contains("path=/proc/does-not-exist"))
            assertFalse(error, error.contains("731731"))
            assertFalse(error, error.contains("947947"))
            assertFalse(error, error.contains("159159"))
            val failure = fs.lastMutationFailure() as HostDispatchFailure
            assertFalse(failure.indeterminate)
            assertEquals(1, failure.firstFailedOperationIndex)
            assertEquals(error, failure.message)
        } finally {
            file.delete()
        }
    }

    @Test fun `shell timeout reports indeterminate failure`() {
        val shell = kotlin.io.path.createTempFile("ct-shell", ".sh").toFile(); shell.writeText("#!/bin/sh\nsleep 5"); shell.setExecutable(true)
        try { val fs = RealHostFilesystem(pollTimeoutMs = 20L, shellPath = shell.absolutePath); assertFalse(fs.mutate(listOf(HostMutation.Write("/tmp/ct-timeout", "123")))); assertTrue(fs.lastMutationFailure() is HostDispatchFailure) } finally { shell.delete() }
    }


    @Test fun `apply repairs minima before lowering max and rolls back on failure`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"))
        val engine = HostApplyEngine(fs)
        val cpu = CpuDomain("p0", "min", "max", null, listOf(400, 200), listOf(400, 800), 1000, 1000, 100)
        assertTrue(engine.apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false)).isSuccess)
        assertEquals(listOf("chmod:min", "write:min=200", "chmod:max", "write:max=400", "chmod:max"), fs.operations)
        val minWrite = fs.orderedOperations.indexOfFirst { it.startsWith("write:min=") }
        val maxWrite = fs.orderedOperations.indexOfFirst { it == "write:max=400" }
        assertTrue(minWrite >= 0 && maxWrite > minWrite)
        assertFalse(fs.orderedOperations.subList(minWrite + 1, maxWrite).any { it.startsWith("read:") || it.startsWith("mode:") })
    }

    @Test fun `safe minima are left untouched`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000", "gmin" to "100", "gmax" to "900"))
        fs.modes["min"] = 416; fs.modes["gmin"] = 416
        val cpu = CpuDomain("p0", "min", "max", null, emptyList(), listOf(400, 800), 1000, 1000, 100)
        val gpu = GpuDomain("g", "gmin", "gmax", null, listOf(300, 600, 900), stockMax = 900, selectableMax = 900, observedMin = 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(800), 600, false)).isSuccess)
        assertFalse(fs.operations.any { it.contains("min") })
        assertEquals("100", fs.read("min")); assertEquals("100", fs.read("gmin"))
        assertEquals(416, fs.modes["min"]); assertEquals(416, fs.modes["gmin"])
    }

    @Test fun `reconciles minimum raised by OEM during max batch`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000"), raiseMinOnMaxWrite = true)
        val cpu = CpuDomain("p0", "min", "max", null, listOf(200), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false)).isSuccess)
        assertEquals("200", fs.read("min"))
        assertTrue(fs.operations.count { it == "write:min=200" } >= 1)
    }

    @Test fun `repeated OEM minimum raises fail and roll back`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000"), raiseMinOnMaxWrite = true, reRaiseMinWrites = 5)
        val cpu = CpuDomain("p0", "min", "max", null, listOf(200), listOf(400, 800), 1000, 1000, 100)
        val result = HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false))
        assertTrue(result.isFailure)
        assertEquals("1000", fs.read("max"))
        assertEquals("100", fs.read("min"))
    }

    @Test fun `required minimum repair rejects missing safe candidate before max`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(500), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false)).isFailure)
        assertFalse(fs.operations.any { it.contains("max") })
    }

    @Test fun `rejects unsupported gpu and rolls back cpu changes`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000", "gmax" to "800"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100, 200), listOf(400, 800), 1000, 1000, 100)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 800), stockMax = 800, selectableMax = 800)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(400), 700, false)).isFailure)
        assertEquals("1000", fs.read("max")); assertEquals("800", fs.read("gmax"))
        assertTrue("unsupported GPU is rejected before any write", fs.operations.isEmpty())
    }

    @Test fun `rejects unsupported cpu before touching gpu`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000", "gmax" to "800"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100, 200), listOf(400, 800), 1000, 1000, 100)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 800), stockMax = 800, selectableMax = 800)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(600), 400, false)).isFailure)
        assertEquals("1000", fs.read("max")); assertEquals("800", fs.read("gmax"))
        assertTrue("unsupported CPU is rejected before any write", fs.operations.isEmpty())
    }

    @Test fun `stock reset leaves gpu untouched when no gpu target is supplied`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "900", "gmax" to "700"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800, 1000), 800, 1000, 100, selectableMax = 800)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 700, 900), stockMax = 900, observedMax = 1000, selectableMax = 900)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(0), null, true)).isSuccess)
        assertEquals("800", fs.read("max")); assertEquals("700", fs.read("gmax"))
    }

    @Test fun `stock reset uses exact discovered cpu ceiling`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "700"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800, 1000), 1000, 1000, 100, selectableMax = 800)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(0), null, true)).isSuccess)
        assertEquals("1000", fs.read("max"))
    }

    @Test fun `stock reset prefers exact discovered stock ceiling`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "700"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 1000, 1000, 100, selectableMax = 800)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(0), null, true)).isSuccess)
        assertEquals("1000", fs.read("max"))
    }

    @Test fun `stock candidate falls back when preferred write is rejected or ignored`() {
        val rejected = FakeFs(mutableMapOf("min" to "100", "max" to "700"), failFirstWritePath = "max")
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 1000, 1000, 100, selectableMax = 800)
        assertTrue(HostApplyEngine(rejected).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(0), null, true)).isSuccess)
        assertEquals("800", rejected.read("max"))

        val ignored = FakeFs(mutableMapOf("min" to "100", "max" to "700"), noOpWritePath = "max")
        assertTrue(HostApplyEngine(ignored).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(0), null, true)).isFailure)
    }

    @Test fun `mixed stock and capped domains resolve independently`() {
        val fs = FakeFs(mutableMapOf("min0" to "100", "max0" to "700", "min1" to "100", "max1" to "900", "gmax" to "700"))
        val cpu0 = CpuDomain("p0", "min0", "max0", null, listOf(100), listOf(400, 800), 1000, 1000, 100, selectableMax = 800)
        val cpu1 = CpuDomain("p1", "min1", "max1", null, listOf(100), listOf(400, 600, 800), 800, 800, 100, selectableMax = 800)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 700, 900), stockMax = 900, observedMax = 1000, selectableMax = 900)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu0, cpu1), gpu), ApplyRequest(listOf(1000, 600), 1000, false)).isSuccess)
        assertEquals("1000", fs.read("max0")); assertEquals("600", fs.read("max1")); assertEquals("900", fs.read("gmax"))
        assertEquals(420, fs.modes["max0"]); assertEquals(292, fs.modes["max1"]); assertEquals(420, fs.modes["gmax"])
    }

    @Test fun `stock reset with gpu target restores selectable gpu ceiling`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "700", "gmax" to "700"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800, 1000), 1000, 1000, 100, selectableMax = 800)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 700, 900), stockMax = 900, observedMax = 1000, selectableMax = 900)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(0), 700, true)).isSuccess)
        assertEquals("1000", fs.read("max")); assertEquals("900", fs.read("gmax"))
    }

    @Test fun `gpu minimum is repaired before gpu ceiling`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "900", "gmin" to "800", "gmax" to "900"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 800, 800, 100)
        val gpu = GpuDomain("g", "gmin", "gmax", null, listOf(300, 600, 900), stockMax = 900, selectableMax = 900, observedMin = 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(800), 600, false)).isSuccess)
        assertTrue(fs.operations.indexOf("write:gmin=100") < fs.operations.indexOf("write:gmax=600"))
    }

    @Test fun `gpu stock fallback repairs minimum against accepted fallback ceiling`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "900", "gmin" to "600", "gmax" to "700"), failFirstWritePath = "gmax")
        fs.modes["gmin"] = 420; fs.modes["gmax"] = 420
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 900, 900, 100)
        val gpu = GpuDomain("g", "gmin", "gmax", null, listOf(400, 800), stockMax = 800, selectableMax = 400, observedMin = 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(900), 800, false)).isSuccess)
        assertEquals("400", fs.read("gmax"))
        assertTrue(fs.read("gmin")!!.toLong() <= 400)
        assertEquals(420, fs.modes["gmin"]); assertEquals(420, fs.modes["gmax"])
    }

    @Test fun `stabilized gpu stock ceiling restores unavailable advertised stock`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "800", "gmax" to "400"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 800, 800, 100)
        val gpu = GpuDomain("gpu0", null, "gmax", null, emptyList(), stockMax = 400, selectableMax = 400)
        val req = ApplyRequest(listOf(800), 400, true, listOf("p0"), "gpu0", "gmax", 800)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), req).isSuccess)
        assertEquals("800", fs.read("gmax"))
    }

    @Test fun `stabilized gpu stock ceiling rejects mismatched identity before writes`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "800", "gmax" to "400"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 800, 800, 100)
        val gpu = GpuDomain("gpu0", null, "gmax", null, emptyList(), stockMax = 400, selectableMax = 400)
        val req = ApplyRequest(listOf(800), 400, true, listOf("p0"), "wrong", "gmax", 800)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), req).isFailure)
        assertTrue(fs.operations.isEmpty())
    }

    @Test fun `stabilized hint is ignored when gpu frequencies are enumerated`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "800", "gmax" to "400"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 800, 800, 100)
        val gpu = GpuDomain("gpu0", null, "gmax", null, listOf(400, 800), stockMax = 800, selectableMax = 800)
        val req = ApplyRequest(listOf(800), 800, true, listOf("p0"), "gpu0", "gmax", 1_600)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), req).isSuccess)
        assertEquals("800", fs.read("gmax"))
    }

    @Test fun `custom gpu cap below stabilized stock remains protected`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "800", "gmax" to "400"))
        fs.modes["gmax"] = 420
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 800, 800, 100)
        val gpu = GpuDomain("gpu0", null, "gmax", null, emptyList(), stockMax = 400, selectableMax = 400)
        val req = ApplyRequest(listOf(800), 400, false, listOf("p0"), "gpu0", "gmax", 800)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), req).isSuccess)
        assertEquals(292, fs.modes["gmax"]) // 0444: preserve OEM read mask, remove writes
    }

    @Test fun `ceiling protection preserves OEM permission masks`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000"))
        fs.modes["min"] = 416 // 0640
        fs.modes["max"] = 432 // 0660
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(800), null, false)).isSuccess)
        assertEquals(288, fs.modes["max"]) // 0440
        assertEquals(416, fs.modes["min"]) // 0640 remains writable
        assertTrue(fs.chmodModes.contains("max=432"))
        assertTrue(fs.chmodModes.contains("max=288"))
    }

    @Test fun `mixed stock and capped domains finalize before next domain`() {
        val fs = FakeFs(mutableMapOf("min0" to "100", "max0" to "700", "min1" to "100", "max1" to "900", "gmin" to "100", "gmax" to "900"))
        fs.modes.putAll(mapOf("max0" to 432, "max1" to 416, "gmax" to 416))
        val cpu0 = CpuDomain("p0", "min0", "max0", null, listOf(100), listOf(400, 800), 1000, 1000, 100, selectableMax = 800)
        val cpu1 = CpuDomain("p1", "min1", "max1", null, listOf(100), listOf(400, 600, 800), 800, 800, 100, selectableMax = 800)
        val gpu = GpuDomain("g", "gmin", "gmax", null, listOf(400, 600, 700, 900), stockMax = 900, selectableMax = 900, observedMin = 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu0, cpu1), gpu), ApplyRequest(listOf(1000, 600), 600, false)).isSuccess)
        assertEquals(432, fs.modes["max0"])
        assertEquals(288, fs.modes["max1"])
        assertEquals(288, fs.modes["gmax"])
        val firstMax = fs.orderedOperations.indexOfFirst { it.startsWith("chmod:max0=") }
        val minWrites = fs.orderedOperations.withIndex().filter { it.value.startsWith("write:min") }.map { it.index }
        // Each domain's minimum repair is immediately followed by its own max write;
        // do not require a global all-minima-first ordering across domains.
        val min0 = fs.orderedOperations.indexOfFirst { it.startsWith("write:min0=") }
        val max0 = fs.orderedOperations.indexOf("write:max0=1000")
        val min1 = fs.orderedOperations.indexOfFirst { it.startsWith("write:min1=") }
        val max1 = fs.orderedOperations.indexOf("write:max1=600")
        assertTrue(min0 < 0 || max0 > min0)
        assertTrue(min1 < 0 || max1 > min1)
        val maxWrites = fs.orderedOperations.withIndex().filter { it.value.startsWith("write:max") }.map { it.index }
        val firstFinalProtection = fs.orderedOperations.indexOfLast { it == "chmod:max1=288" }
        assertTrue(maxWrites.all { it < firstFinalProtection })
        val firstVerificationRead = firstFinalProtection + 1 + fs.orderedOperations.drop(firstFinalProtection + 1).indexOf("read:max0")
        assertTrue(maxWrites.all { it < firstVerificationRead })
    }

    @Test fun `missing original minimum fails before writes`() {
        val fs = FakeFs(mutableMapOf("max" to "900"))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(800), 900, 900, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(800), null, false)).isFailure)
        assertTrue(fs.operations.isEmpty())
    }

    @Test fun `failed write restores cpu values and modes`() {
        val fs = FakeFs(mutableMapOf("min" to "1000", "max" to "1000"), failPath = "max")
        fs.modes["min"] = 384; fs.modes["max"] = 420
        val cpu = CpuDomain("p0", "min", "max", null, listOf(200), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(800), null, false)).isFailure)
        assertEquals("1000", fs.read("max")); assertEquals("1000", fs.read("min")); assertEquals(420, fs.modes["max"]); assertEquals(384, fs.modes["min"])
        assertTrue(fs.orderedOperations.indexOfLast { it == "write:max=1000" } < fs.orderedOperations.indexOfLast { it == "write:min=1000" })
    }

    @Test fun `rollback failure is surfaced`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000"), failPath = "max", failAllWrites = true)
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 1000, 1000, 100)
        val result = HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(800), null, false))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("rollback incomplete") == true)
        assertTrue(result.exceptionOrNull()?.cause != null)
    }

    @Test fun `atomic failure rolls back all journaled domains without requested targets`() {
        val fs = FakeFs(mutableMapOf("min0" to "1000", "max0" to "1000", "min1" to "100", "max1" to "900"), failEveryWritePath = "min0")
        fs.modes.putAll(mapOf("min0" to 420, "max0" to 420, "min1" to 420, "max1" to 420))
        val cpu0 = CpuDomain("p0", "min0", "max0", null, listOf(100), listOf(400, 800), 1000, 1000, 100)
        val cpu1 = CpuDomain("p1", "min1", "max1", null, listOf(100), listOf(400, 800), 900, 900, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu0, cpu1), null), ApplyRequest(listOf(800, 800), null, false)).isFailure)
        assertEquals(mapOf("min0" to "1000", "max0" to "1000", "min1" to "100", "max1" to "900"), fs.valuesSnapshot())
        assertEquals(420, fs.modes["min0"]); assertEquals(420, fs.modes["max0"])
        assertEquals(420, fs.modes["max1"])
        assertFalse(fs.operations.any { it == "write:max0=800" || it == "write:max1=800" })
    }

    @Test fun `unsafe minimum rollback is skipped when ceiling restore fails`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"), failChmodPath = "max", failAfterFirstWritePath = "max")
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100, 500), listOf(400, 800), 1000, 1000, 100)
        val result = HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false))
        assertTrue("result=$result", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("rollback incomplete") == true)
        assertEquals("100", fs.read("min"))
    }

    @Test fun `no-op minimum chmod fails and rolls back safely`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"), noOpChmodPath = "min")
        fs.modes["min"] = 292
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100, 200), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false)).isFailure)
        assertEquals("500", fs.read("min")); assertEquals("1000", fs.read("max"))
        assertEquals(292, fs.modes["min"]); assertEquals(420, fs.modes["max"])
    }

    @Test fun `failing minimum writes with unsafe readback fails before max`() {
        val fs = FakeFs(mutableMapOf("min" to "500", "max" to "1000"), failEveryWritePath = "min")
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100, 200), listOf(400, 800), 1000, 1000, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(400), null, false)).isFailure)
        assertFalse(fs.operations.any { it == "write:max=400" })
    }

    @Test fun `stock transition from protected max restores writable mode`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "800"))
        fs.modes["max"] = 292
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 800, 800, 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), null), ApplyRequest(listOf(800), null, true)).isSuccess)
        assertEquals(420, fs.modes["max"])
    }

    @Test fun `gpu failure rolls cpu max back to exact protected mode`() {
        val fs = FakeFs(mutableMapOf("min" to "1000", "max" to "800", "gmin" to "1000", "gmax" to "900"), failChmodPath = "gmax")
        fs.modes.putAll(mapOf("max" to 292, "gmax" to 420))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 800, 800, 100)
        val gpu = GpuDomain("g", "gmin", "gmax", null, listOf(300, 600, 900), stockMax = 900, selectableMax = 900, observedMin = 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(800), 600, false)).isFailure)
        assertEquals("800", fs.read("max")); assertEquals(292, fs.modes["max"])
        val gmaxRestore = fs.orderedOperations.indexOfLast { it == "write:gmax=900" }
        val cpuMaxRestore = fs.orderedOperations.indexOfLast { it == "write:max=800" }
        assertTrue(gmaxRestore < cpuMaxRestore)
    }

    @Test fun `hidden stock cpu rollback uses selectable fallback`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000", "gmax" to "900"), failChmodPath = "gmax", rejectWriteValue = "max" to "1000")
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 1000, 1000, 100, selectableMax = 800)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 600, 900), stockMax = 900, selectableMax = 900)
        val result = HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(400), 600, false))
        assertTrue(result.isFailure)
        assertEquals("800", fs.read("max"))
        assertEquals(mapOf("max" to setOf(800L)), (result.exceptionOrNull() as HostApplyFailure).rollbackOwnedValues)
    }

    @Test fun `hidden stock gpu rollback uses selectable fallback`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "1000", "gmax" to "1000"), failChmodPath = "gmax", rejectWriteValue = "gmax" to "1000")
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 1000, 1000, 100, selectableMax = 800)
        val cpu1 = CpuDomain("p1", "min1", "max1", null, listOf(100), listOf(400, 800), 1000, 1000, 100, selectableMax = 800)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 600, 900), stockMax = 900, selectableMax = 900)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(400), 600, false)).isFailure)
        assertEquals("900", fs.read("gmax"))
    }

    @Test fun `capped rollback does not broaden to stock fallback`() {
        val fs = FakeFs(mutableMapOf("min" to "100", "max" to "700", "gmax" to "900"), failChmodPath = "gmax", rejectWriteValue = "max" to "700")
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 700, 800, 100, selectableMax = 800)
        val gpu = GpuDomain("g", null, "gmax", null, listOf(400, 600, 900), stockMax = 900, selectableMax = 900)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(400), 600, false)).isFailure)
        assertEquals("400", fs.read("max"))
    }

    @Test fun `protection failure restores cpu and gpu values and modes`() {
        val fs = FakeFs(
            mutableMapOf("min" to "100", "max" to "1000", "gmin" to "100", "gmax" to "900"),
            failChmodPath = "gmax",
        )
        fs.modes.putAll(mapOf("min" to 384, "max" to 420, "gmin" to 384, "gmax" to 420))
        val cpu = CpuDomain("p0", "min", "max", null, listOf(100), listOf(400, 800), 1000, 1000, 100)
        val gpu = GpuDomain("g", "gmin", "gmax", null, listOf(300, 600, 900), stockMax = 900, selectableMax = 900, observedMin = 100)
        assertTrue(HostApplyEngine(fs).apply(HostCapabilities(listOf(cpu), gpu), ApplyRequest(listOf(800), 600, false)).isFailure)
        assertEquals(mapOf("min" to "100", "max" to "1000", "gmin" to "100", "gmax" to "900"), fs.valuesSnapshot())
        assertEquals(384, fs.modes["min"]); assertEquals(420, fs.modes["max"])
        assertEquals(384, fs.modes["gmin"]); assertEquals(420, fs.modes["gmax"])
    }

    private data class ExternalRewrite(val path: String, val value: String, val mode: Int)

    private class FakeFs(
        private val values: MutableMap<String, String>,
        private val failPath: String? = null,
        private val failChmodPath: String? = null,
        private val failAllWrites: Boolean = false,
        private val failAfterFirstWritePath: String? = null,
        private val failFirstWritePath: String? = null,
        private val failEveryWritePath: String? = null,
        private val noOpWritePath: String? = null,
        private val rejectWriteValue: Pair<String, String>? = null,
        private val noOpChmodPath: String? = null,
        private val raiseMinOnMaxWrite: Boolean = false,
        private val reRaiseMinWrites: Int = 0,
        private val rewriteAfterWriteOnRead: ExternalRewrite? = null,
        private val rewriteAfterChmod: ExternalRewrite? = null,
        private val determinateFailureOperationIndex: Int? = null,
        private val rewritesAfterDeterminateFailure: List<ExternalRewrite> = emptyList(),
    ) : HostFilesystem {
        val operations = mutableListOf<String>()
        val orderedOperations = mutableListOf<String>()
        val chmodModes = mutableListOf<String>()
        val modes = mutableMapOf<String, Int>()
        private var externalRewriteApplied = false
        private var chmodRewriteApplied = false
        private var mutationFailure: Throwable? = null
        override fun lastMutationFailure(): Throwable? = mutationFailure
        override fun mutate(operations: List<HostMutation>): Boolean {
            val failedIndex = determinateFailureOperationIndex
                ?: return super<HostFilesystem>.mutate(operations)
            require(failedIndex in operations.indices)
            check(super<HostFilesystem>.mutate(operations.take(failedIndex))) {
                "fake determinate prefix unexpectedly failed"
            }
            rewritesAfterDeterminateFailure.forEach { rewrite ->
                values[rewrite.path] = rewrite.value
                modes[rewrite.path] = rewrite.mode
                this.operations += "external:${rewrite.path}=${rewrite.value} mode=${rewrite.mode}"
            }
            mutationFailure = HostDispatchFailure(
                indeterminate = false,
                message = "fake determinate transaction failure",
                firstFailedOperationIndex = failedIndex,
            )
            return false
        }
        override fun read(path: String): String? {
            orderedOperations += "read:$path"
            rewriteAfterWriteOnRead?.takeIf {
                !externalRewriteApplied && it.path == path && (writeCounts[path] ?: 0) > 0
            }?.let { rewrite ->
                externalRewriteApplied = true
                values[path] = rewrite.value
                modes[path] = rewrite.mode
                operations += "external:$path=${rewrite.value} mode=${rewrite.mode}"
            }
            return values[path]
        }
        private var failed = false
        private val writeCounts = mutableMapOf<String, Int>()
        override fun write(path: String, value: String): Boolean {
            operations += "write:$path=$value"; orderedOperations += "write:$path=$value"
            writeCounts[path] = (writeCounts[path] ?: 0) + 1
            if (failAllWrites || path == failEveryWritePath || (rejectWriteValue?.first == path && rejectWriteValue.second == value && writeCounts[path]!! >= 1) || (path == failPath && !failed) ||
                (path == failFirstWritePath && writeCounts[path]!! == 1) ||
                (path == failAfterFirstWritePath && writeCounts[path]!! > 1)) { failed = true; return false }
            if (path != noOpWritePath) values[path] = value
            if (raiseMinOnMaxWrite && path == "max") values["min"] = "900"
            if (reRaiseMinWrites > 0 && path == "min" && writeCounts[path]!! <= reRaiseMinWrites) values[path] = "900"
            return true
        }
        override fun mode(path: String): Int? { orderedOperations += "mode:$path"; return modes[path] ?: 420 }
        override fun chmod(path: String, mode: Int): Boolean {
            operations += "chmod:$path"
            orderedOperations += "chmod:$path=$mode"
            chmodModes += "$path=$mode"
            if (path == failChmodPath && mode == 292) return false
            if (path != noOpChmodPath) modes[path] = mode
            rewriteAfterChmod?.takeIf { !chmodRewriteApplied && it.path == path }?.let { rewrite ->
                chmodRewriteApplied = true
                values[path] = rewrite.value
                modes[path] = rewrite.mode
                operations += "external:$path=${rewrite.value} mode=${rewrite.mode}"
            }
            return true
        }
        override fun exists(path: String) = values.containsKey(path)
        fun valuesSnapshot(): Map<String, String> = values.toMap()
    }

}
