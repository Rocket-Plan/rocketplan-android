package com.example.rocketplan_android.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.rocketplan_android.data.model.LibraryMediaItem
import com.example.rocketplan_android.data.model.LibraryMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Queries media content that is allowed to be displayed inside the in-app library.
 * Only JPEG images and MP4 videos stored under DCIM are returned.
 */
class LibraryMediaRepository(private val context: Context) {

    suspend fun loadDcimMedia(): Result<List<LibraryMediaItem>> = runCatching {
        withContext(Dispatchers.IO) {
            val images = queryImages()
            val videos = queryVideos()
            (images + videos).sortedByDescending { it.dateAddedMillis }
        }
    }

    private fun queryImages(): List<LibraryMediaItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            legacyPathColumn()
        )

        val selection = dcimSelection(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATA) +
            " AND ${MediaStore.Images.Media.MIME_TYPE} IN (?,?)"
        val selectionArgs = dcimSelectionArgs() + listOf("image/jpeg", "image/jpg")

        return query(collection, projection, selection, selectionArgs.toTypedArray(), LibraryMediaType.IMAGE)
    }

    private fun queryVideos(): List<LibraryMediaItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            legacyPathColumn()
        )

        val selection = dcimSelection(MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DATA) +
            " AND ${MediaStore.Video.Media.MIME_TYPE} = ?"
        val selectionArgs = dcimSelectionArgs() + listOf("video/mp4")

        return query(collection, projection, selection, selectionArgs.toTypedArray(), LibraryMediaType.VIDEO)
    }

    @Suppress("DEPRECATION")
    private fun legacyPathColumn(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }

    private fun dcimSelection(relativePathColumn: String, legacyPathColumn: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "$relativePathColumn LIKE ?"
        } else {
            "$legacyPathColumn LIKE ?"
        }

    private fun dcimSelectionArgs(): List<String> =
        listOf("DCIM/%")

    @Suppress("DEPRECATION")
    private fun query(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        type: LibraryMediaType
    ): List<LibraryMediaItem> {
        val resolver = context.contentResolver
        val items = mutableListOf<LibraryMediaItem>()

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(nameColumn) ?: ""
                val mimeType = cursor.getString(mimeColumn) ?: ""
                val dateAddedSeconds = cursor.getLong(dateAddedColumn)
                val uri = ContentUris.withAppendedId(collection, id)

                items.add(
                    LibraryMediaItem(
                        uri = uri,
                        displayName = displayName,
                        mimeType = mimeType,
                        dateAddedMillis = dateAddedSeconds * 1000,
                        type = type
                    )
                )
            }
        }

        return items
    }
}
