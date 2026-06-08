package com.example.rocketplan_android.ui.common

import android.view.View
import android.widget.ImageView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.local.SyncStatus

/**
 * RP-BUG-041: per-item cloud indicator state, matching the iOS precedence (cloud-up beats cloud-down).
 *
 * - [PendingUpload] (cloud-up): the row has unsynced local changes waiting to be pushed.
 * - [NotDownloaded] (cloud-down): the row's content has not been downloaded to this device yet.
 * - [Synced]: fully synced + downloaded — show nothing.
 */
enum class DownloadSyncState { Synced, PendingUpload, NotDownloaded }

/**
 * Derive the indicator state. `isDownloaded` means the item's content is present locally (e.g. it has
 * been pulled at least once / its file is cached). Pure + side-effect free so it can be unit-tested.
 */
fun downloadSyncState(
    isDirty: Boolean,
    syncStatus: SyncStatus,
    isDownloaded: Boolean,
): DownloadSyncState = when {
    isDirty || syncStatus == SyncStatus.PENDING -> DownloadSyncState.PendingUpload // cloud-up wins
    !isDownloaded -> DownloadSyncState.NotDownloaded
    else -> DownloadSyncState.Synced
}

/** Bind the cloud glyph to an [ImageView] (hidden when fully synced). */
fun ImageView.bindDownloadSyncIndicator(state: DownloadSyncState) {
    when (state) {
        DownloadSyncState.Synced -> visibility = View.GONE
        DownloadSyncState.PendingUpload -> {
            setImageResource(R.drawable.ic_cloud_upload)
            visibility = View.VISIBLE
        }
        DownloadSyncState.NotDownloaded -> {
            setImageResource(R.drawable.ic_cloud_off)
            visibility = View.VISIBLE
        }
    }
}
