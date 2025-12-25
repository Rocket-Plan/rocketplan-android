package com.example.rocketplan_android.data.local.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.rocketplan_android.data.api.RetrofitClient
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

        val requestBuilder = Request.Builder().url(remoteUrl)
        RetrofitClient.getAuthToken()?.takeIf { it.isNotBlank() }?.let { token ->
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }
        val request = requestBuilder.build()
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
     * Removes all cached photos for the given list of photos.
     * Used when cascade deleting a project.
     */
    suspend fun removeCachedPhotos(photos: List<OfflinePhotoEntity>) = withContext(ioDispatcher) {
        var deleted = 0
        photos.forEach { photo ->
            runCatching {
                photo.cachedOriginalPath?.let { File(it).delete() }
                photo.cachedThumbnailPath?.let { File(it).delete() }
                deleted++
            }
        }
        if (deleted > 0) {
            Log.d(TAG, "🧹 Removed $deleted cached photo files")
        }
    }

    /**
     * Generates a downscaled JPEG thumbnail alongside the cached original bytes.
     */
    private fun generateThumbnail(originalFile: File, mimeType: String): File? {
        if (!mimeType.lowercase().startsWith("image")) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(originalFile.absolutePath, bounds)
            val (width, height) = bounds.outWidth to bounds.outHeight
            if (width <= 0 || height <= 0) return null
            if (width <= THUMBNAIL_MAX_DIMENSION && height <= THUMBNAIL_MAX_DIMENSION) {
                return null // original small enough
            }

            val sampleSize = calculateInSampleSize(width, height, THUMBNAIL_MAX_DIMENSION)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val sampled = BitmapFactory.decodeFile(originalFile.absolutePath, decodeOptions) ?: return null

            val maxDimension = maxOf(sampled.width, sampled.height)
            val scaled = if (maxDimension > THUMBNAIL_MAX_DIMENSION) {
                val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxDimension
                val thumbWidth = (sampled.width * scale).toInt().coerceAtLeast(64)
                val thumbHeight = (sampled.height * scale).toInt().coerceAtLeast(64)
                Bitmap.createScaledBitmap(sampled, thumbWidth, thumbHeight, true)
            } else {
                sampled
            }

            val thumbnailFile = File(originalFile.parentFile, "${originalFile.nameWithoutExtension}_thumb.jpg")
            FileOutputStream(thumbnailFile).use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
            }
            if (scaled != sampled) {
                scaled.recycle()
            }
            sampled.recycle()
            thumbnailFile
        }.getOrNull()
    }

    suspend fun cleanUpUnused(threshold: Date, maxBytes: Long) = withContext(ioDispatcher) {
        if (maxBytes <= 0) return@withContext

        val cached = localDataService.getCachedPhotos()
        if (cached.isEmpty()) return@withContext

        val entries = cached.map { photo ->
            val original = photo.cachedOriginalPath?.let(::File)
            val thumb = photo.cachedThumbnailPath?.let(::File)
            val originalSize = original?.takeIf { it.exists() }?.length() ?: 0L
            val thumbSize = thumb?.takeIf { it.exists() }?.length() ?: 0L
            CachedFiles(photo, original, thumb, originalSize + thumbSize)
        }

        val victims = LinkedHashSet<CachedFiles>()

        // Expire old or missing files first
        entries.forEach { entry ->
            val lastAccess = entry.photo.lastAccessedAt ?: entry.photo.updatedAt
            val expired = lastAccess?.before(threshold) == true
            if (expired || entry.totalBytes == 0L) {
                victims.add(entry)
            }
        }

        var totalBytes = entries.sumOf { it.totalBytes }

        // Enforce maxBytes using LRU (oldest lastAccessedAt first)
        if (totalBytes > maxBytes) {
            val lru = entries
                .filterNot { victims.contains(it) }
                .sortedBy { it.photo.lastAccessedAt?.time ?: 0L }

            lru.forEach { entry ->
                if (totalBytes <= maxBytes) return@forEach
                victims.add(entry)
                totalBytes -= entry.totalBytes
            }
        }

        victims.forEach { entry ->
            entry.original?.takeIf { it.exists() }?.delete()
            entry.thumbnail?.takeIf { it.exists() }?.delete()
            localDataService.markPhotoCacheFailed(entry.photo.photoId)
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDimension * 2 || height / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private data class CachedFiles(
        val photo: OfflinePhotoEntity,
        val original: File?,
        val thumbnail: File?,
        val totalBytes: Long
    )

    private fun fileExtension(mimeType: String): String =
        when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
}
