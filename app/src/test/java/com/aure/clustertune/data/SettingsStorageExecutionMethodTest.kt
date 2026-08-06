package com.aure.clustertune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStorageExecutionMethodTest {

    @Test
    fun `keeps supported execution methods`() {
        assertEquals("pserver-stdout", supportedExecutionMethodId("pserver-stdout"))
        assertEquals("root-shell", supportedExecutionMethodId("root-shell"))
    }

    @Test
    fun `clears removed execution methods for automatic detection`() {
        assertNull(supportedExecutionMethodId("pserver-file-output"))
        assertNull(supportedExecutionMethodId("shizuku"))
    }
}
