package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.CreateCompanyProjectRequest
import com.example.rocketplan_android.data.model.DeleteProjectRequest
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.model.UpdateProjectRequest
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.repository.mapper.PendingProjectCreationPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

data class PendingProjectSyncResult(
    val localProjectId: Long,
    val serverProjectId: Long
)

/**
 * Handles pushing project create/update/delete operations to the server.
 */
class ProjectPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleCreate(operation: OfflineSyncQueueEntity): PendingProjectSyncResult? {
        val payload = runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingProjectCreationPayload::class.java
            )
        }.getOrNull() ?: return null

        val existing = ctx.localDataService.getProject(payload.localProjectId)
            ?: return null

        val addressDto = ctx.api.createAddress(payload.addressRequest).data
        val addressId = addressDto.id
            ?: throw IllegalStateException("Address creation succeeded but returned null id")

        val idempotencyKey = payload.idempotencyKey ?: payload.projectUuid
        val projectRequest = CreateCompanyProjectRequest(
            projectStatusId = payload.projectStatusId,
            addressId = addressId,
            idempotencyKey = idempotencyKey
        )

        val dto = ctx.api.createCompanyProject(payload.companyId, projectRequest).data

        Log.d(
            SYNC_TAG,
            "📥 [handlePendingProjectCreation] Server response: " +
                "id=${dto.id} companyId=${dto.companyId} uid=${dto.uid} uuid=${dto.uuid} " +
                "propertyId=${dto.propertyId} createdAt=${dto.createdAt} updatedAt=${dto.updatedAt}"
        )

        // CRITICAL: Validate the server returned a project for the correct company
        if (dto.companyId != null && dto.companyId != payload.companyId) {
            Log.e(
                SYNC_TAG,
                "🚨 [handlePendingProjectCreation] COMPANY MISMATCH! " +
                    "Requested companyId=${payload.companyId} but server returned companyId=${dto.companyId} " +
                    "for project id=${dto.id}. This indicates a server-side bug. " +
                    "Rejecting response to prevent data corruption."
            )
            throw IllegalStateException(
                "Server returned project from wrong company: expected=${payload.companyId}, got=${dto.companyId}"
            )
        }

        // Re-read project to get latest propertyId (may have been attached during API call)
        val freshExisting = ctx.localDataService.getProject(payload.localProjectId) ?: existing
        Log.d(
            SYNC_TAG,
            "🔍 [handlePendingProjectCreation] dto.id=${dto.id} dto.propertyId=${dto.propertyId} " +
                "freshExisting.propertyId=${freshExisting.propertyId}"
        )

        val entity = dto.toEntity(existing = freshExisting).withAddressFallback(
            projectAddress = addressDto,
            addressRequest = payload.addressRequest
        ).copy(
            projectId = freshExisting.projectId,
            uuid = freshExisting.uuid,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = ctx.now()
        )
        Log.d(
            SYNC_TAG,
            "🔍 [handlePendingProjectCreation] entity.propertyId=${entity.propertyId} " +
                "(should be ${freshExisting.propertyId})"
        )

        // Save the initial entity
        ctx.localDataService.saveProjects(listOf(entity))

        // Update alias if needed (separate API call, then persist)
        val pendingAlias = freshExisting.alias?.takeIf { it.isNotBlank() }
        val finalEntity = if (!pendingAlias.isNullOrBlank() && pendingAlias != entity.alias) {
            val updatedDto = ctx.api.updateProject(
                projectId = dto.id,
                body = UpdateProjectRequest(
                    alias = pendingAlias,
                    projectStatusId = null,
                    updatedAt = dto.updatedAt
                )
            ).data
            val updated = updatedDto.toEntity(existing = entity).copy(
                projectId = entity.projectId,
                uuid = entity.uuid,
                syncStatus = SyncStatus.SYNCED,
                isDirty = false,
                lastSyncedAt = ctx.now()
            )
            ctx.localDataService.saveProjects(listOf(updated))
            updated
        } else {
            entity
        }
        return PendingProjectSyncResult(
            localProjectId = finalEntity.projectId,
            serverProjectId = dto.id
        )
    }

    suspend fun handleUpdate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val project = ctx.localDataService.getProject(operation.entityId)
            ?: return OperationOutcome.DROP
        val serverId = project.serverId
            ?: return OperationOutcome.SKIP
        val lockUpdatedAt = (project.serverUpdatedAt ?: project.updatedAt).toApiTimestamp()
        val statusId = ProjectStatus.fromApiValue(project.status)?.backendId
        val request = UpdateProjectRequest(
            alias = project.alias?.takeIf { it.isNotBlank() },
            projectStatusId = statusId,
            updatedAt = lockUpdatedAt
        )

        try {
            val dto = ctx.api.updateProject(serverId, request).data
            val entity = dto.toEntity(existing = project).copy(
                projectId = project.projectId,
                uuid = project.uuid,
                syncStatus = SyncStatus.SYNCED,
                isDirty = false,
                lastSyncedAt = ctx.now()
            )
            ctx.localDataService.saveProjects(listOf(entity))
            return OperationOutcome.SUCCESS
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error.isConflict()) {
                Log.w(SYNC_TAG, "\u26a0\ufe0f [handlePendingProjectUpdate] 409 conflict for project $serverId; fetching fresh and retrying")
                ctx.remoteLogger?.log(
                    LogLevel.WARN,
                    SYNC_TAG,
                    "Project update 409 conflict",
                    mapOf(
                        "projectServerId" to serverId.toString(),
                        "projectUuid" to project.uuid,
                        "usedServerTimestamp" to (project.serverUpdatedAt != null).toString(),
                        "localUpdatedAt" to project.updatedAt.time.toString(),
                        "serverUpdatedAt" to (project.serverUpdatedAt?.time?.toString() ?: "null")
                    )
                )
                // Fetch fresh project data from server
                val freshProject = runCatching {
                    ctx.api.getProjectDetail(serverId).data
                }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrElse { fetchError ->
                        Log.e(SYNC_TAG, "\u274c [handlePendingProjectUpdate] Failed to fetch fresh project $serverId; will retry later", fetchError)
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Project update 409 recovery deferred - fresh fetch failed",
                            mapOf("projectServerId" to serverId.toString(), "error" to (fetchError.message ?: "unknown"))
                        )
                        return OperationOutcome.SKIP
                    }

                // Retry with fresh updatedAt
                val retryRequest = UpdateProjectRequest(
                    alias = project.alias?.takeIf { it.isNotBlank() },
                    projectStatusId = statusId,
                    updatedAt = freshProject.updatedAt
                )
                val retryResult = runCatching { ctx.api.updateProject(serverId, retryRequest) }
                    .onFailure { if (it is CancellationException) throw it }
                retryResult.onFailure { retryError ->
                    if (retryError.isConflict()) {
                        Log.w(
                            SYNC_TAG,
                            "\u26a0\ufe0f [handlePendingProjectUpdate] Retry still got 409; recording conflict for user resolution"
                        )
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Project update double-409 - recording conflict",
                            mapOf("projectServerId" to serverId.toString(), "projectUuid" to project.uuid)
                        )
                        val conflict = OfflineConflictResolutionEntity(
                            conflictId = UuidUtils.generateUuidV7(),
                            entityType = "project",
                            entityId = project.projectId,
                            entityUuid = project.uuid,
                            localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "alias" to project.alias,
                                "status" to project.status
                            )).toByteArray(Charsets.UTF_8),
                            remoteVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "alias" to freshProject.alias,
                                "status" to freshProject.status
                            )).toByteArray(Charsets.UTF_8),
                            conflictType = "UPDATE_CONFLICT",
                            detectedAt = ctx.now(),
                            originalOperationId = operation.operationId
                        )
                        ctx.recordConflict(conflict)
                        return OperationOutcome.CONFLICT_PENDING
                    }
                    throw retryError
                }
                // Retry succeeded - save the result
                val retryDto = retryResult.getOrThrow().data
                val entity = retryDto.toEntity(existing = project).copy(
                    projectId = project.projectId,
                    uuid = project.uuid,
                    syncStatus = SyncStatus.SYNCED,
                    isDirty = false,
                    lastSyncedAt = ctx.now()
                )
                ctx.localDataService.saveProjects(listOf(entity))
                Log.d(SYNC_TAG, "\u2705 [handlePendingProjectUpdate] Retry update succeeded for project $serverId")
                return OperationOutcome.SUCCESS
            }
            throw error
        }
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val project = ctx.localDataService.getProject(operation.entityId)
            ?: return OperationOutcome.DROP
        val serverId = project.serverId
            ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = (project.serverUpdatedAt ?: project.updatedAt).toApiTimestamp()
        val request = DeleteProjectRequest(
            projectId = serverId,
            updatedAt = lockUpdatedAt
        )
        val response = ctx.api.deleteProject(serverId, request)
        if (!response.isSuccessful) {
            if (response.code() == 409) {
                // Conflict - fetch fresh and retry delete with updated timestamp
                Log.w(
                    SYNC_TAG,
                    "⚠️ [handlePendingProjectDeletion] 409 conflict for project $serverId; " +
                        "fetching fresh and retrying"
                )
                ctx.remoteLogger?.log(
                    LogLevel.WARN,
                    SYNC_TAG,
                    "Project delete 409 conflict, retrying",
                    mapOf(
                        "projectServerId" to serverId.toString(),
                        "projectLocalId" to project.projectId.toString()
                    )
                )
                val freshProject = runCatching {
                    ctx.api.getProjectDetail(serverId).data
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.e(
                        SYNC_TAG,
                        "❌ [handlePendingProjectDeletion] Failed to fetch fresh project $serverId; will retry later",
                        error
                    )
                    ctx.remoteLogger?.log(
                        LogLevel.WARN,
                        SYNC_TAG,
                        "Project delete conflict resolution deferred - fetch failed",
                        mapOf(
                            "projectServerId" to serverId.toString(),
                            "error" to (error.message ?: "unknown")
                        )
                    )
                    return OperationOutcome.SKIP
                }

                val retryRequest = DeleteProjectRequest(
                    projectId = serverId,
                    updatedAt = freshProject.updatedAt
                )
                val retryResponse = ctx.api.deleteProject(serverId, retryRequest)
                if (!retryResponse.isSuccessful && retryResponse.code() !in listOf(404, 409, 410)) {
                    throw HttpException(retryResponse)
                }
                if (retryResponse.code() == 409) {
                    Log.w(
                        SYNC_TAG,
                        "⚠️ [handlePendingProjectDeletion] Retry still got 409; " +
                            "restoring project $serverId"
                    )
                    ctx.remoteLogger?.log(
                        LogLevel.WARN,
                        SYNC_TAG,
                        "Project delete 409 conflict persisted, restoring",
                        mapOf("projectServerId" to serverId.toString())
                    )
                    val restored = freshProject.toEntity(existing = project)
                    ctx.localDataService.saveProjects(listOf(restored))
                    return OperationOutcome.DROP
                }
                Log.d(
                    SYNC_TAG,
                    "✅ [handlePendingProjectDeletion] Retry delete succeeded for project $serverId"
                )
            } else if (response.code() !in listOf(404, 410)) {
                throw HttpException(response)
            }
        }
        val cleaned = project.copy(
            isDeleted = true,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveProjects(listOf(cleaned))
        // Clear tombstone now that server confirmed deletion
        DeletionTombstoneCache.clearTombstone("project", serverId)
        return OperationOutcome.SUCCESS
    }

    private fun OfflineProjectEntity.withAddressFallback(
        projectAddress: ProjectAddressDto?,
        addressRequest: CreateAddressRequest?
    ): OfflineProjectEntity {
        val resolvedLine1 = listOfNotNull(
            addressLine1?.takeIf { it.isNotBlank() },
            projectAddress?.address?.takeIf { it.isNotBlank() },
            addressRequest?.address?.takeIf { it.isNotBlank() }
        ).firstOrNull()

        val resolvedLine2 = listOfNotNull(
            addressLine2?.takeIf { it.isNotBlank() },
            projectAddress?.address2?.takeIf { it.isNotBlank() },
            addressRequest?.address2?.takeIf { it.isNotBlank() }
        ).firstOrNull()

        val resolvedPropertyId = propertyId ?: projectAddress?.id

        val resolvedTitle = listOfNotNull(
            resolvedLine1,
            title.takeIf { it.isNotBlank() },
            alias?.takeIf { it.isNotBlank() },
            projectNumber?.takeIf { it.isNotBlank() },
            uid?.takeIf { it.isNotBlank() }
        ).firstOrNull() ?: title

        return copy(
            addressLine1 = resolvedLine1,
            addressLine2 = resolvedLine2,
            propertyId = resolvedPropertyId,
            title = resolvedTitle
        )
    }
}
