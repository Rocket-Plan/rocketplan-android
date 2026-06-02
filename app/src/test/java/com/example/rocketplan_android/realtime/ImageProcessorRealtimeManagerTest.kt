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
 * - QUEUED/CREATING/CREATED/UPLOADING locally + "processing" from backend = IGNORE
 *   (the backend fires "processing" immediately on assembly creation, before the
 *   photos have finished uploading, so accepting it would advance the UI prematurely)
 * - RETRYING/FAILED locally + "processing" from backend = ACCEPT (genuine reprocess)
 * - Any status + "completed" from backend = ACCEPT (terminal forward progress)
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
    fun `should ignore processing update when local status is CREATED`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.CREATED, "processing"
        )
        assertTrue("Should ignore premature processing while local is created", result)
    }

    @Test
    fun `should ignore processing update when local status is UPLOADING`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.UPLOADING, "processing"
        )
        assertTrue("Should ignore premature processing while local is uploading", result)
    }

    @Test
    fun `should ignore processing update when local status is CREATING`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.CREATING, "processing"
        )
        assertTrue("Should ignore premature processing while local is creating", result)
    }

    @Test
    fun `should ignore processing update when local status is QUEUED`() {
        val result = ImageProcessorRealtimeManager.shouldIgnoreUpdate(
            AssemblyStatus.QUEUED, "processing"
        )
        assertTrue("Should ignore premature processing while local is queued", result)
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
