package com.example.rocketplan_android.data.local.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date

class PhotoCacheManager(
    context: Context,
    private val localDataService: LocalDataService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    companion object {
        private const val TAG = "PhotoCacheManager"
        private const val THUMBNAIL_MAX_DIMENSION = 512
    }

    private val cacheRoot: File = File(context.filesDir, "photo_cache").apply {
        if (!exists()) mkdirs()
    }

    suspend fun cachePhotos(photos: List<OfflinePhotoEntity>) {
        photos.forEach { photo ->
            runCatching { cachePhoto(photo) }.onFailure {
                Log.w(TAG, "Failed to cache photo ${photo.photoId}", it)
            }
        }
    }

    suspend fun cachePhoto(photo: OfflinePhotoEntity) = withContext(ioDispatcher) {
        val remoteUrl = photo.remoteUrl ?: return@withContext

        // If already cached and still on disk, just bump access timestamp.
        val existing = photo.cachedOriginalPath?.takeIf { File(it).exists() }
        if (existing != null) {
            localDataService.touchPhotoAccess(photo.photoId)
            return@withContext
        }

        localDataService.markPhotoCacheInProgress(photo.photoId)

        val request = Request.Builder().url(remoteUrl).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} while fetching $remoteUrl")
                }
                val body = response.body ?: throw IOException("Empty body for $remoteUrl")

                val projectDir = File(cacheRoot, photo.projectId.toString())
                if (!projectDir.exists() && !projectDir.mkdirs()) {
                    throw IOException("Unable to create cache directory $projectDir")
                }

                val originalFile = File(projectDir, "${photo.uuid}.${fileExtension(photo.mimeType)}")
                body.byteStream().use { input ->
                    FileOutputStream(originalFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val thumbnailFile = generateThumbnail(originalFile, photo.mimeType)

                localDataService.markPhotoCacheSuccess(
                    photoId = photo.photoId,
                    originalPath = originalFile.absolutePath,
                    thumbnailPath = thumbnailFile?.absolutePath
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error caching photo ${photo.photoId}", t)
            localDataService.markPhotoCacheFailed(photo.photoId)
        }
    }

    suspend fun removeCachedPhoto(photo: OfflinePhotoEntity) = withContext(ioDispatcher) {
        photo.cachedOriginalPath?.let { runCatching { File(it).delete() } }
        photo.cachedThumbnailPath?.let { runCatching { File(it).delete() } }
        localDataService.markPhotoCacheFailed(photo.photoId)
    }

    /**
     * Generates a downscaled JPEG thumbnail alongside the cached original bytes.
     */
    private fun generateThumbnail(originalFile: File, mimeType: String): File? {
        if (!mimeType.lowercase().startsWith("image")) return null
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(originalFile.absolutePath) ?: return null
            val (width, height) = bitmap.width to bitmap.height
            if (width <= THUMBNAIL_MAX_DIMENSION && height <= THUMBNAIL_MAX_DIMENSION) {
                return null // original small enough
            }
            val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxOf(width, height)
            val thumbWidth = (width * scale).toInt().coerceAtLeast(64)
            val thumbHeight = (height * scale).toInt().coerceAtLeast(64)
            val thumbnail = Bitmap.createScaledBitmap(bitmap, thumbWidth, thumbHeight, true)
            val thumbnailFile = File(originalFile.parentFile, "${originalFile.nameWithoutExtension}_thumb.jpg")
            FileOutputStream(thumbnailFile).use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, output)
            }
            bitmap.recycle()
            if (thumbnail != bitmap) {
                thumbnail.recycle()
            }
            thumbnailFile
        }.getOrNull()
    }

    suspend fun cleanUpUnused(threshold: Date, maxBytes: Long) = withContext(ioDispatcher) {
        // TODO: implement LRU cleanup respecting threshold and maxBytes
    }

    private fun fileExtension(mimeType: String): String =
        when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
}
