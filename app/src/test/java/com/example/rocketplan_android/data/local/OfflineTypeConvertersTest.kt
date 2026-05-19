package com.example.rocketplan_android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class OfflineTypeConvertersTest {

    private val converters = OfflineTypeConverters()

    @Test
    fun toSyncStatus_validValues() {
        SyncStatus.entries.forEach { status ->
            val result = converters.toSyncStatus(status.name)
            assertSame("Storage value '${status.name}' should map to $status", status, result)
        }
    }

    @Test
    fun toSyncStatus_nullReturnsNull() {
        assertEquals(null, converters.toSyncStatus(null))
    }

    @Test
    fun toSyncStatus_unknownValue_throwsDescriptiveException() {
        val unknownValue = "UNKNOWN_MAPPED_VALUE"
        try {
            converters.toSyncStatus(unknownValue)
            fail("Expected IllegalArgumentException for unknown value: $unknownValue")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unknown SyncStatus storage value: '$unknownValue'. Expected one of: PENDING, SYNCING, SYNCED, CONFLICT, FAILED", e.message)
        }
    }

    @Test
    fun toSyncStatus_typoValue_throwsDescriptiveException() {
        val typoValue = "PEDNING"
        try {
            converters.toSyncStatus(typoValue)
            fail("Expected IllegalArgumentException for typo value: $typoValue")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unknown SyncStatus storage value: '$typoValue'. Expected one of: PENDING, SYNCING, SYNCED, CONFLICT, FAILED", e.message)
        }
    }

    @Test
    fun fromSyncStatus_allValues() {
        SyncStatus.entries.forEach { status ->
            val result = converters.fromSyncStatus(status)
            assertEquals(status.name, result)
        }
    }

    @Test
    fun fromSyncStatus_nullReturnsNull() {
        assertEquals(null, converters.fromSyncStatus(null))
    }
}