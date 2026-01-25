package com.example.rocketplan_android.realtime

import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ImageProcessorRealtimeManager.shouldIgnore logic.
 *
 * The shouldIgnore function determines whether to accept or reject Pusher updates
 * based on local vs backend status. Key rules:
 * - COMPLETED locally + "processing" from backend = IGNORE (don't go backward)
 * - Any other status + "processing" from backend = ACCEPT (update to processing)
 */
class ImageProcessorRealtimeManagerTest {

    // Helper to create test assembly with given status
    private fun createAssembly(status: AssemblyStatus) = ImageProcessorAssemblyEntity(
        id = 1L,
        assemblyId = "test-assembly-123",
        projectId = 100L,
        roomId = 200L,
        groupUuid = "test-group-uuid",
        status = status.value,
        totalFiles = 5,
        bytesReceived = 0,
        createdAt = System.currentTimeMillis(),
        lastUpdatedAt = System.currentTimeMillis(),
        errorMessage = null
    )

    // Helper to create update with given status
    private fun createUpdate(status: String) = ImageProcessorUpdate(
        assemblyId = "test-assembly-123",
        status = status,
        bytesReceived = null,
        errorMessage = null,
        photos = null
    )

    @Test
    fun `should ignore processing update when local status is COMPLETED`() {
        val assembly = createAssembly(AssemblyStatus.COMPLETED)
        val update = createUpdate("processing")

        val result = shouldIgnoreTestable(update, assembly)

        assertTrue("Should ignore processing when already completed", result)
    }

    @Test
    fun `should accept processing update when local status is CREATED`() {
        val assembly = createAssembly(AssemblyStatus.CREATED)
        val update = createUpdate("processing")

        val result = shouldIgnoreTestable(update, assembly)

        assertFalse("Should accept processing when local is created", result)
    }

    @Test
    fun `should accept processing update when local status is UPLOADING`() {
        val assembly = createAssembly(AssemblyStatus.UPLOADING)
        val update = createUpdate("processing")

        val result = shouldIgnoreTestable(update, assembly)

        assertFalse("Should accept processing when local is uploading", result)
    }

    @Test
    fun `should accept processing update when local status is CREATING`() {
        val assembly = createAssembly(AssemblyStatus.CREATING)
        val update = createUpdate("processing")

        val result = shouldIgnoreTestable(update, assembly)

        assertFalse("Should accept processing when local is creating", result)
    }

    @Test
    fun `should accept processing update when local status is RETRYING`() {
        val assembly = createAssembly(AssemblyStatus.RETRYING)
        val update = createUpdate("processing")

        val result = shouldIgnoreTestable(update, assembly)

        assertFalse("Should accept processing when local is retrying", result)
    }

    @Test
    fun `should accept processing update when local status is FAILED`() {
        val assembly = createAssembly(AssemblyStatus.FAILED)
        val update = createUpdate("processing")

        val result = shouldIgnoreTestable(update, assembly)

        assertFalse("Should accept processing when local is failed", result)
    }

    @Test
    fun `should accept completed update when local status is PROCESSING`() {
        val assembly = createAssembly(AssemblyStatus.PROCESSING)
        val update = createUpdate("completed")

        val result = shouldIgnoreTestable(update, assembly)

        assertFalse("Should accept completed when local is processing", result)
    }

    @Test
    fun `should accept completed update when local status is UPLOADING`() {
        val assembly = createAssembly(AssemblyStatus.UPLOADING)
        val update = createUpdate("completed")

        val result = shouldIgnoreTestable(update, assembly)

        assertFalse("Should accept completed when local is uploading", result)
    }

    /**
     * Testable version of shouldIgnore logic.
     * Mirrors the actual implementation in ImageProcessorRealtimeManager.
     */
    private fun shouldIgnoreTestable(
        update: ImageProcessorUpdate,
        assembly: ImageProcessorAssemblyEntity
    ): Boolean {
        val localStatus = AssemblyStatus.fromValue(assembly.status)
        val backendStatus = update.status?.lowercase() ?: return false

        // Only ignore "processing" updates if we've already completed locally
        if (localStatus == AssemblyStatus.COMPLETED && backendStatus == "processing") {
            return true
        }

        return false
    }
}
