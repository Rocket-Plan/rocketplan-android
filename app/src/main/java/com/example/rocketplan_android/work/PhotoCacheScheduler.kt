package com.example.rocketplan_android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class PhotoCacheScheduler(private val context: Context) {

    companion object {
        private const val WORK_NAME = "photo_prefetch_work"
        // Use a modest initial delay to avoid aggressive retry storms on flaky networks
        private const val BACKOFF_DELAY_MS = 5_000L
    }

    private val workManager: WorkManager = WorkManager.getInstance(context)

    fun schedulePrefetch(limit: Int = 25) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PhotoPrefetchWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
            .setInputData(
                Data.Builder()
                    .putInt(PhotoPrefetchWorker.KEY_LIMIT, limit)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}
