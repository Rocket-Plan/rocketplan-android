package com.example.rocketplan_android.logging

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RetryableLogBatch(
    val batchId: String,
    val logIds: List<String>,
    val attempt: Int,
    val nextAttemptAtMillis: Long
)

class RemoteLogRetryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<RetryableLogBatch>>() {}.type
    private val mutex = Mutex()
    private var cache: MutableList<RetryableLogBatch>? = null

    suspend fun getAll(): List<RetryableLogBatch> = mutex.withLock {
        return loadInternal().toList()
    }

    suspend fun saveAll(batches: List<RetryableLogBatch>) = mutex.withLock {
        cache = batches.toMutableList()
        persistLocked(cache!!)
    }

    private fun persistLocked(current: MutableList<RetryableLogBatch>) {
        runCatching {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            file.writeText(gson.toJson(current, listType))
        }.onFailure {
            Log.w(TAG, "Failed to persist retry queue: ${it.localizedMessage}")
        }
    }

    private fun loadInternal(): MutableList<RetryableLogBatch> {
        val existing = cache
        if (existing != null) {
            return existing
        }
        val loaded = runCatching {
            if (!file.exists()) {
                mutableListOf()
            } else {
                val text = file.readText()
                if (text.isBlank()) {
                    mutableListOf()
                } else {
                    gson.fromJson<MutableList<RetryableLogBatch>>(text, listType) ?: mutableListOf()
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to load retry queue: ${it.localizedMessage}")
            mutableListOf()
        }
        cache = loaded
        return loaded
    }

    companion object {
        private const val FILE_NAME = "remote_log_retry_queue.json"
        private const val TAG = "RemoteLogRetryStore"
    }
}
