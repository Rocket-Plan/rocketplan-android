package com.example.rocketplan_android.logging

import android.content.Context
import android.util.Log
import com.example.rocketplan_android.data.model.RemoteLogEntry
import com.example.rocketplan_android.util.UuidUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PendingRemoteLog(
    val id: String,
    val entry: RemoteLogEntry
)

class PendingRemoteLogStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<PendingRemoteLog>>() {}.type
    private val mutex = Mutex()
    private var cache: MutableList<PendingRemoteLog>? = null

    suspend fun enqueue(entry: RemoteLogEntry): PendingRemoteLog = mutex.withLock {
        val current = loadInternal()
        val queued = PendingRemoteLog(UuidUtils.generateUuidV7(), entry)
        current.add(queued)
        if (current.size > MAX_BUFFER_SIZE) {
            val overflow = current.size - MAX_BUFFER_SIZE
            repeat(overflow.coerceAtMost(current.size)) { current.removeAt(0) }
            Log.w(TAG, "Pending log buffer full. Dropping $overflow oldest entries.")
        }
        persistLocked(current)
        queued
    }

    suspend fun getAll(): List<PendingRemoteLog> = mutex.withLock {
        return loadInternal().toList()
    }

    suspend fun getByIds(ids: Collection<String>): List<PendingRemoteLog> = mutex.withLock {
        if (ids.isEmpty()) return emptyList()
        val set = ids.toSet()
        return loadInternal().filter { set.contains(it.id) }
    }

    suspend fun remove(ids: Collection<String>) = mutex.withLock {
        if (ids.isEmpty()) return
        val current = loadInternal()
        val removed = current.removeAll { ids.contains(it.id) }
        if (removed) {
            persistLocked(current)
        }
    }

    private fun persistLocked(current: MutableList<PendingRemoteLog>) {
        runCatching {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            file.writeText(gson.toJson(current, listType))
        }.onFailure {
            Log.w(TAG, "Failed to persist pending logs: ${it.localizedMessage}")
        }
    }

    private fun loadInternal(): MutableList<PendingRemoteLog> {
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
                    gson.fromJson<MutableList<PendingRemoteLog>>(text, listType) ?: mutableListOf()
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to load pending logs: ${it.localizedMessage}")
            mutableListOf()
        }
        cache = loaded
        return loaded
    }

    companion object {
        private const val FILE_NAME = "pending_remote_logs.json"
        private const val MAX_BUFFER_SIZE = 2000
        private const val TAG = "PendingRemoteLogStore"
    }
}
