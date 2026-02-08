package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.repository.mapper.PendingLocationCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomCreationPayload
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class IdRemapServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val gson = Gson()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val service = IdRemapService(localDataService, gson, testDispatcher)

    // ========================================================================
    // Helper methods
    // ========================================================================

    private fun makePropertyOp(
        operationId: String = "op-prop-1",
        payload: PendingPropertyCreationPayload
    ): OfflineSyncQueueEntity = OfflineSyncQueueEntity(
        operationId = operationId,
        entityType = "property",
        entityId = payload.localPropertyId,
        entityUuid = payload.propertyUuid,
        operationType = SyncOperationType.CREATE,
        payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
        priority = SyncPriority.MEDIUM,
        createdAt = Date()
    )

    private fun makeLocationOp(
        operationId: String = "op-loc-1",
        payload: PendingLocationCreationPayload
    ): OfflineSyncQueueEntity = OfflineSyncQueueEntity(
        operationId = operationId,
        entityType = "location",
        entityId = payload.localLocationId,
        entityUuid = payload.locationUuid,
        operationType = SyncOperationType.CREATE,
        payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
        priority = SyncPriority.MEDIUM,
        createdAt = Date()
    )

    private fun makeRoomOp(
        operationId: String = "op-room-1",
        payload: PendingRoomCreationPayload
    ): OfflineSyncQueueEntity = OfflineSyncQueueEntity(
        operationId = operationId,
        entityType = "room",
        entityId = payload.localRoomId,
        entityUuid = payload.roomUuid ?: "",
        operationType = SyncOperationType.CREATE,
        payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
        priority = SyncPriority.MEDIUM,
        createdAt = Date()
    )

    private fun defaultPropertyPayload(
        localPropertyId: Long = 1L,
        propertyUuid: String = "prop-uuid-1",
        projectId: Long = -100L,
        propertyTypeId: Int = 1,
        propertyTypeValue: String? = null,
        idempotencyKey: String? = null
    ) = PendingPropertyCreationPayload(
        localPropertyId = localPropertyId,
        propertyUuid = propertyUuid,
        projectId = projectId,
        propertyTypeId = propertyTypeId,
        propertyTypeValue = propertyTypeValue,
        idempotencyKey = idempotencyKey
    )

    private fun defaultLocationPayload(
        localLocationId: Long = 10L,
        locationUuid: String = "loc-uuid-1",
        projectId: Long = -100L,
        propertyLocalId: Long = -200L,
        locationName: String = "Floor 1",
        locationTypeId: Long = 1L,
        type: String = "level",
        floorNumber: Int = 1,
        isCommon: Boolean = false,
        isAccessible: Boolean = true,
        isCommercial: Boolean = false,
        idempotencyKey: String? = null
    ) = PendingLocationCreationPayload(
        localLocationId = localLocationId,
        locationUuid = locationUuid,
        projectId = projectId,
        propertyLocalId = propertyLocalId,
        locationName = locationName,
        locationTypeId = locationTypeId,
        type = type,
        floorNumber = floorNumber,
        isCommon = isCommon,
        isAccessible = isAccessible,
        isCommercial = isCommercial,
        idempotencyKey = idempotencyKey
    )

    private fun defaultRoomPayload(
        localRoomId: Long = 20L,
        roomUuid: String? = "room-uuid-1",
        projectId: Long = -100L,
        roomName: String = "Living Room",
        roomTypeId: Long = 1L,
        roomTypeName: String? = "Living",
        isSource: Boolean = false,
        isExterior: Boolean = false,
        levelServerId: Long? = -300L,
        locationServerId: Long? = -400L,
        levelUuid: String = "level-uuid-1",
        locationUuid: String = "location-uuid-1",
        idempotencyKey: String? = null
    ) = PendingRoomCreationPayload(
        localRoomId = localRoomId,
        roomUuid = roomUuid,
        projectId = projectId,
        roomName = roomName,
        roomTypeId = roomTypeId,
        roomTypeName = roomTypeName,
        isSource = isSource,
        isExterior = isExterior,
        levelServerId = levelServerId,
        locationServerId = locationServerId,
        levelUuid = levelUuid,
        locationUuid = locationUuid,
        idempotencyKey = idempotencyKey
    )

    // ========================================================================
    // remapProjectId tests
    // ========================================================================

    @Test
    fun `remapProjectId - same IDs returns 0 and does no work`() = runTest {
        val result = service.remapProjectId(localProjectId = 100L, serverId = 100L)

        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) { localDataService.getPendingOperationsForEntityType(any()) }
        coVerify(exactly = 0) { localDataService.enqueueSyncOperation(any()) }
    }

    @Test
    fun `remapProjectId - updates property payload projectId`() = runTest {
        val payload = defaultPropertyPayload(projectId = -100L)
        val op = makePropertyOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("property") } returns listOf(op)
        coEvery { localDataService.getPendingOperationsForEntityType("location") } returns emptyList()
        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns emptyList()

        val result = service.remapProjectId(localProjectId = -100L, serverId = 1000L)

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingPropertyCreationPayload::class.java
        )
        assertThat(updatedPayload.projectId).isEqualTo(1000L)
        // Other fields should remain unchanged
        assertThat(updatedPayload.localPropertyId).isEqualTo(payload.localPropertyId)
        assertThat(updatedPayload.propertyUuid).isEqualTo(payload.propertyUuid)
        assertThat(updatedPayload.propertyTypeId).isEqualTo(payload.propertyTypeId)
    }

    @Test
    fun `remapProjectId - updates location payload projectId`() = runTest {
        val payload = defaultLocationPayload(projectId = -100L)
        val op = makeLocationOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("property") } returns emptyList()
        coEvery { localDataService.getPendingOperationsForEntityType("location") } returns listOf(op)
        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns emptyList()

        val result = service.remapProjectId(localProjectId = -100L, serverId = 2000L)

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingLocationCreationPayload::class.java
        )
        assertThat(updatedPayload.projectId).isEqualTo(2000L)
        // Other fields should remain unchanged
        assertThat(updatedPayload.localLocationId).isEqualTo(payload.localLocationId)
        assertThat(updatedPayload.propertyLocalId).isEqualTo(payload.propertyLocalId)
        assertThat(updatedPayload.locationName).isEqualTo(payload.locationName)
    }

    @Test
    fun `remapProjectId - updates room payload projectId`() = runTest {
        val payload = defaultRoomPayload(projectId = -100L)
        val op = makeRoomOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("property") } returns emptyList()
        coEvery { localDataService.getPendingOperationsForEntityType("location") } returns emptyList()
        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns listOf(op)

        val result = service.remapProjectId(localProjectId = -100L, serverId = 3000L)

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingRoomCreationPayload::class.java
        )
        assertThat(updatedPayload.projectId).isEqualTo(3000L)
        // Other fields should remain unchanged
        assertThat(updatedPayload.localRoomId).isEqualTo(payload.localRoomId)
        assertThat(updatedPayload.roomName).isEqualTo(payload.roomName)
        assertThat(updatedPayload.levelServerId).isEqualTo(payload.levelServerId)
        assertThat(updatedPayload.locationServerId).isEqualTo(payload.locationServerId)
    }

    @Test
    fun `remapProjectId - skips operations with non-matching projectId`() = runTest {
        val matchingPayload = defaultPropertyPayload(
            localPropertyId = 1L, propertyUuid = "uuid-1", projectId = -100L
        )
        val nonMatchingPayload = defaultPropertyPayload(
            localPropertyId = 2L, propertyUuid = "uuid-2", projectId = -999L
        )
        val matchingOp = makePropertyOp(operationId = "op-1", payload = matchingPayload)
        val nonMatchingOp = makePropertyOp(operationId = "op-2", payload = nonMatchingPayload)

        coEvery { localDataService.getPendingOperationsForEntityType("property") } returns
                listOf(matchingOp, nonMatchingOp)
        coEvery { localDataService.getPendingOperationsForEntityType("location") } returns emptyList()
        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns emptyList()

        val result = service.remapProjectId(localProjectId = -100L, serverId = 1000L)

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        // Only the matching operation should have been updated
        assertThat(capturedOp.captured.operationId).isEqualTo("op-1")
        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingPropertyCreationPayload::class.java
        )
        assertThat(updatedPayload.projectId).isEqualTo(1000L)
    }

    @Test
    fun `remapProjectId - updates all entity types and returns total count`() = runTest {
        val propPayload = defaultPropertyPayload(projectId = -50L)
        val locPayload = defaultLocationPayload(projectId = -50L)
        val roomPayload = defaultRoomPayload(projectId = -50L)

        coEvery { localDataService.getPendingOperationsForEntityType("property") } returns
                listOf(makePropertyOp(operationId = "op-p1", payload = propPayload))
        coEvery { localDataService.getPendingOperationsForEntityType("location") } returns
                listOf(makeLocationOp(operationId = "op-l1", payload = locPayload))
        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns
                listOf(makeRoomOp(operationId = "op-r1", payload = roomPayload))

        val result = service.remapProjectId(localProjectId = -50L, serverId = 500L)

        assertThat(result).isEqualTo(3)
        coVerify(exactly = 3) { localDataService.enqueueSyncOperation(any()) }
    }

    // ========================================================================
    // remapPropertyId tests
    // ========================================================================

    @Test
    fun `remapPropertyId - updates location payload propertyLocalId`() = runTest {
        val payload = defaultLocationPayload(propertyLocalId = -200L)
        val op = makeLocationOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("location") } returns listOf(op)

        val result = service.remapPropertyId(localPropertyId = -200L, serverId = 5000L)

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingLocationCreationPayload::class.java
        )
        assertThat(updatedPayload.propertyLocalId).isEqualTo(5000L)
        // Other fields should remain unchanged
        assertThat(updatedPayload.projectId).isEqualTo(payload.projectId)
        assertThat(updatedPayload.locationName).isEqualTo(payload.locationName)
        assertThat(updatedPayload.localLocationId).isEqualTo(payload.localLocationId)
    }

    @Test
    fun `remapPropertyId - skips non-matching operations`() = runTest {
        val matchingPayload = defaultLocationPayload(
            localLocationId = 10L, locationUuid = "loc-1", propertyLocalId = -200L
        )
        val nonMatchingPayload = defaultLocationPayload(
            localLocationId = 11L, locationUuid = "loc-2", propertyLocalId = -777L
        )

        coEvery { localDataService.getPendingOperationsForEntityType("location") } returns listOf(
            makeLocationOp(operationId = "op-1", payload = matchingPayload),
            makeLocationOp(operationId = "op-2", payload = nonMatchingPayload)
        )

        val result = service.remapPropertyId(localPropertyId = -200L, serverId = 5000L)

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }
        assertThat(capturedOp.captured.operationId).isEqualTo("op-1")
    }

    @Test
    fun `remapPropertyId - same IDs returns 0 and does no work`() = runTest {
        val result = service.remapPropertyId(localPropertyId = 42L, serverId = 42L)

        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) { localDataService.getPendingOperationsForEntityType(any()) }
    }

    // ========================================================================
    // remapLocationId tests
    // ========================================================================

    @Test
    fun `remapLocationId - updates room payload levelServerId and locationServerId`() = runTest {
        val payload = defaultRoomPayload(
            levelServerId = -300L,
            locationServerId = -300L,
            levelUuid = "level-uuid-1",
            locationUuid = "location-uuid-1"
        )
        val op = makeRoomOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns listOf(op)

        val result = service.remapLocationId(
            localLocationId = -300L,
            serverId = 7000L,
            uuid = "some-other-uuid"
        )

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingRoomCreationPayload::class.java
        )
        assertThat(updatedPayload.levelServerId).isEqualTo(7000L)
        assertThat(updatedPayload.locationServerId).isEqualTo(7000L)
        // Other fields should remain unchanged
        assertThat(updatedPayload.projectId).isEqualTo(payload.projectId)
        assertThat(updatedPayload.roomName).isEqualTo(payload.roomName)
    }

    @Test
    fun `remapLocationId - matches by UUID fallback when IDs do not match`() = runTest {
        val payload = defaultRoomPayload(
            levelServerId = -999L,       // Does NOT match localLocationId
            locationServerId = -888L,    // Does NOT match localLocationId
            levelUuid = "target-uuid",
            locationUuid = "target-uuid"
        )
        val op = makeRoomOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns listOf(op)

        val result = service.remapLocationId(
            localLocationId = -300L,     // Does NOT match the server IDs in payload
            serverId = 7000L,
            uuid = "target-uuid"         // Matches both levelUuid and locationUuid
        )

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingRoomCreationPayload::class.java
        )
        assertThat(updatedPayload.levelServerId).isEqualTo(7000L)
        assertThat(updatedPayload.locationServerId).isEqualTo(7000L)
    }

    @Test
    fun `remapLocationId - updates only levelServerId when only level matches`() = runTest {
        val payload = defaultRoomPayload(
            levelServerId = -300L,
            locationServerId = -500L,    // Different ID, does not match
            levelUuid = "level-uuid",
            locationUuid = "other-uuid"  // Different UUID, does not match
        )
        val op = makeRoomOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns listOf(op)

        val result = service.remapLocationId(
            localLocationId = -300L,
            serverId = 7000L,
            uuid = "level-uuid"
        )

        assertThat(result).isEqualTo(1)

        val capturedOp = slot<OfflineSyncQueueEntity>()
        coVerify(exactly = 1) { localDataService.enqueueSyncOperation(capture(capturedOp)) }

        val updatedPayload = gson.fromJson(
            String(capturedOp.captured.payload, Charsets.UTF_8),
            PendingRoomCreationPayload::class.java
        )
        assertThat(updatedPayload.levelServerId).isEqualTo(7000L)
        assertThat(updatedPayload.locationServerId).isEqualTo(-500L) // Unchanged
    }

    @Test
    fun `remapLocationId - same IDs returns 0 and does no work`() = runTest {
        val result = service.remapLocationId(localLocationId = 42L, serverId = 42L, uuid = "uuid")

        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) { localDataService.getPendingOperationsForEntityType(any()) }
    }

    @Test
    fun `remapLocationId - skips room ops that do not match by ID or UUID`() = runTest {
        val payload = defaultRoomPayload(
            levelServerId = -999L,
            locationServerId = -888L,
            levelUuid = "no-match-level",
            locationUuid = "no-match-location"
        )
        val op = makeRoomOp(payload = payload)

        coEvery { localDataService.getPendingOperationsForEntityType("room") } returns listOf(op)

        val result = service.remapLocationId(
            localLocationId = -300L,
            serverId = 7000L,
            uuid = "target-uuid"
        )

        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) { localDataService.enqueueSyncOperation(any()) }
    }

    // ========================================================================
    // remapRoomId tests
    // ========================================================================

    @Test
    fun `remapRoomId - calls all migrate methods and returns sum`() = runTest {
        coEvery { localDataService.migrateNoteRoomIds(-10L, 500L) } returns 2
        coEvery { localDataService.migrateEquipmentRoomIds(-10L, 500L) } returns 3
        coEvery { localDataService.migrateMoistureLogRoomIds(-10L, 500L) } returns 1
        coEvery { localDataService.migrateAtmosphericLogRoomIds(-10L, 500L) } returns 0
        coEvery { localDataService.migratePhotoRoomIds(-10L, 500L) } returns 4
        coEvery { localDataService.migrateAlbumRoomIds(-10L, 500L) } returns 1
        coEvery { localDataService.migrateDamageRoomIds(-10L, 500L) } returns 2
        coEvery { localDataService.migrateWorkScopeRoomIds(-10L, 500L) } returns 1

        val result = service.remapRoomId(localRoomId = -10L, serverId = 500L, uuid = "room-uuid")

        assertThat(result).isEqualTo(14)

        coVerify(exactly = 1) { localDataService.migrateNoteRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateEquipmentRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateMoistureLogRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateAtmosphericLogRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migratePhotoRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateAlbumRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateDamageRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateWorkScopeRoomIds(-10L, 500L) }
    }

    @Test
    fun `remapRoomId - same IDs returns 0 and does no work`() = runTest {
        val result = service.remapRoomId(localRoomId = 42L, serverId = 42L, uuid = "uuid")

        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) { localDataService.migrateNoteRoomIds(any(), any()) }
        coVerify(exactly = 0) { localDataService.migrateEquipmentRoomIds(any(), any()) }
        coVerify(exactly = 0) { localDataService.migrateMoistureLogRoomIds(any(), any()) }
        coVerify(exactly = 0) { localDataService.migrateAtmosphericLogRoomIds(any(), any()) }
        coVerify(exactly = 0) { localDataService.migratePhotoRoomIds(any(), any()) }
        coVerify(exactly = 0) { localDataService.migrateAlbumRoomIds(any(), any()) }
        coVerify(exactly = 0) { localDataService.migrateDamageRoomIds(any(), any()) }
        coVerify(exactly = 0) { localDataService.migrateWorkScopeRoomIds(any(), any()) }
    }

    @Test
    fun `remapRoomId - returns 0 when all migrate methods return 0`() = runTest {
        // relaxed mockk already returns 0 for all Int-returning functions
        val result = service.remapRoomId(localRoomId = -10L, serverId = 500L, uuid = "room-uuid")

        assertThat(result).isEqualTo(0)

        // All migrate methods should still be called
        coVerify(exactly = 1) { localDataService.migrateNoteRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateEquipmentRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateMoistureLogRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateAtmosphericLogRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migratePhotoRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateAlbumRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateDamageRoomIds(-10L, 500L) }
        coVerify(exactly = 1) { localDataService.migrateWorkScopeRoomIds(-10L, 500L) }
    }
}
