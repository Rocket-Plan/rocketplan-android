package com.example.rocketplan_android.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RP-BUG-045 regression — the remote-log `level` wire value must match what the backend accepts.
 *
 * Backend `IosLoggingController` validates `logs.*.level` against `in:DEBUG,INFO,WARNING,ERROR,CRITICAL`.
 * Sending the enum *name* `"WARN"` made Laravel reject the entire batch (HTTP 400) and drop it. The wire
 * value for WARN must be `"WARNING"`.
 */
class LogLevelWireTest {

    /** Mirror of the backend `in:` rule in IosLoggingController.php (keep in sync). */
    private val backendAcceptedLevels = setOf("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL")

    @Test
    fun `WARN serializes to WARNING (not WARN)`() {
        assertThat(LogLevel.WARN.wireValue).isEqualTo("WARNING")
        assertThat(LogLevel.WARN.wireValue).isNotEqualTo("WARN")
    }

    @Test
    fun `other levels keep their names`() {
        assertThat(LogLevel.DEBUG.wireValue).isEqualTo("DEBUG")
        assertThat(LogLevel.INFO.wireValue).isEqualTo("INFO")
        assertThat(LogLevel.ERROR.wireValue).isEqualTo("ERROR")
    }

    @Test
    fun `every level wire value is accepted by the backend`() {
        val wireValues = LogLevel.values().map { it.wireValue }
        assertThat(backendAcceptedLevels).containsAtLeastElementsIn(wireValues)
    }
}
