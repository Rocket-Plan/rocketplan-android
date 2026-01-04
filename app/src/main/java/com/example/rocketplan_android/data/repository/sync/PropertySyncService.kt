package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.repository.RoomTypeRepository.RequestType
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.Date
import com.example.rocketplan_android.util.UuidUtils

/**
 * Service responsible for property CRUD and persistence logic for projects.
 * Extracted from OfflineSyncRepository to keep property logic cohesive.
 */
class PropertySyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val roomTypeRepository: RoomTypeRepository,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val isNetworkAvailable: () -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val propertyIncludes = "propertyType,asbestosStatus,propertyDamageTypes,damageCause"

    private fun now() = Date()

    suspend fun createProjectProperty(
        projectId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?,
        idempotencyKey: String? = null
    ): Result<OfflinePropertyEntity> = withContext(ioDispatcher) {
        runCatching {
            val resolvedIdempotencyKey = idempotencyKey ?: request.idempotencyKey ?: UuidUtils.generateUuidV7()
            val project = localDataService.getAllProjects().firstOrNull { it.projectId == projectId }
                ?: throw Exception("Project not found locally")
            val projectServerId = project.serverId
            val isOnline = isNetworkAvailable()

            // Create pending property if project not synced OR offline
            if (projectServerId == null || !isOnline) {
                Log.d(TAG, "[createProjectProperty] Creating pending property " +
                    "(projectId=$projectId, serverId=$projectServerId, online=$isOnline)")
                return@runCatching createPendingProperty(
                    project = project,
                    propertyTypeValue = propertyTypeValue,
                    propertyTypeId = request.propertyTypeId,
                    idempotencyKey = resolvedIdempotencyKey
                )
            }
            val requestWithKey = request.copy(idempotencyKey = resolvedIdempotencyKey)

            if (AppConfig.isLoggingEnabled) {
                Log.d(
                    TAG,
                    "[createProjectProperty] createProperty payload: " +
                        "projectId=$projectId serverId=$projectServerId propertyTypeId=${request.propertyTypeId} " +
                        "propertyTypeValue=${propertyTypeValue ?: "null"} idempotencyKey=$resolvedIdempotencyKey"
                )
            }

            val created = try {
                api.createProjectProperty(projectServerId, requestWithKey).data
            } catch (error: HttpException) {
                if (AppConfig.isLoggingEnabled) {
                    val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                    Log.w(
                        TAG,
                        "[createProjectProperty] createProperty failed: code=${error.code()} " +
                            "body=${errorBody ?: "null"}"
                    )
                }
                throw error
            } catch (error: IOException) {
                // Network failure - fall back to pending property for offline resilience
                Log.w(TAG, "[createProjectProperty] Network error, falling back to pending property", error)
                return@runCatching createPendingProperty(
                    project = project,
                    propertyTypeValue = propertyTypeValue,
                    propertyTypeId = request.propertyTypeId,
                    idempotencyKey = resolvedIdempotencyKey
                )
            }

            val refreshed = runCatching {
                api.getProjectProperties(projectServerId, include = propertyIncludes).data
            }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "[createProjectProperty] getProjectProperties failed for project=$projectServerId",
                        error
                    )
                }
                .getOrNull()
                ?.firstOrNull { it.id == created.id }

            val resolved = refreshed ?: created
            if (AppConfig.isLoggingEnabled) {
                val source = if (refreshed != null) "projectProperties" else "createResponse"
                Log.d(
                    TAG,
                    "[createProjectProperty] property resolved from $source: id=${resolved.id} " +
                        "address=${resolved.address} city=${resolved.city} state=${resolved.state} zip=${resolved.postalCode} " +
                        "propertyTypeId=${resolved.propertyTypeId} propertyType=${resolved.propertyType}"
                )
            }

            val existing = project.propertyId?.let { localDataService.getProperty(it) }
            persistProperty(
                projectId = projectId,
                property = resolved,
                propertyTypeValue = propertyTypeValue,
                existing = existing,
                forceRoomTypeRefresh = true
            )
        }
    }

    suspend fun updateProjectProperty(
        projectId: Long,
        propertyId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?
    ): Result<OfflinePropertyEntity> = withContext(ioDispatcher) {
        val property = localDataService.getProperty(propertyId)
        if (property == null) {
            Log.d(TAG, "[updateProjectProperty] Property $propertyId not found locally, creating new property")
            return@withContext createProjectProperty(projectId, request, propertyTypeValue)
        }
        runCatching {
            val lockUpdatedAt = property.updatedAt.toApiTimestamp()
            val updated = property.copy(
                updatedAt = now(),
                syncStatus = SyncStatus.PENDING,
                syncVersion = property.syncVersion + 1
            )
            localDataService.saveProperty(updated)
            localDataService.attachPropertyToProject(
                projectId = projectId,
                propertyId = updated.propertyId,
                propertyType = propertyTypeValue
            )
            syncQueueEnqueuer().enqueuePropertyUpdate(
                property = updated,
                projectId = projectId,
                request = request.copy(updatedAt = null, idempotencyKey = null),
                propertyTypeValue = propertyTypeValue,
                lockUpdatedAt = lockUpdatedAt
            )
            updated
        }
    }

    suspend fun persistProperty(
        projectId: Long,
        property: PropertyDto,
        propertyTypeValue: String?,
        existing: OfflinePropertyEntity? = null,
        projectAddress: ProjectAddressDto? = null,
        forceRoomTypeRefresh: Boolean = true,
        forcePropertyIdUpdate: Boolean = false
    ): OfflinePropertyEntity {
        val needsFallback = property.address.isNullOrBlank() || property.city.isNullOrBlank()
        val serverProjectId = localDataService.getProject(projectId)?.serverId ?: projectId.takeIf { it > 0 }
        if (AppConfig.isLoggingEnabled) {
            Log.d(
                TAG,
                "[persistProperty] needsFallback=$needsFallback projectId=$projectId serverProjectId=${serverProjectId ?: "null"} " +
                    "propertyId=${property.id}"
            )
        }
        val fallbackAddress = if (needsFallback) {
            projectAddress?.also {
                if (AppConfig.isLoggingEnabled) {
                    Log.d(TAG, "[persistProperty] Using provided projectAddress for projectId=$projectId")
                }
            } ?: serverProjectId?.let { serverId ->
                val fetched = runCatching { api.getProjectDetail(serverId).data.address }
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "[persistProperty] Failed to fetch project detail for fallback (projectId=$projectId serverId=$serverId)",
                            error
                        )
                    }
                    .getOrNull()
                if (AppConfig.isLoggingEnabled) {
                    Log.d(
                        TAG,
                        "[persistProperty] Project detail fallback ${if (fetched != null) "resolved" else "missing"} " +
                            "for projectId=$projectId serverId=$serverId"
                    )
                }
                fetched
            } ?: run {
                if (AppConfig.isLoggingEnabled) {
                    Log.d(
                        TAG,
                        "[persistProperty] No projectAddress and no serverId; skipping fallback for projectId=$projectId"
                    )
                }
                null
            }
        } else null
        val entity = property.toEntity(existing = existing, projectAddress = fallbackAddress)
        Log.d(TAG, "🔍 [persistProperty] Saving property: propertyId=${entity.propertyId} serverId=${entity.serverId} (existing.propertyId=${existing?.propertyId})")
        // Delete pending property first to avoid uuid unique constraint violation
        if (existing != null && existing.propertyId < 0 && entity.propertyId > 0) {
            Log.d(TAG, "🔍 [persistProperty] Deleting pending property ${existing.propertyId} before saving synced ${entity.propertyId}")
            localDataService.deleteProperty(existing.propertyId)
        }
        localDataService.saveProperty(entity)
        // Verify save
        val verify = localDataService.getProperty(entity.propertyId)
        Log.d(TAG, "🔍 [persistProperty] Verify after save: getProperty(${entity.propertyId}) = ${verify?.propertyId}")
        // Force update if we're upgrading from a pending property (negative ID) to a server property (positive ID)
        val isPendingToServerUpgrade = existing?.propertyId != null && existing.propertyId < 0 && property.id > 0
        localDataService.attachPropertyToProject(
            projectId = projectId,
            propertyId = entity.propertyId,
            propertyType = propertyTypeValue,
            forceUpdate = forcePropertyIdUpdate || isPendingToServerUpgrade
        )
        runCatching { primeRoomTypeCaches(projectId, forceRefresh = forceRoomTypeRefresh) }
            .onFailure { Log.w(TAG, "[persistProperty] Unable to prefetch room types for project $projectId", it) }
        return entity
    }

    suspend fun fetchProjectProperty(
        projectId: Long,
        projectDetail: ProjectDetailDto? = null
    ): PropertyDto? {
        val result = runCatching { api.getProjectProperties(projectId, include = propertyIncludes) }
        result.onFailure { error ->
            Log.e(TAG, "[fetchProjectProperty] API call failed for project $projectId", error)
        }
        val response = result.getOrNull()
        Log.d(TAG, "[fetchProjectProperty] Response for project $projectId: ${response?.data?.size ?: 0} properties returned")
        val property = response?.data?.firstOrNull()
        if (property != null) {
            Log.d(
                TAG,
                "[fetchProjectProperty] PropertyDto for project $projectId: id=${property.id}, " +
                    "propertyTypeId=${property.propertyTypeId}, propertyType=${property.propertyType}, " +
                    "address=${property.address}, city=${property.city}, state=${property.state}, zip=${property.postalCode}"
            )
            return property
        }

        Log.d(TAG, "[fetchProjectProperty] No property in response for project $projectId")
        val detail = projectDetail ?: runCatching { api.getProjectDetail(projectId).data }
            .onFailure { Log.w(TAG, "[fetchProjectProperty] Unable to load project detail for fallback (project $projectId)", it) }
            .getOrNull()
        val detailPropertyId = detail?.propertyId ?: detail?.properties?.firstOrNull()?.id

        if (detailPropertyId != null) {
            val fetchedById = runCatching { api.getProperty(detailPropertyId).data }
                .onSuccess { fetched ->
                    Log.d(
                        TAG,
                        "[fetchProjectProperty] Fallback getProperty succeeded for project $projectId " +
                            "(id=$detailPropertyId, address=${fetched.address}, city=${fetched.city}, state=${fetched.state}, zip=${fetched.postalCode})"
                    )
                }
                .onFailure {
                    Log.e(
                        TAG,
                        "[fetchProjectProperty] Fallback getProperty failed for project $projectId (id=$detailPropertyId)",
                        it
                    )
                }
                .getOrNull()
            if (fetchedById != null) {
                return fetchedById
            }
        }

        val embedded = detail?.properties?.firstOrNull()
        if (embedded != null) {
            Log.d(
                TAG,
                "[fetchProjectProperty] Using embedded property from project detail for project $projectId: " +
                    "id=${embedded.id}, address=${embedded.address}, city=${embedded.city}, state=${embedded.state}, zip=${embedded.postalCode}"
            )
            return embedded
        }

        Log.d(TAG, "[fetchProjectProperty] No property found for project $projectId after fallback attempts")
        return null
    }

    private suspend fun createPendingProperty(
        project: OfflineProjectEntity,
        propertyTypeValue: String?,
        propertyTypeId: Int,
        idempotencyKey: String
    ): OfflinePropertyEntity {
        val timestamp = now()
        val localId = -System.currentTimeMillis()
        val resolvedAddress = listOfNotNull(
            project.addressLine1?.takeIf { it.isNotBlank() },
            project.title.takeIf { it.isNotBlank() },
            "Pending property"
        ).first()
        val pending = OfflinePropertyEntity(
            propertyId = localId,
            serverId = null,
            uuid = UuidUtils.generateUuidV7(),
            address = resolvedAddress,
            city = null,
            state = null,
            zipCode = null,
            latitude = null,
            longitude = null,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null
        )
        localDataService.saveProperty(pending)
        localDataService.attachPropertyToProject(
            projectId = project.projectId,
            propertyId = pending.propertyId,
            propertyType = propertyTypeValue
        )
        syncQueueEnqueuer().enqueuePropertyCreation(
            property = pending,
            projectId = project.projectId,
            propertyTypeId = propertyTypeId,
            propertyTypeValue = propertyTypeValue,
            idempotencyKey = idempotencyKey
        )
        return pending
    }

    private suspend fun primeRoomTypeCaches(projectId: Long, forceRefresh: Boolean) {
        RequestType.entries.forEach { requestType ->
            runCatching {
                val types = roomTypeRepository
                    .getRoomTypes(projectId, requestType, forceRefresh = forceRefresh)
                    .getOrThrow()
                Log.d(TAG, "[roomTypes] Prefetched ${types.size} ${requestType.name.lowercase()} room types for project $projectId")
            }.onFailure { error ->
                Log.w(TAG, "[roomTypes] Prefetch failed for project $projectId (${requestType.name})", error)
            }
        }
    }

    companion object {
        private const val TAG = "API"
    }
}
