package io.github.jiangood.xq.ui

import org.junit.Test
import org.junit.Assert.*

class CalibrationValidationTest {

    @Test
    fun saveDisabled_whenNoTestRun() {
        assertFalse("Save should be disabled when test hasn't been run", isSaveEnabled(null))
    }

    @Test
    fun saveDisabled_whenTestFailed() {
        assertFalse("Save should be disabled when test failed", isSaveEnabled(false))
    }

    @Test
    fun saveEnabled_whenTestPassed() {
        assertTrue("Save should be enabled when test passed", isSaveEnabled(true))
    }
}
