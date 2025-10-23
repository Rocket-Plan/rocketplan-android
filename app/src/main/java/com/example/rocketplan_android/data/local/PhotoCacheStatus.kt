package com.example.rocketplan_android.data.local

/**
 * Tracks the local cache lifecycle for a remote photo.
 */
enum class PhotoCacheStatus {
    NONE,          // no cache attempt yet
    PENDING,       // queued for download
    DOWNLOADING,   // in progress
    READY,         // original + thumbnail cached locally
    FAILED         // last attempt failed (retry later)
}
