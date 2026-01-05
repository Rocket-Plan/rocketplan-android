package com.example.rocketplan_android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.model.ImageProcessorAssemblyWithDetails
import kotlinx.coroutines.flow.Flow

/**
 * Data access layer for persisting image processor assemblies and their photos.
 * Mirrors the behaviour of the iOS Core Data tracker.
 */
@Dao
interface ImageProcessorDao {

    // region Assembly operations
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssembly(assembly: ImageProcessorAssemblyEntity): Long

    @Update
    suspend fun updateAssembly(assembly: ImageProcessorAssemblyEntity)

    @Query("SELECT * FROM image_processor_assemblies WHERE assemblyId = :assemblyId LIMIT 1")
    suspend fun getAssembly(assemblyId: String): ImageProcessorAssemblyEntity?

    @Query("SELECT * FROM image_processor_assemblies WHERE id = :localId LIMIT 1")
    suspend fun getAssemblyByLocalId(localId: Long): ImageProcessorAssemblyEntity?

    @Query(
        """
        SELECT a.* FROM image_processor_assemblies AS a
        LEFT JOIN offline_rooms AS r ON r.roomId = :roomId
        WHERE a.roomId = :roomId
           OR (r.serverId IS NOT NULL AND a.roomId = r.serverId)
        ORDER BY a.createdAt DESC
        """
    )
    fun observeAssembliesByRoom(roomId: Long): Flow<List<ImageProcessorAssemblyEntity>>

    @Query(
        """
        SELECT * FROM image_processor_assemblies
        ORDER BY createdAt DESC
        """
    )
    fun observeAllAssemblies(): Flow<List<ImageProcessorAssemblyEntity>>

    @Query(
        """
        SELECT 
            a.*,
            p.title AS projectName,
            r.title AS roomName,
            COALESCE(SUM(CASE WHEN ph.status = 'completed' THEN 1 ELSE 0 END), 0) AS uploadedCount,
            COALESCE(SUM(CASE WHEN ph.status = 'failed' THEN 1 ELSE 0 END), 0) AS failedCount,
            COALESCE(SUM(ph.bytesUploaded), 0) AS bytesUploaded
        FROM image_processor_assemblies AS a
        LEFT JOIN offline_projects AS p ON a.projectId = p.projectId
        LEFT JOIN offline_rooms AS r ON (
            a.roomId = r.roomId
            OR a.roomId = r.serverId
        )
        LEFT JOIN image_processor_photos AS ph ON ph.assemblyLocalId = a.id
        GROUP BY a.id
        ORDER BY a.createdAt DESC
        """
    )
    fun observeAllAssembliesWithDetails(): Flow<List<ImageProcessorAssemblyWithDetails>>

    @Query(
        """
        SELECT * FROM image_processor_assemblies
        ORDER BY createdAt DESC
        """
    )
    suspend fun getAllAssemblies(): List<ImageProcessorAssemblyEntity>

    @Query(
        """
        SELECT * FROM image_processor_assemblies
        WHERE status IN (:statuses)
        ORDER BY createdAt ASC
        """
    )
    suspend fun getAssembliesByStatus(statuses: List<String>): List<ImageProcessorAssemblyEntity>

    @Query(
        """
        SELECT * FROM image_processor_assemblies
        WHERE status IN (:queuedStatuses)
        ORDER BY createdAt ASC
        """
    )
    suspend fun getQueuedAssemblies(queuedStatuses: List<String>): List<ImageProcessorAssemblyEntity>

