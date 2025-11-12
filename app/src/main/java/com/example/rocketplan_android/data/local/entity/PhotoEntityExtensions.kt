package com.example.rocketplan_android.data.local.entity

import java.io.File

fun OfflinePhotoEntity.hasRenderableAsset(): Boolean =
    !remoteUrl.isNullOrBlank() ||
        !thumbnailUrl.isNullOrBlank() ||
        !cachedOriginalPath.isNullOrBlank() ||
        !cachedThumbnailPath.isNullOrBlank() ||
        localPath.isNotBlank()

fun OfflinePhotoEntity.preferredImageSource(): String =
    cachedOriginalPath.existingFilePath() ?:
        remoteUrl?.takeIf { it.isNotBlank() } ?:
        localPath.existingFilePath() ?:
        cachedThumbnailPath.existingFilePath() ?:
        thumbnailUrl?.takeIf { it.isNotBlank() } ?: ""

fun OfflinePhotoEntity.preferredThumbnailSource(): String =
    cachedThumbnailPath.existingFilePath() ?:
        thumbnailUrl?.takeIf { it.isNotBlank() } ?:
        cachedOriginalPath.existingFilePath() ?:
        remoteUrl?.takeIf { it.isNotBlank() } ?:
        localPath.existingFilePath() ?: ""

private fun String?.existingFilePath(): String? {
    if (this.isNullOrBlank()) return null
    val file = File(this)
    return file.takeIf { it.exists() }?.absolutePath
}
