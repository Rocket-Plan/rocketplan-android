package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineTimecardEntity
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import com.example.rocketplan_android.util.UuidUtils

/**
 * Handles timecard CRUD operations and queues sync work via SyncQueueProcessor.
 */
class TimecardSyncService(
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val logLocalDeletion: (String, Long, String?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    /**
     * Clock in: Create a new timecard with the current time as timeIn.
     */
    suspend fun clockIn(
        projectId: Long,
        userId: Long,
        companyId: Long,
        timecardTypeId: Int = 1,
        timecardTypeName: String = "Standard",
        notes: String? = null
    ): OfflineTimecardEntity = withContext(ioDispatcher) {
        val timestamp = now()
        val uuid = UuidUtils.generateUuidV7()

        val entity = OfflineTimecardEntity(
            timecardId = -System.currentTimeMillis(),
            serverId = null,
            uuid = uuid,
            projectId = projectId,
            userId = userId,
            timecardTypeId = timecardTypeId,
            timecardTypeName = timecardTypeName,
            timeIn = timestamp,
            timeOut = null,
            elapsed = null,
            notes = notes,
            companyId = companyId,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false
        )

        localDataService.saveTimecard(entity)
        val saved = localDataService.getTimecardByUuid(uuid) ?: entity
        syncQueueEnqueuer().enqueueTimecardUpsert(saved, null)
        saved
    }

    /**
     * Clock out: Update an existing timecard with the current time as timeOut.
     */
    suspend fun clockOut(
        timecardId: Long,
        notes: String? = null
    ): OfflineTimecardEntity? = withContext(ioDispatcher) {
        val existing = localDataService.getTimecard(timecardId)
            ?: return@withContext null

        val timestamp = now()
        val elapsed = (timestamp.time - existing.timeIn.time) / 1000 // seconds
        val lockUpdatedAt = existing.serverId?.let { (existing.serverUpdatedAt ?: existing.updatedAt).toApiTimestamp() }

        val updated = existing.copy(
            timeOut = timestamp,
            elapsed = elapsed,
            notes = notes ?: existing.notes,
            updatedAt = timestamp,
            isDirty = true,
            syncStatus = SyncStatus.PENDING
        )

        localDataService.saveTimecard(updated)
        syncQueueEnqueuer().enqueueTimecardUpsert(updated, lockUpdatedAt)
        updated
    }

    /**
     * Update an existing timecard (edit times, notes, type).
     */
    suspend fun updateTimecard(
        timecardId: Long,
        timeIn: Date? = null,
        timeOut: Date? = null,
        timecardTypeId: Int? = null,
        timecardTypeName: String? = null,
        notes: String? = null
    ): OfflineTimecardEntity? = withContext(ioDispatcher) {
        val existing = localDataService.getTimecard(timecardId)
            ?: return@withContext null

        val timestamp = now()
        val lockUpdatedAt = existing.serverId?.let { (existing.serverUpdatedAt ?: existing.updatedAt).toApiTimestamp() }

        val newTimeIn = timeIn ?: existing.timeIn
        val newTimeOut = timeOut ?: existing.timeOut
        val elapsed = newTimeOut?.let { (it.time - newTimeIn.time) / 1000 }

        val updated = existing.copy(
            timeIn = newTimeIn,
            timeOut = newTimeOut,
            elapsed = elapsed,
            timecardTypeId = timecardTypeId ?: existing.timecardTypeId,
            timecardTypeName = timecardTypeName ?: existing.timecardTypeName,
            notes = notes ?: existing.notes,
            updatedAt = timestamp,
            isDirty = true,
            syncStatus = SyncStatus.PENDING
        )

        localDataService.saveTimecard(updated)
        syncQueueEnqueuer().enqueueTimecardUpsert(updated, lockUpdatedAt)
        updated
    }

    /**
     * Delete a timecard.
     */
    suspend fun deleteTimecard(
        timecardId: Long? = null,
        uuid: String? = null
    ): OfflineTimecardEntity? = withContext(ioDispatcher) {
        val existing = when {
            timecardId != null -> localDataService.getTimecard(timecardId)
            uuid != null -> localDataService.getTimecardByUuid(uuid)
            else -> null
        } ?: return@withContext null

        // Record tombstone BEFORE marking as deleted to prevent resurrection during sync
        existing.serverId?.let { DeletionTombstoneCache.recordDeletion("timecard", it) }

        val lockUpdatedAt = existing.serverId?.let { (existing.serverUpdatedAt ?: existing.updatedAt).toApiTimestamp() }
        val timestamp = now()
        val updated = existing.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = timestamp
        )
        localDataService.saveTimecard(updated)

        if (existing.serverId == null) {
            // Never synced to server, clean up locally
            localDataService.removeSyncOperationsForEntity(entityType = "timecard", entityId = existing.timecardId)
            logLocalDeletion("timecard", existing.timecardId, existing.uuid)
            val cleaned = updated.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
            localDataService.saveTimecard(cleaned)
            return@withContext cleaned
        }

        syncQueueEnqueuer().enqueueTimecardDeletion(updated, lockUpdatedAt)
        updated
    }
}