    @Query(
        """
        SELECT * FROM image_processor_assemblies
        WHERE status IN (:retryableStatuses)
          AND (nextRetryAt IS NULL OR nextRetryAt <= :currentTimeMillis)
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRetryableAssemblies(
        retryableStatuses: List<String>,
        currentTimeMillis: Long
    ): List<ImageProcessorAssemblyEntity>

    @Query(
        """
        DELETE FROM image_processor_assemblies
        WHERE status = :status AND lastUpdatedAt < :cutoffMillis
        """
    )
    suspend fun deleteAssembliesByStatusOlderThan(status: String, cutoffMillis: Long)

    @Query("DELETE FROM image_processor_assemblies WHERE assemblyId = :assemblyId")
    suspend fun deleteAssembly(assemblyId: String)

    @Query("DELETE FROM image_processor_assemblies")
    suspend fun deleteAllAssemblies()

    @Query("DELETE FROM image_processor_assemblies WHERE roomId = :roomId")
    suspend fun deleteAssembliesByRoomId(roomId: Long): Int

    @Query("SELECT assemblyId FROM image_processor_assemblies WHERE roomId = :roomId")
    suspend fun getAssemblyIdsByRoomId(roomId: Long): List<String>

    @Query(
        """
        SELECT a.roomId, SUM(a.totalFiles) as pendingCount
        FROM image_processor_assemblies a
        WHERE a.projectId = :projectId
          AND a.status NOT IN ('completed', 'failed')
          AND a.roomId IS NOT NULL
        GROUP BY a.roomId
        """
    )
    fun observePendingPhotoCountsByProject(projectId: Long): Flow<List<RoomPendingCount>>

    data class RoomPendingCount(
        val roomId: Long,
        val pendingCount: Int
    )
    // endregion

    // region Photo operations
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPhoto(photo: ImageProcessorPhotoEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPhotos(photos: List<ImageProcessorPhotoEntity>)

    @Update
    suspend fun updatePhoto(photo: ImageProcessorPhotoEntity)

    @Query(
        """
        SELECT * FROM image_processor_photos
        WHERE assemblyLocalId = :assemblyLocalId
        ORDER BY orderIndex ASC
        """
    )
    suspend fun getPhotosByAssemblyLocalId(assemblyLocalId: Long): List<ImageProcessorPhotoEntity>

    @Query(
        """
        SELECT * FROM image_processor_photos
        WHERE assemblyUuid = :assemblyUuid
        ORDER BY orderIndex ASC
        """
    )
    suspend fun getPhotosByAssemblyUuid(assemblyUuid: String): List<ImageProcessorPhotoEntity>

    @Query(
        """
        SELECT * FROM image_processor_photos
        WHERE assemblyLocalId = :assemblyLocalId
        ORDER BY orderIndex ASC
        """
    )
    fun observePhotosByAssemblyLocalId(
        assemblyLocalId: Long
    ): Flow<List<ImageProcessorPhotoEntity>>

    @Query("SELECT * FROM image_processor_photos WHERE uploadTaskId = :taskId LIMIT 1")
    suspend fun getPhotoByUploadTaskId(taskId: String): ImageProcessorPhotoEntity?

    @Query("SELECT * FROM image_processor_photos WHERE photoId = :photoId LIMIT 1")
    suspend fun getPhotoByPhotoId(photoId: String): ImageProcessorPhotoEntity?

    @Query(
        """
        SELECT * FROM image_processor_photos
        WHERE assemblyUuid = :assemblyUuid AND fileName = :fileName
        LIMIT 1
        """
    )
    suspend fun getPhotoByFilename(
        assemblyUuid: String,
        fileName: String
    ): ImageProcessorPhotoEntity?

    @Query(
        """
        SELECT * FROM image_processor_photos
        WHERE status = :status
        ORDER BY lastUpdatedAt ASC
        """
    )
    suspend fun getPhotosByStatus(status: String): List<ImageProcessorPhotoEntity>

    @Query("DELETE FROM image_processor_photos WHERE assemblyLocalId = :assemblyLocalId")
    suspend fun deletePhotosForAssembly(assemblyLocalId: Long)

    @Query("DELETE FROM image_processor_photos WHERE assemblyUuid IN (:assemblyIds)")
    suspend fun deletePhotosByAssemblyIds(assemblyIds: List<String>): Int
    // endregion
}
