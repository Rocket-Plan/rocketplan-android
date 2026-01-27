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
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.RemoteLogBatch
import com.example.rocketplan_android.data.model.RemoteLogEntry
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.rocketplan_android.util.UuidUtils
import java.time.Instant
import java.time.format.DateTimeFormatter

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
    private val retryStore: RemoteLogRetryStore = RemoteLogRetryStore(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val mutex = Mutex()
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var scheduledJob: Job? = null
    private var scheduledAtMillis: Long? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleDelayedFlush()
        }
    }

    private val sessionId: String = UuidUtils.generateUuidV7()
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
        scheduleDelayedFlush()
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
            scheduleDelayedFlush()
        }
    }

    fun flush() {
        scheduleProcessing(0)
    }

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    private suspend fun ensureAuthToken(): Boolean {
        val existing = RetrofitClient.getAuthToken()
        if (!existing.isNullOrBlank()) {
            return true
        }

        val stored = secureStorage.getAuthTokenSync()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        RetrofitClient.setAuthToken(stored)
        return true
    }

    private fun scheduleProcessing(delayMillis: Long) {
        val now = System.currentTimeMillis()
        val target = now + delayMillis
        val currentTarget = scheduledAtMillis
        val active = scheduledJob?.isActive == true
        if (active && currentTarget != null && currentTarget <= target) {
            return
        }

        scheduledJob?.cancel()
        scheduledAtMillis = target
        scheduledJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            scheduledAtMillis = null
            scheduledJob = null
            processQueue()
        }
    }

    private fun scheduleDelayedFlush() {
        if (scheduledJob?.isActive == true) {
            return
        }
        scheduleProcessing(PERIODIC_FLUSH_INTERVAL_MS)
    }

    private suspend fun processQueue() {
        mutex.withLock {
            if (!ensureAuthToken()) {
                scheduleProcessing(AUTH_RETRY_DELAY_MS)
                return
            }

            if (!isNetworkAvailable()) {
                scheduleProcessing(NETWORK_RETRY_DELAY_MS)
                return
            }

            var queue = retryStore.getAll().toMutableList()
            val initialFill = fillQueue(queue)
            queue = initialFill.queue
            var scheduleImmediate = initialFill.added
            val now = System.currentTimeMillis()
            var nextDelayMs: Long? = null

            val iterator = queue.listIterator()
            while (iterator.hasNext()) {
                val batch = iterator.next()
                if (batch.nextAttemptAtMillis > now) {
                    val delay = batch.nextAttemptAtMillis - now
                    nextDelayMs = minDelay(nextDelayMs, delay)
                    continue
                }

                val logs = store.getByIds(batch.logIds)
                if (logs.isEmpty()) {
                    iterator.remove()
                    scheduleImmediate = true
                    continue
                }

                when (val result = submitBatch(logs.map { it.entry }, batch.batchId, batch.attempt)) {
                    BatchResult.Success -> {
                        iterator.remove()
                        store.remove(batch.logIds)
                        scheduleImmediate = true
                    }

                    BatchResult.Drop -> {
                        iterator.remove()
                        store.remove(batch.logIds)
                        scheduleImmediate = true
                    }

                    BatchResult.Retry -> {
                        val nextAttempt = batch.attempt + 1
                        if (nextAttempt > MAX_RETRY_ATTEMPTS) {
                            Log.w(TAG, "Max retry attempts reached for batch ${batch.batchId}. Dropping.")
                            iterator.remove()
                            store.remove(batch.logIds)
                            scheduleImmediate = true
                        } else {
                            val delaySeconds = calculateBackoffSeconds(nextAttempt)
                            val delayMs = delaySeconds * 1000
                            iterator.set(
                                batch.copy(
                                    attempt = nextAttempt,
                                    nextAttemptAtMillis = now + delayMs
                                )
                            )
                            nextDelayMs = minDelay(nextDelayMs, delayMs)
                        }
                    }
                }
            }

            val postFill = fillQueue(queue)
            queue = postFill.queue
            scheduleImmediate = scheduleImmediate || postFill.added

            retryStore.saveAll(queue)

            when {
                scheduleImmediate -> scheduleProcessing(0)
                nextDelayMs != null -> scheduleProcessing(nextDelayMs)
            }
        }
    }

    private suspend fun submitBatch(
        entries: List<RemoteLogEntry>,
        batchId: String,
        attempt: Int
    ): BatchResult {
        if (RetrofitClient.getAuthToken().isNullOrBlank()) {
            secureStorage.getAuthTokenSync()
                ?.takeIf { it.isNotBlank() }
                ?.let { RetrofitClient.setAuthToken(it) }
        }

        val userId = secureStorage.getUserIdSync()
            ?.takeIf { it > 0L }
            ?.toString()
        val companyId = secureStorage.getCompanyIdSync()
            ?.takeIf { it > 0L }
            ?.toString()

        val batch = RemoteLogBatch(
            batchId = batchId,
            deviceId = deviceId,
            userId = userId,
            companyId = companyId,
            sessionId = sessionId,
            appVersion = AppConfig.versionName,
            buildNumber = AppConfig.versionCode.toString(),
            platform = RemoteLogBatch.PLATFORM_ANDROID,
            logs = entries
        )

        return runCatching { loggingService.submitLogBatch(batch) }
            .fold(
                onSuccess = { response ->
                    val errorBody = response.errorBody()?.string()
                    when {
                        response.isSuccessful -> {
                            val body = response.body()
                            if (body != null && !body.success) {
                                Log.w(TAG, "Remote log batch reported failure: ${body.error.orEmpty()}")
                                BatchResult.Drop
                            } else {
                                BatchResult.Success
                            }
                        }

                        isRetryableStatusCode(response.code()) -> {
                            Log.w(
                                TAG,
                                "Remote log batch rejected (HTTP ${response.code()}), scheduling retry: $errorBody"
                            )
                            BatchResult.Retry
                        }

                        else -> {
                            Log.w(
                                TAG,
                                "Remote log batch rejected (HTTP ${response.code()}), dropping: $errorBody"
                            )
                            BatchResult.Drop
                        }
                    }
                },
                onFailure = { throwable ->
                    Log.w(
                        TAG,
                        "Remote log batch failed on attempt ${attempt + 1}/${MAX_RETRY_ATTEMPTS + 1}: ${throwable.localizedMessage}"
                    )
                    BatchResult.Retry
                }
            )
    }

    private suspend fun fillQueue(
        queue: MutableList<RetryableLogBatch>
    ): FillResult {
        val queuedIds = queue.flatMap { it.logIds }.toSet()
        val availableSlots = (MAX_RETRY_QUEUE_SIZE - queue.size).coerceAtLeast(0)
        if (availableSlots == 0) {
            enforceQueueLimit(queue)
            return FillResult(queue, false)
        }

        val pending = store.getAll()
        val notQueued = pending.filter { !queuedIds.contains(it.id) }
        if (notQueued.isEmpty()) {
            enforceQueueLimit(queue)
            return FillResult(queue, false)
        }

        val newBatches = notQueued.chunked(MAX_BATCH_SIZE)
            .take(availableSlots)
            .map { chunk ->
                RetryableLogBatch(
                    batchId = UuidUtils.generateUuidV7(),
                    logIds = chunk.map { it.id },
                    attempt = 0,
                    nextAttemptAtMillis = 0
                )
            }

        queue.addAll(newBatches)
        enforceQueueLimit(queue)
        return FillResult(queue, newBatches.isNotEmpty())
    }

    private suspend fun enforceQueueLimit(queue: MutableList<RetryableLogBatch>) {
        while (queue.size > MAX_RETRY_QUEUE_SIZE) {
            val dropped = queue.removeAt(0)
            store.remove(dropped.logIds)
            Log.w(TAG, "Retry queue full. Dropping oldest batch ${dropped.batchId}.")
        }
    }

    private fun calculateBackoffSeconds(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1)
        val candidate = INITIAL_BACKOFF_SECONDS * multiplier
        return candidate.coerceAtMost(MAX_BACKOFF_SECONDS)
    }

    private fun isRetryableStatusCode(code: Int): Boolean {
        return when (code) {
            401, 429, 408, 423 -> true
            in 500..599 -> true
            else -> false
        }
    }

    private fun minDelay(current: Long?, candidate: Long): Long {
        return current?.let { kotlin.math.min(it, candidate) } ?: candidate
    }

    private sealed class BatchResult {
        object Success : BatchResult()
        object Drop : BatchResult()
        object Retry : BatchResult()
    }

    private data class FillResult(
        val queue: MutableList<RetryableLogBatch>,
        val added: Boolean
    )

    companion object {
        private const val TAG = "RemoteLogger"
        private const val PERIODIC_FLUSH_INTERVAL_MS = 5 * 1000L
        private const val MAX_BATCH_SIZE = 50
        private const val MAX_RETRY_QUEUE_SIZE = 10
        private const val MAX_RETRY_ATTEMPTS = 11
        private const val INITIAL_BACKOFF_SECONDS = 5L
        private const val MAX_BACKOFF_SECONDS = 1800L
        private const val NETWORK_RETRY_DELAY_MS = 5_000L
        private const val AUTH_RETRY_DELAY_MS = 5_000L
    }
}
