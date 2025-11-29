package com.example.rocketplan_android.data.model

import android.net.Uri

enum class LibraryMediaType {
    IMAGE,
    VIDEO
}

data class LibraryMediaItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAddedMillis: Long,
    val type: LibraryMediaType
)
