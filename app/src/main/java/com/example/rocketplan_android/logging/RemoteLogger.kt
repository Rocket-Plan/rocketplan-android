package com.example.rocketplan_android.logging

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.api.LoggingService
import com.example.rocketplan_android.data.model.RemoteLogBatch
import com.example.rocketplan_android.data.model.RemoteLogEntry
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

class RemoteLogger(
    private val loggingService: LoggingService,
    context: Context,
    private val secureStorage: SecureStorage,
    private val store: PendingRemoteLogStore = PendingRemoteLogStore(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val mutex = Mutex()
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { flushPendingLogs() }
        }
    }

    private val sessionId: String = UUID.randomUUID().toString()
    private val deviceId: String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
                .onFailure {
                    Log.w(TAG, "Unable to register network callback: ${it.localizedMessage}")
                }
        }
        scope.launch { flushPendingLogs() }
    }

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        metadata: Map<String, String>? = null
    ) {
        val entry = RemoteLogEntry(
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            level = level.name,
            category = tag,
            message = message,
            tags = listOf(tag),
            data = metadata
        )
        scope.launch {
            store.enqueue(entry)
            flushPendingLogs()
        }
    }

    fun flush() {
        scope.launch { flushPendingLogs() }
    }

    private suspend fun flushPendingLogs() {
        if (!isNetworkAvailable()) return
        mutex.withLock {
            val pending = store.getAll()
            if (pending.isEmpty()) return

            val userId = secureStorage.getUserIdSync()
                ?.takeIf { it > 0L }
                ?.toString()
            val companyId = secureStorage.getCompanyIdSync()
                ?.takeIf { it > 0L }
                ?.toString()
            val chunks = pending.chunked(MAX_BATCH_SIZE)

            for (chunk in chunks) {
                val batch = RemoteLogBatch(
                    batchId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    userId = userId,
                    companyId = companyId,
                    sessionId = sessionId,
                    appVersion = AppConfig.versionName,
                    logs = chunk.map { it.entry }
                )

                val success = runCatching { loggingService.submitLogBatch(batch) }
                    .fold(
                        onSuccess = { response ->
                            when {
                                response.isSuccessful -> {
                                    val responseBody = response.body()
                                    if (responseBody != null && !responseBody.success) {
                                        Log.w(TAG, "Remote log batch reported failure: ${responseBody.error.orEmpty()}")
                                    }
                                    true
                                }

                                response.code() in 500..599 -> {
                                    Log.w(
                                        TAG,
                                        "Remote log batch rejected (${response.code()}): ${response.errorBody()?.string()}"
                                    )
                                    false
                                }

                                else -> {
                                    Log.w(
                                        TAG,
                                        "Remote log batch rejected (${response.code()}): ${response.errorBody()?.string()} (dropping)"
                                    )
                                    true
                                }
                            }
                        },
                        onFailure = { throwable ->
                            Log.w(TAG, "Remote log batch failed: ${throwable.localizedMessage}")
                            false
                        }
                    )

                if (success) {
                    store.remove(chunk.map { it.id })
                } else {
                    break
                }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "RemoteLogger"
        private const val MAX_BATCH_SIZE = 50
    }
}
