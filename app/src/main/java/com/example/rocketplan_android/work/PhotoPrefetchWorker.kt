package com.example.rocketplan_android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.rocketplan_android.RocketPlanApplication

class PhotoPrefetchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_LIMIT = "limit"
    }

    private val application = appContext.applicationContext as RocketPlanApplication
    private val localDataService = application.localDataService
    private val photoCacheManager = application.photoCacheManager

    override suspend fun doWork(): Result {
        val limit = inputData.getInt(KEY_LIMIT, 25).coerceAtLeast(1)
        val pending = localDataService.getPhotosNeedingCache(limit)
        if (pending.isEmpty()) {
            return Result.success()
        }
        photoCacheManager.cachePhotos(pending)
        return Result.success()
    }
}
