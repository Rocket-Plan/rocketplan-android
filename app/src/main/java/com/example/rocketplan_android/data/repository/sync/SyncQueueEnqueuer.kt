package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.PropertyMutationRequest

/**
 * Interface for enqueueing sync operations.
 *
 * This interface decouples sync services from the concrete [SyncQueueProcessor],
 * enabling easier testing and clearer dependency boundaries.
 *
 * Services that need to queue operations for background sync should depend on
 * this interface rather than the processor directly.
 */
interface SyncQueueEnqueuer {

    // ============================================================================
    // Project Operations
    // ============================================================================

    suspend fun enqueueProjectCreation(
        project: OfflineProjectEntity,
        companyId: Long,
        statusId: Int,
        addressRequest: CreateAddressRequest,
        idempotencyKey: String? = null
    )

    suspend fun enqueueProjectUpdate(
        project: OfflineProjectEntity,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueueProjectDeletion(
        project: OfflineProjectEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Property Operations
    // ============================================================================

    suspend fun enqueuePropertyCreation(
        property: OfflinePropertyEntity,
        projectId: Long,
        propertyTypeId: Int,
        propertyTypeValue: String?,
        idempotencyKey: String?
    )

    suspend fun enqueuePropertyUpdate(
        property: OfflinePropertyEntity,
        projectId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueuePropertyDeletion(
        property: OfflinePropertyEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Location Operations
    // ============================================================================

    suspend fun enqueueLocationCreation(
        location: OfflineLocationEntity,
        propertyLocalId: Long,
        locationName: String,
        locationTypeId: Long,
        type: String,
        floorNumber: Int,
        isCommon: Boolean,
        isAccessible: Boolean,
        isCommercial: Boolean,
        idempotencyKey: String?
    )

    suspend fun enqueueLocationUpdate(
        location: OfflineLocationEntity,
        name: String?,
        floorNumber: Int?,
        isAccessible: Boolean?,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueueLocationDeletion(
        location: OfflineLocationEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Room Operations
    // ============================================================================

    suspend fun enqueueRoomCreation(
        room: OfflineRoomEntity,
        roomTypeId: Long,
        roomTypeName: String?,
        isSource: Boolean,
        isExterior: Boolean,
        levelServerId: Long?,
        locationServerId: Long?,
        levelUuid: String,
        locationUuid: String,
        idempotencyKey: String?
    )

    suspend fun enqueueRoomUpdate(
        room: OfflineRoomEntity,
        isSource: Boolean,
        levelId: Long?,
        roomTypeId: Long?,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueueRoomDeletion(
        room: OfflineRoomEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Note Operations
    // ============================================================================

    suspend fun enqueueNoteUpsert(
        note: OfflineNoteEntity,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueueNoteDeletion(
        note: OfflineNoteEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Equipment Operations
    // ============================================================================

    suspend fun enqueueEquipmentUpsert(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueueEquipmentDeletion(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Moisture Log Operations
    // ============================================================================

    suspend fun enqueueMoistureLogUpsert(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueueMoistureLogDeletion(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Photo Operations
    // ============================================================================

    suspend fun enqueuePhotoDeletion(
        photo: OfflinePhotoEntity,
        lockUpdatedAt: String? = null
    )

    // ============================================================================
    // Atmospheric Log Operations
    // ============================================================================

    suspend fun enqueueAtmosphericLogUpsert(
        log: OfflineAtmosphericLogEntity,
        lockUpdatedAt: String? = null
    )

    suspend fun enqueueAtmosphericLogDeletion(
        log: OfflineAtmosphericLogEntity,
        lockUpdatedAt: String? = null
    )
}
