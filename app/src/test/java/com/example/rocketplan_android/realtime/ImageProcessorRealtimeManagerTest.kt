package com.example.rocketplan_android.realtime

import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ImageProcessorRealtimeManager.shouldIgnoreUpdate logic.
 *
 * The shouldIgnoreUpdate function determines whether to accept or reject Pusher updates
 * based on local vs backend status. Key rules:
 * - COMPLETED locally + "processing" from backend = IGNORE (don't go backward)
 * - Any other status + "processing" from backend = ACCEPT (update to processing)
 */
class ImageProcessorRealtimeManagerTest {

    @Test
    fun `should ignore processing update when local status is COMPLETED`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.COMPLETED, "processing"
        )
        assertTrue("Should ignore processing when already completed", result)
    }

    @Test
    fun `should accept processing update when local status is CREATED`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.CREATED, "processing"
        )
        assertFalse("Should accept processing when local is created", result)
    }

    @Test
    fun `should accept processing update when local status is UPLOADING`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.UPLOADING, "processing"
        )
        assertFalse("Should accept processing when local is uploading", result)
    }

    @Test
    fun `should accept processing update when local status is CREATING`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.CREATING, "processing"
        )
        assertFalse("Should accept processing when local is creating", result)
    }

    @Test
    fun `should accept processing update when local status is RETRYING`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.RETRYING, "processing"
        )
        assertFalse("Should accept processing when local is retrying", result)
    }

    @Test
    fun `should accept processing update when local status is FAILED`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.FAILED, "processing"
        )
        assertFalse("Should accept processing when local is failed", result)
    }

    @Test
    fun `should accept completed update when local status is PROCESSING`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.PROCESSING, "completed"
        )
        assertFalse("Should accept completed when local is processing", result)
    }

    @Test
    fun `should accept completed update when local status is UPLOADING`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.UPLOADING, "completed"
        )
        assertFalse("Should accept completed when local is uploading", result)
    }
}
