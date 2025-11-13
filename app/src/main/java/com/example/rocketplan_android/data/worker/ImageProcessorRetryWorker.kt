package com.example.rocketplan_android.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.rocketplan_android.RocketPlanApplication

/**
 * Periodic worker that checks for failed assemblies ready for retry.
 * Runs every 15 minutes (matching iOS's 60-second interval, but less aggressive for Android battery life).
 */
class ImageProcessorRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ImgProcessorRetryWorker"
        const val WORK_NAME = "image_processor_retry"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "🔄 ImageProcessorRetryWorker started")

            val app = applicationContext as RocketPlanApplication
            val queueManager = app.imageProcessorQueueManager

            // Process retry queue
            queueManager.processRetryQueue(bypassTimeout = false)

            Log.d(TAG, "✅ ImageProcessorRetryWorker completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ ImageProcessorRetryWorker failed", e)
            Result.retry()
        }
    }
}
