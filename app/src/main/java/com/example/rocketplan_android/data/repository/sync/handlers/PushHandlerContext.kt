package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.logging.RemoteLogger
import com.google.gson.Gson
import java.util.Date

/**
 * Shared context for all push handlers.
 * Contains common dependencies needed for syncing local changes to the server.
 */
class PushHandlerContext(
    val api: OfflineSyncApi,
    val localDataService: LocalDataService,
    val gson: Gson,
    val remoteLogger: RemoteLogger?,
    val syncProjectEssentials: suspend (Long) -> SyncResult,
    val persistProperty: suspend (
        projectId: Long,
        property: PropertyDto,
        propertyTypeValue: String?,
        existing: OfflinePropertyEntity?,
        forcePropertyIdUpdate: Boolean
    ) -> OfflinePropertyEntity,
    val imageProcessorQueueManagerProvider: () -> ImageProcessorQueueManager?
) {
    fun now(): Date = Date()
}
