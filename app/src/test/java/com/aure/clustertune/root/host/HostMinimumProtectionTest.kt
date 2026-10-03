package com.aure.clustertune.root.host

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HostMinimumProtectionTest {
    private val caps = HostCapabilities(listOf(cpu("policy0"), cpu("policy3")), null)
    private fun cpu(id: String) = CpuDomain(id, "$id/min", "$id/max", null,
        listOf(100), listOf(100, 200, 400, 800), 800, 800, 500)
    private fun request(a: Long = 400, b: Long = 800, stock: Boolean = false, maxOnly: Boolean = false) =
        ApplyRequest(listOf(a, b), null, stock, maximumsOnly = maxOnly)

    @Test fun `underclock protects only its CPU and Stock restores original permissions`() {
        val fs = FakeFs(); val store = Store(); val manager = manager(fs, store)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request())
        assertEquals("100", fs.values["policy0/min"])
        assertEquals(288, fs.modes["policy0/min"]) // 0440: retain original read permissions.
        assertEquals(432, fs.modes["policy3/min"])
        assertFalse(fs.write("policy0/min", "400"))
        fs.operations.clear()
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(800, 800, stock = true))
        assertEquals(432, fs.modes["policy0/min"])
        assertEquals("800", fs.values["policy0/max"])
        assertTrue(store.record!!.nodes.isEmpty())
        // Do not write a possibly boosted snapshot back as a lasting minimum request.
        assertFalse(fs.operations.any { it.startsWith("write policy0/min") })
        assertTrue(fs.operations.indexOf("write policy0/max 800") < fs.operations.indexOf("chmod policy0/min 432"))
    }

    @Test fun `independent minimum boost is not treated as permission loss`() {
        val fs = FakeFs().apply { boost = true }; val manager = manager(fs)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(400, 400))
        assertEquals("900", fs.read("policy0/min"))
        assertEquals("100", fs.values["policy0/min"])
        assertEquals(288, fs.modes["policy0/min"])
    }

    @Test fun `repeated profiles retain the original mode without rewriting owned minimums`() {
        val fs = FakeFs(); val store = Store(); val manager = manager(fs, store)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request())
        fs.operations.clear()
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(200))
        assertFalse(fs.operations.any { it.contains("policy0/min") })
        assertEquals(432, store.record!!.nodes.single().originalMode)
    }

    @Test fun `returning one cluster to Stock releases only that cluster`() {
        val fs = FakeFs(); val store = Store(); val manager = manager(fs, store)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(400, 400))
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(800, 200))
        assertEquals(432, fs.modes["policy0/min"])
        assertEquals(288, fs.modes["policy3/min"])
        assertEquals(listOf("policy3"), store.record!!.nodes.map { it.policyId })
    }

    @Test fun `maximum-only path does not acquire or release minimum protection`() {
        val fs = FakeFs(); val manager = manager(fs)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request())
        fs.operations.clear()
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(800, 800, maxOnly = true))
        assertFalse(fs.operations.any { it.contains("/min") })
        assertEquals(288, fs.modes["policy0/min"])
    }

    @Test fun `minimum locked by another owner is never taken over`() {
        val fs = FakeFs().apply { modes["policy0/min"] = 292 }
        assertThrows(HostApplyFailure::class.java) { manager(fs).applyOrThrow(HostApplyEngine(fs), caps, request()) }
        assertEquals(292, fs.modes["policy0/min"])
        assertTrue(fs.operations.isEmpty())
    }

    @Test fun `failed ceiling update releases newly protected nodes and rolls back ceiling`() {
        val fs = FakeFs().apply { reject = { path, value -> path == "policy0/max" && value == "400" } }
        val store = Store(); val manager = manager(fs, store)
        val failure = assertThrows(HostApplyFailure::class.java) { manager.applyOrThrow(HostApplyEngine(fs), caps, request()) }
        assertTrue(failure.rollbackComplete)
        assertEquals(432, fs.modes["policy0/min"])
        assertEquals("800", fs.values["policy0/max"])
        assertTrue(store.record!!.nodes.isEmpty())
    }

    @Test fun `failed permission release remains recoverable on retry`() {
        val fs = FakeFs(); val store = Store(); val manager = manager(fs, store)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request())
        fs.failUnlock = true
        assertThrows(IllegalStateException::class.java) { manager.releaseOrThrow(caps) }
        assertEquals(1, store.record!!.nodes.size)
        fs.failUnlock = false
        manager.releaseOrThrow(caps)
        assertEquals(432, fs.modes["policy0/min"])
        assertTrue(store.record!!.nodes.isEmpty())
    }

    @Test fun `new host recovers same boot record without copying a boosted value`() {
        val fs = FakeFs(); val store = Store()
        manager(fs, store).applyOrThrow(HostApplyEngine(fs), caps, request())
        fs.boost = true; fs.operations.clear()
        manager(fs, store).recoverOrThrow(caps)
        assertEquals(432, fs.modes["policy0/min"])
        assertFalse(fs.operations.any { it.startsWith("write") })
        assertTrue(store.record!!.nodes.isEmpty())
    }

    @Test fun `reboot discards stale record without changing new sysfs nodes`() {
        val fs = FakeFs(); val store = Store(MinimumProtectionRecord("old-boot", listOf(ProtectedMinimum("policy0", 432))))
        manager(fs, store).recoverOrThrow(caps)
        assertTrue(fs.operations.isEmpty())
        assertTrue(store.record!!.nodes.isEmpty())
    }

    @Test fun `write ahead failure prevents frequency mutation`() {
        val fs = FakeFs(); val store = Store(); val manager = manager(fs, store)
        manager.recoverOrThrow(caps)
        store.failSave = true
        assertThrows(HostApplyFailure::class.java) { manager.applyOrThrow(HostApplyEngine(fs), caps, request()) }
        assertFalse(fs.operations.any { it.startsWith("write") })
        assertEquals(432, fs.modes["policy0/min"])
    }

    @Test fun `unknown recovery policy is rejected before any mutation`() {
        val fs = FakeFs(); val store = Store(MinimumProtectionRecord("boot", listOf(ProtectedMinimum("../../etc/file", 432))))
        assertThrows(IllegalArgumentException::class.java) { manager(fs, store).recoverOrThrow(caps) }
        assertTrue(fs.operations.isEmpty())
    }

    @Test fun `external permission change is preserved on release`() {
        val fs = FakeFs(); val manager = manager(fs)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request())
        fs.modes["policy0/min"] = 420
        manager.releaseOrThrow(caps)
        assertEquals(420, fs.modes["policy0/min"])
    }

    @Test fun `disk record survives host replacement and excludes concurrent owners`() {
        val directory = Files.createTempDirectory("ct-minimum-test").toFile()
        try {
            val record = MinimumProtectionRecord("boot", listOf(ProtectedMinimum("policy0", 432)))
            FileMinimumProtectionStore(directory).use { first ->
                first.save(record)
                assertThrows(Exception::class.java) { FileMinimumProtectionStore(directory) }
            }
            FileMinimumProtectionStore(directory).use { assertEquals(record, it.load()) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `invalid GPU request does not acquire CPU minimums`() {
        val fs = FakeFs()
        assertThrows(IllegalArgumentException::class.java) {
            manager(fs).applyOrThrow(HostApplyEngine(fs), caps, request().copy(gpuMax = 500))
        }
        assertTrue(fs.operations.isEmpty())
    }

    @Test fun `invalid later recovery entry cannot partially load ownership on retry`() {
        val fs = FakeFs()
        val store = Store(MinimumProtectionRecord("boot", listOf(
            ProtectedMinimum("policy0", 432), ProtectedMinimum("unknown", 432))))
        val manager = manager(fs, store)
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { manager.recoverOrThrow(caps) } }
        assertTrue(fs.operations.isEmpty())
        assertEquals(2, store.record!!.nodes.size)
    }

    @Test fun `failure protecting later cluster releases earlier acquisition`() {
        val fs = FakeFs().apply { reject = { path, _ -> path == "policy3/min" } }
        val store = Store()
        assertThrows(HostApplyFailure::class.java) {
            manager(fs, store).applyOrThrow(HostApplyEngine(fs), caps, request(400, 400))
        }
        assertEquals(432, fs.modes["policy0/min"])
        assertEquals(432, fs.modes["policy3/min"])
        assertEquals("800", fs.values["policy0/max"])
        assertTrue(store.record!!.nodes.isEmpty())
    }

    @Test fun `failed profile preserves preexisting minimum ownership`() {
        val fs = FakeFs(); val store = Store(); val manager = manager(fs, store)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request())
        fs.reject = { path, value -> path == "policy3/max" && value == "200" }
        assertThrows(HostApplyFailure::class.java) {
            manager.applyOrThrow(HostApplyEngine(fs), caps, request(200, 200))
        }
        assertEquals(288, fs.modes["policy0/min"])
        assertEquals(432, fs.modes["policy3/min"])
        assertEquals(listOf("policy0"), store.record!!.nodes.map { it.policyId })
        assertEquals("400", fs.values["policy0/max"])
    }

    @Test fun `replacement recovers existing ownership before acquiring a new underclock`() {
        val fs = FakeFs(); val store = Store()
        manager(fs, store).applyOrThrow(HostApplyEngine(fs), caps, request())
        val replacement = HostMinimumProtection(fs, store, "boot")
        replacement.applyOrThrow(HostApplyEngine(fs), caps, request(200))
        assertEquals(288, fs.modes["policy0/min"])
        assertEquals("200", fs.values["policy0/max"])
        assertEquals(432, store.record!!.nodes.single().originalMode)
        replacement.releaseOrThrow(caps)
        assertEquals(432, fs.modes["policy0/min"])
    }

    @Test fun `Stock without a vendor writer leaves a writable supported floor instead of replaying a boost`() {
        val fs = FakeFs().apply { boost = true }
        val manager = manager(fs)
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(400, 400))
        manager.applyOrThrow(HostApplyEngine(fs), caps, request(800, 800, stock = true))
        fs.boost = false
        assertEquals("100", fs.read("policy0/min"))
        assertEquals(432, fs.modes["policy0/min"])
        assertEquals("800", fs.read("policy0/max"))
        assertTrue(fs.write("policy0/min", "500"))
        assertEquals("500", fs.read("policy0/min"))
    }

    @Test fun `missing supported minimum fails before taking ownership`() {
        val fs = FakeFs(); val store = Store()
        val unsupported = caps.copy(cpus = caps.cpus.map { it.copy(minimumCandidates = emptyList()) })
        assertThrows(HostApplyFailure::class.java) {
            manager(fs, store).applyOrThrow(HostApplyEngine(fs), unsupported, request())
        }
        assertTrue(fs.operations.isEmpty())
        assertTrue(store.record!!.nodes.isEmpty())
    }

    @Test fun `unreadable minimum permissions fail without lowering clocks`() {
        val fs = FakeFs(); val store = Store()
        fs.modes.remove("policy0/min")
        assertThrows(HostApplyFailure::class.java) {
            manager(fs, store).applyOrThrow(HostApplyEngine(fs), caps, request())
        }
        assertTrue(fs.operations.isEmpty())
        assertTrue(store.record!!.nodes.isEmpty())
    }

    private fun manager(fs: FakeFs, store: Store = Store()) = HostMinimumProtection(fs, store, "boot")
    private class Store(var record: MinimumProtectionRecord? = null) : MinimumProtectionStore {
        var failSave = false
        override fun load() = record
        override fun save(record: MinimumProtectionRecord) {
            check(!failSave) { "disk unavailable" }
            this.record = record
        }
    }
    private class FakeFs : HostFilesystem {
        val values = mutableMapOf("policy0/min" to "500", "policy0/max" to "800", "policy3/min" to "500", "policy3/max" to "800")
        val modes = values.mapValues { 432 }.toMutableMap()
        val operations = mutableListOf<String>()
        var boost = false
        var failUnlock = false
        var reject: (String, String) -> Boolean = { _, _ -> false }
        override fun read(path: String): String? = if (boost && path.endsWith("/min")) "900" else values[path]
        override fun write(path: String, value: String): Boolean {
            operations += "write $path $value"
            if (reject(path, value) || (modes[path] ?: 0) and 0x92 == 0) return false
            values[path] = value
            return true
        }
        override fun mode(path: String) = modes[path]
        override fun chmod(path: String, mode: Int): Boolean {
            operations += "chmod $path $mode"
            if (failUnlock && path.endsWith("/min") && mode == 432) return false
            modes[path] = mode
            return true
        }
        override fun exists(path: String) = path in values
    }
}
