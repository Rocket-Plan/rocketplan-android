package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles moisture log CRUD operations and queues sync work via SyncQueueProcessor.
 */
class MoistureLogSyncService(
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun upsertMoistureLogOffline(
        log: OfflineMoistureLogEntity
    ): OfflineMoistureLogEntity = withContext(ioDispatcher) {
        val existing = localDataService.getMoistureLogByUuid(log.uuid)
        val lockUpdatedAt = existing?.serverId?.let { existing.updatedAt.toApiTimestamp() }
        localDataService.saveMoistureLogs(listOf(log))
        val saved = localDataService.getMoistureLogByUuid(log.uuid) ?: log
        syncQueueEnqueuer().enqueueMoistureLogUpsert(saved, lockUpdatedAt)
        saved
    }
}
