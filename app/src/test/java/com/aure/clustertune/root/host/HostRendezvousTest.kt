package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HostRendezvousTest {
    @Test
    fun `handoff action is app owned`() {
        assertEquals("com.aure.clustertune.HOST_HANDOFF", HostRendezvous.ACTION)
    }

    @Test
    fun `handoff rejects any changed identity field`() {
        val expected = HostRendezvous.HandoffIdentity("clustertune.host.1", "nonce", 42L, "root-shell")
        assertTrue(HostRendezvous.matches(expected, expected))
        assertFalse(HostRendezvous.matches(expected, expected.copy(nonce = "other")))
        assertFalse(HostRendezvous.matches(expected, expected.copy(name = "other")))
        assertFalse(HostRendezvous.matches(expected, expected.copy(generation = 43L)))
        assertFalse(HostRendezvous.matches(expected, expected.copy(method = "pserver-stdout")))
    }
}
