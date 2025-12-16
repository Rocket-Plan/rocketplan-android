package com.example.rocketplan_android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
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
        SELECT * FROM image_processor_assemblies
        WHERE roomId = :roomId
        ORDER BY createdAt DESC
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
    // endregion
}
