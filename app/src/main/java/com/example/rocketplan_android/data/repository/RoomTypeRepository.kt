package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineRoomTypeEntity
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RoomTypeRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService
) {

    enum class RequestType {
        INTERIOR,
        EXTERIOR
    }

    class MissingPropertyException : IllegalStateException("Project property not available.")
    class UnsyncedPropertyException : IllegalStateException("Property has not been synced yet.")

    private data class RequestContext(val propertyServerId: Long, val filterType: String)

    private val cacheTtlMs = TimeUnit.HOURS.toMillis(12)

    suspend fun getCachedRoomTypes(
        projectId: Long,
        requestType: RequestType
    ): Result<List<RoomTypeDto>> = runCatching {
        val context = resolveRequestContext(projectId, requestType)
        localDataService
            .getRoomTypes(context.propertyServerId, context.filterType)
            .map { it.toDto() }
    }

    suspend fun getRoomTypes(
        projectId: Long,
        requestType: RequestType,
        forceRefresh: Boolean = false
    ): Result<List<RoomTypeDto>> = runCatching {
        val context = resolveRequestContext(projectId, requestType)
        val now = System.currentTimeMillis()
        val cached = localDataService.getRoomTypes(context.propertyServerId, context.filterType)
        val isFresh = cached.isNotEmpty() && cached.all { now - it.fetchedAt.time <= cacheTtlMs }
        if (!forceRefresh && isFresh) {
            return@runCatching cached.map { it.toDto() }
        }

        try {
            val response = api.getPropertyRoomTypes(context.propertyServerId, context.filterType)
            val remote = response.data
            val entities = remote.map { it.toEntity(context.propertyServerId, context.filterType, now) }
            localDataService.replaceRoomTypes(context.propertyServerId, context.filterType, entities)
            remote
        } catch (error: Throwable) {
            if (cached.isNotEmpty()) {
                cached.map { it.toDto() }
            } else {
                throw error
            }
        }
    }

    private suspend fun resolveRequestContext(
        projectId: Long,
        requestType: RequestType
    ): RequestContext {
        val project = localDataService.getProject(projectId)
            ?: throw MissingPropertyException()
        val propertyLocalId = project.propertyId ?: throw MissingPropertyException()
        val property = localDataService.getProperty(propertyLocalId)
            ?: throw MissingPropertyException()
        val propertyServerId = property.serverId ?: throw UnsyncedPropertyException()
        val filter = determineFilter(project.propertyType, requestType)
        return RequestContext(propertyServerId, filter)
    }

    private fun determineFilter(propertyType: String?, requestType: RequestType): String {
        val normalized = propertyType?.lowercase(Locale.US)
        return when (requestType) {
            RequestType.INTERIOR -> when (normalized) {
                "single_location", "single-location" -> "single-location"
                "commercial" -> "industrial"
                else -> "unit"
            }
            RequestType.EXTERIOR -> when (normalized) {
                "multi_unit", "multi-unit" -> "multi-external"
                "exterior" -> "external"
                else -> "single-external"
            }
        }
    }

    private fun OfflineRoomTypeEntity.toDto(): RoomTypeDto =
        RoomTypeDto(
            id = roomTypeId,
            name = name,
            type = type,
            isStandard = isStandard
        )

    private fun RoomTypeDto.toEntity(
        propertyServerId: Long,
        filterType: String,
        timestamp: Long
    ): OfflineRoomTypeEntity =
        OfflineRoomTypeEntity(
            roomTypeId = id,
            propertyServerId = propertyServerId,
            filterType = filterType,
            name = name,
            type = type,
            isStandard = isStandard,
            fetchedAt = Date(timestamp)
        )
}
