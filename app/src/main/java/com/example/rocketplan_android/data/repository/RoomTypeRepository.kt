package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineCatalogLevelEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogPropertyTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogRoomTypeEntity
import com.example.rocketplan_android.data.model.offline.OfflineLevelDto
import com.example.rocketplan_android.data.model.offline.OfflinePropertyTypeDto
import com.example.rocketplan_android.data.model.offline.OfflineRoomTypeCatalogItemDto
import com.example.rocketplan_android.data.model.offline.OfflineRoomTypeCatalogResponse
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import com.example.rocketplan_android.data.storage.OfflineRoomTypeCatalogStore
import com.google.gson.GsonBuilder
import java.util.Date
import java.util.Locale

class RoomTypeRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val offlineRoomTypeCatalogStore: OfflineRoomTypeCatalogStore
) {

    enum class RequestType {
        INTERIOR,
        EXTERIOR
    }

    class MissingPropertyException : IllegalStateException("Project property not available.")
    class UnsyncedPropertyException : IllegalStateException("Property has not been synced yet.")

    private val logGson = GsonBuilder().setPrettyPrinting().create()

    suspend fun getCachedRoomTypes(
        projectId: Long,
        requestType: RequestType
    ): Result<List<RoomTypeDto>> = runCatching {
        val context = resolveProjectContext(projectId)
        val cached = localDataService.getOfflineCatalogRoomTypes()
        if (cached.isNotEmpty()) {
            filterOfflineRoomTypes(cached, requestType, context.propertyTypeId)
        } else {
            val stored = offlineRoomTypeCatalogStore.read()
            if (stored != null) {
                Log.d(
                    "RoomTypeRepository",
                    "ℹ️ Using cached offline room type catalog (version=${stored.version ?: "unknown"})"
                )
                persistOfflineCatalog(stored.catalog)
                val refreshed = localDataService.getOfflineCatalogRoomTypes()
                if (refreshed.isNotEmpty()) {
                    filterOfflineRoomTypes(refreshed, requestType, context.propertyTypeId)
                } else {
                    filterOfflineRoomTypes(stored.catalog.toRoomTypeEntities(Date()), requestType, context.propertyTypeId)
                }
            } else {
                emptyList()
            }
        }
    }

    suspend fun getRoomTypes(
        projectId: Long,
        requestType: RequestType,
        forceRefresh: Boolean = false
    ): Result<List<RoomTypeDto>> = runCatching {
        val context = resolveProjectContext(projectId)
        getOfflineRoomTypes(requestType, context.propertyTypeId, forceRefresh)
    }

    suspend fun prefetchOfflineCatalog(forceRefresh: Boolean = false): Result<Unit> = runCatching {
        val cached = localDataService.getOfflineCatalogRoomTypes()
        if (cached.isNotEmpty() && !forceRefresh) return@runCatching

        val response = api.getOfflineRoomTypes()
        logOfflineCatalog(response)
        offlineRoomTypeCatalogStore.write(response)
        persistOfflineCatalog(response)
        Unit
    }

    private suspend fun getOfflineRoomTypes(
        requestType: RequestType,
        propertyTypeId: Long?,
        forceRefresh: Boolean
    ): List<RoomTypeDto> {
        val cached = localDataService.getOfflineCatalogRoomTypes()
        if (cached.isNotEmpty() && !forceRefresh) {
            return filterOfflineRoomTypes(cached, requestType, propertyTypeId)
        }

        return runCatching {
            val response = api.getOfflineRoomTypes()
            logOfflineCatalog(response)
            offlineRoomTypeCatalogStore.write(response)
            Log.d(
                "RoomTypeRepository",
                "✅ Fetched offline room type catalog (version=${response.version ?: "unknown"})"
            )
            persistOfflineCatalog(response)
            filterOfflineRoomTypes(response.toRoomTypeEntities(Date()), requestType, propertyTypeId)
        }.getOrElse { error ->
            Log.w("RoomTypeRepository", "⚠️ Failed to fetch offline room type catalog", error)
            if (cached.isNotEmpty()) {
                filterOfflineRoomTypes(cached, requestType, propertyTypeId)
            } else {
                val stored = offlineRoomTypeCatalogStore.read()
                if (stored != null) {
                    persistOfflineCatalog(stored.catalog)
                    val refreshed = localDataService.getOfflineCatalogRoomTypes()
                    if (refreshed.isNotEmpty()) {
                        filterOfflineRoomTypes(refreshed, requestType, propertyTypeId)
                    } else {
                        filterOfflineRoomTypes(stored.catalog.toRoomTypeEntities(Date()), requestType, propertyTypeId)
                    }
                } else {
                    throw error
                }
            }
        }
    }

    private suspend fun persistOfflineCatalog(response: OfflineRoomTypeCatalogResponse) {
        val timestamp = Date()
        val propertyTypes = response.propertyTypes.map { it.toEntity(timestamp) }
        val levels = response.levels.map { it.toEntity(timestamp) }
        val roomTypes = response.roomTypes.map { it.toEntity(timestamp) }
        localDataService.replaceOfflineRoomTypeCatalog(propertyTypes, levels, roomTypes)
    }

    suspend fun ensureOfflineCatalogCached() {
        val hasPropertyTypes = localDataService.getOfflineCatalogPropertyTypes().isNotEmpty()
        val hasLevels = localDataService.getOfflineCatalogLevels().isNotEmpty()
        val hasRoomTypes = localDataService.getOfflineCatalogRoomTypes().isNotEmpty()
        if (hasPropertyTypes && hasLevels && hasRoomTypes) return
        val stored = offlineRoomTypeCatalogStore.read() ?: return
        persistOfflineCatalog(stored.catalog)
    }

    private fun OfflinePropertyTypeDto.toEntity(timestamp: Date): OfflineCatalogPropertyTypeEntity =
        OfflineCatalogPropertyTypeEntity(
            propertyTypeId = id,
            name = name,
            sortOrder = sortOrder,
            updatedAt = updatedAt,
            fetchedAt = timestamp
        )

    private fun OfflineLevelDto.toEntity(timestamp: Date): OfflineCatalogLevelEntity =
        OfflineCatalogLevelEntity(
            levelId = id,
            name = name,
            type = type,
            isDefault = isDefault,
            isStandard = isStandard,
            propertyTypeIds = propertyTypeIds,
            updatedAt = updatedAt,
            fetchedAt = timestamp
        )

    private fun OfflineRoomTypeCatalogItemDto.toEntity(timestamp: Date): OfflineCatalogRoomTypeEntity =
        OfflineCatalogRoomTypeEntity(
            roomTypeId = id,
            name = name,
            type = type,
            isStandard = isStandard,
            isDefault = isDefault,
            levelIds = levelIds,
            propertyTypeIds = propertyTypeIds,
            updatedAt = updatedAt,
            fetchedAt = timestamp
        )

    private fun OfflineRoomTypeCatalogResponse.toRoomTypeEntities(
        timestamp: Date
    ): List<OfflineCatalogRoomTypeEntity> = roomTypes.map { it.toEntity(timestamp) }

    private suspend fun resolveProjectContext(projectId: Long): ProjectContext {
        val project = localDataService.getProject(projectId)
            ?: throw MissingPropertyException()
        val propertyLocalId = project.propertyId
            ?: throw MissingPropertyException()
        localDataService.getProperty(propertyLocalId)
            ?: throw MissingPropertyException()
        val propertyTypeId = resolveCatalogPropertyTypeId(project.propertyType)
        return ProjectContext(propertyTypeId)
    }

    /**
     * Resolves a property type string (e.g., "single_unit", "multi-unit") to its catalog ID.
     * Used by OfflineSyncRepository for room/location creation.
     */
    suspend fun resolveCatalogPropertyTypeId(propertyTypeValue: String?): Long? {
        val normalized = normalizePropertyType(propertyTypeValue) ?: return null
        val catalogTypes = localDataService.getOfflineCatalogPropertyTypes()
        val match = catalogTypes.firstOrNull { normalizePropertyType(it.name) == normalized }
        return match?.propertyTypeId ?: fallbackPropertyTypeId(normalized)
    }

    companion object {
        /**
         * Normalizes property type strings for comparison.
         * Converts to lowercase, replaces non-alphanumeric chars with underscores.
         */
        fun normalizePropertyType(value: String?): String? =
            value
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace("[^a-z0-9]+".toRegex(), "_")
                ?.trim('_')
                ?.takeIf { it.isNotBlank() }

        /**
         * Fallback property type IDs for offline use when catalog is unavailable.
         */
        fun fallbackPropertyTypeId(normalized: String): Long? =
            when (normalized) {
                "single_unit" -> 1L
                "multi_unit" -> 2L
                "single_location" -> 3L
                "commercial" -> 4L
                "exterior" -> 5L
                else -> null
            }
    }

    private data class ProjectContext(
        val propertyTypeId: Long?
    )

    private fun filterOfflineRoomTypes(
        roomTypes: List<OfflineCatalogRoomTypeEntity>,
        requestType: RequestType,
        propertyTypeId: Long?
    ): List<RoomTypeDto> {
        val desiredType = when (requestType) {
            RequestType.EXTERIOR -> "external"
            RequestType.INTERIOR -> "unit"
        }
        val typeFiltered = roomTypes.filter { roomType ->
            val isExterior = isExteriorRoomType(roomType.type)
            when (requestType) {
                RequestType.EXTERIOR -> isExterior
                RequestType.INTERIOR -> !isExterior
            }
        }
        val filtered = if (propertyTypeId == null) {
            typeFiltered
        } else {
            typeFiltered.filter { it.propertyTypeIds.isEmpty() || it.propertyTypeIds.contains(propertyTypeId) }
        }
        return filtered.map { roomType ->
            RoomTypeDto(
                id = roomType.roomTypeId,
                name = roomType.name,
                type = desiredType,
                isStandard = roomType.isStandard
            )
        }
    }

    private fun isExteriorRoomType(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.US) ?: return false
        return normalized == "external" ||
            normalized == "multi-external" ||
            normalized == "single-external" ||
            normalized == "industrial" ||
            normalized == "exterior"
    }

    private fun logOfflineCatalog(response: OfflineRoomTypeCatalogResponse) {
        if (!AppConfig.isLoggingEnabled) return
        Log.d(
            "RoomTypeRepository",
            "offline-room-types summary: version=${response.version ?: "unknown"}, " +
                "property_types=${response.propertyTypes.size}, " +
                "levels=${response.levels.size}, " +
                "room_types=${response.roomTypes.size}"
        )
        if (!AppConfig.isDevelopment) return
        val json = logGson.toJson(response)
        val maxChunkSize = 3500
        var offset = 0
        while (offset < json.length) {
            val end = minOf(json.length, offset + maxChunkSize)
            Log.d("RoomTypeRepository", "offline-room-types response: ${json.substring(offset, end)}")
            offset = end
        }
    }
}
