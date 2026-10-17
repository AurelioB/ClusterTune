package com.aure.clustertune.root.host

import org.junit.Assert.*
import org.junit.Test

class HostMaximumVerificationTest {
    @Test fun `full managed profile verifies after a temporary boost without rewriting minimums`() {
        var requested=1000L; var boosted=false; var boostReads=0; var maxWrites=0; var minWrites=0
        var maximumMode=420
        val fs=object: HostFilesystem {
            override fun read(path: String): String = when(path) {
                "max" -> (if(boosted)900L else requested).toString()
                "min" -> {
                    if(boosted && ++boostReads>=3) boosted=false
                    (if(boosted)900L else 200L).toString()
                }
                else -> error(path)
            }
            override fun write(path: String,value: String): Boolean {
                if(path=="min") { minWrites++; error("managed minimum must not be written") }
                maxWrites++; requested=value.toLong(); boosted=true; return true
            }
            override fun mode(path: String) = if(path=="max")maximumMode else 292
            override fun chmod(path: String,mode: Int): Boolean { assertEquals("max",path); maximumMode=mode; return true }
            override fun exists(path: String)=true
        }
        val cpu=CpuDomain("p0","min","max",null,listOf(200),listOf(400,600,1000),1000,1000,200)
        HostApplyEngine(fs).applyWithManagedCpuMinimumsOrThrow(HostCapabilities(listOf(cpu),null),
            ApplyRequest(listOf(600),null,false),setOf("p0"))
        assertEquals("600",fs.read("max")); assertEquals(292,maximumMode)
        assertEquals(1,maxWrites); assertEquals(0,minWrites)
    }

    private class Fs(var maximum: Long = 1000, var minimum: Long = 900, var permissions: Int = 292) : HostFilesystem {
        override fun read(path: String) = (if(path == "max") maximum else minimum).toString()
        override fun mode(path: String) = permissions
        override fun exists(path: String) = true
        override fun write(path: String,value: String): Boolean = error("verification must not write")
        override fun chmod(path: String,mode: Int): Boolean = error("verification must not chmod")
    }
    @Test fun `an independent launch boost can expire during exact verification`() {
        val fs=Fs(); var millis=0L
        val actual=verifySettledHostMaximum(fs,"cpu","max",listOf(600),292,"min",
            nowNanos={millis*1_000_000},waitMillis={
                millis+=it
                if(millis==350L) { fs.minimum=200; fs.maximum=600 }
            })
        assertEquals(600L,actual); assertEquals(350L,millis)
    }
    @Test fun `a persistent boost times out and is never accepted as the requested cap`() {
        val fs=Fs(); var millis=0L
        val error=runCatching { verifySettledHostMaximum(fs,"cpu","max",listOf(600),292,"min",
            nowNanos={millis*1_000_000},waitMillis={millis+=it}) }.exceptionOrNull()
        assertNotNull(error); assertEquals(2_000L,millis)
        assertEquals(1000L,fs.maximum); assertEquals(900L,fs.minimum)
    }
    @Test fun `an unrelated override with no boost is rejected promptly`() {
        val fs=Fs(minimum=200); var millis=0L
        assertNotNull(runCatching { verifySettledHostMaximum(fs,"cpu","max",listOf(600),292,"min",
            nowNanos={millis*1_000_000},waitMillis={millis+=it}) }.exceptionOrNull())
        assertEquals(40L,millis)
    }
    @Test fun `a foreign permission change stops waiting immediately`() {
        val fs=Fs(permissions=420); var waits=0
        assertNotNull(runCatching { verifySettledHostMaximum(fs,"cpu","max",listOf(600),292,"min",
            waitMillis={waits++}) }.exceptionOrNull())
        assertEquals(0,waits); assertEquals(420,fs.permissions)
    }
    @Test fun `maximums-only actuator keeps immediate verification`() {
        val fs=Fs(); var waits=0
        assertNotNull(runCatching { verifySettledHostMaximum(fs,"cpu","max",listOf(600),292,null,
            waitMillis={waits++}) }.exceptionOrNull())
        assertEquals(0,waits)
    }
}
