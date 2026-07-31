package com.aure.clustertune.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionMethodPresentationTest {

    @Test
    fun `pserver uses concise user-facing name`() {
        assertEquals("PServer", executionMethodLabel("pserver-stdout"))
    }

    @Test
    fun `root shell uses concise user-facing name`() {
        assertEquals("Root", executionMethodLabel("root-shell"))
    }
}
