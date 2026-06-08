package com.example.rocketplan_android.ui.common

import com.example.rocketplan_android.data.local.SyncStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** RP-BUG-041: per-item cloud indicator state derivation (iOS precedence: cloud-up > cloud-down). */
class DownloadSyncStateTest {

    @Test
    fun `dirty row is PendingUpload (cloud-up) even if not downloaded`() {
        assertThat(
            downloadSyncState(isDirty = true, syncStatus = SyncStatus.PENDING, isDownloaded = false)
        ).isEqualTo(DownloadSyncState.PendingUpload)
    }

    @Test
    fun `pending syncStatus is PendingUpload`() {
        assertThat(
            downloadSyncState(isDirty = false, syncStatus = SyncStatus.PENDING, isDownloaded = true)
        ).isEqualTo(DownloadSyncState.PendingUpload)
    }

    @Test
    fun `clean but not downloaded is NotDownloaded (cloud-down)`() {
        assertThat(
            downloadSyncState(isDirty = false, syncStatus = SyncStatus.SYNCED, isDownloaded = false)
        ).isEqualTo(DownloadSyncState.NotDownloaded)
    }

    @Test
    fun `synced and downloaded shows nothing`() {
        assertThat(
            downloadSyncState(isDirty = false, syncStatus = SyncStatus.SYNCED, isDownloaded = true)
        ).isEqualTo(DownloadSyncState.Synced)
    }

    @Test
    fun `cloud-up beats cloud-down when both apply`() {
        // dirty (cloud-up) AND not downloaded (cloud-down) -> cloud-up wins, matching iOS precedence.
        assertThat(
            downloadSyncState(isDirty = true, syncStatus = SyncStatus.SYNCED, isDownloaded = false)
        ).isEqualTo(DownloadSyncState.PendingUpload)
    }
}
