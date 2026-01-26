package com.example.rocketplan_android.data.network

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicLong

/**
 * Backend health state enumeration.
 */
enum class HealthState {
    /** Initial state, health not yet checked */
    UNKNOWN,
    /** Backend is reachable and responding */
    HEALTHY,
    /** Backend is unreachable or returning errors */
    UNHEALTHY,
    /** Health check is in progress */
    CHECKING
}

/**
 * Result of a health check operation.
 */
data class HealthCheckResult(
    val isHealthy: Boolean,
    val responseTimeMs: Long = 0,
    val error: String? = null,
    val checkedAt: Long = System.currentTimeMillis()
)

/**
 * Service for checking backend health status.
 * Provides a lightweight endpoint check to verify the backend is reachable
 * before attempting sync operations.
 *
 * Features:
 * - Cached health state with configurable TTL
 * - Timeout protection for slow connections
 * - Exponential backoff hint for retry scheduling
 */
class BackendHealthCheckService(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _healthState = MutableStateFlow(HealthState.UNKNOWN)
    val healthState: StateFlow<HealthState> = _healthState.asStateFlow()

    private val lastCheckTime = AtomicLong(0)
    private var lastResult: HealthCheckResult? = null
    private var consecutiveFailures = 0

    /**
     * Checks if the backend is healthy.
     * Uses cached result if available and not expired.
     *
     * @param force If true, bypasses cache and performs fresh check
     * @return true if backend is healthy, false otherwise
     */
    suspend fun checkHealth(force: Boolean = false): Boolean = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val lastCheck = lastCheckTime.get()
        val cacheExpired = (now - lastCheck) > CACHE_DURATION_MS

        // Return cached result if still valid
        if (!force && !cacheExpired && lastResult != null) {
            return@withContext lastResult?.isHealthy == true
        }

        // Perform health check
        _healthState.value = HealthState.CHECKING
        val result = performHealthCheck()

        lastResult = result
        lastCheckTime.set(now)

        if (result.isHealthy) {
            _healthState.value = HealthState.HEALTHY
            consecutiveFailures = 0
            Log.d(TAG, "✅ Backend health check passed (${result.responseTimeMs}ms)")
        } else {
            _healthState.value = HealthState.UNHEALTHY
            consecutiveFailures++
            Log.w(TAG, "❌ Backend health check failed: ${result.error} (consecutive failures: $consecutiveFailures)")
        }

        result.isHealthy
    }

    /**
     * Returns the recommended delay before the next health check retry,
     * using exponential backoff based on consecutive failures.
     */
    fun getRetryDelayMs(): Long {
        val backoff = INITIAL_RETRY_DELAY_MS * (1L shl minOf(consecutiveFailures, MAX_BACKOFF_EXPONENT))
        return minOf(backoff, MAX_RETRY_DELAY_MS)
    }

    /**
     * Resets the health check state (e.g., after network restoration).
     */
    fun reset() {
        _healthState.value = HealthState.UNKNOWN
        lastCheckTime.set(0)
        lastResult = null
        consecutiveFailures = 0
        Log.d(TAG, "🔄 Health check state reset")
    }

    /**
     * Gets the last health check result, if any.
     */
    fun getLastResult(): HealthCheckResult? = lastResult

    /**
     * Checks if the cached health state is still valid.
     */
    fun isCacheValid(): Boolean {
        val now = System.currentTimeMillis()
        val lastCheck = lastCheckTime.get()
        return (now - lastCheck) <= CACHE_DURATION_MS
    }

    private suspend fun performHealthCheck(): HealthCheckResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()

        try {
            val result = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url("$baseUrl$STATUS_ENDPOINT")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val responseTime = System.currentTimeMillis() - startTime

                    when {
                        response.isSuccessful -> {
                            HealthCheckResult(
                                isHealthy = true,
                                responseTimeMs = responseTime
                            )
                        }
                        response.code in 500..599 -> {
                            HealthCheckResult(
                                isHealthy = false,
                                responseTimeMs = responseTime,
                                error = "Server error: ${response.code}"
                            )
                        }
                        response.code == 401 || response.code == 403 -> {
                            // Auth errors still mean backend is reachable
                            HealthCheckResult(
                                isHealthy = true,
                                responseTimeMs = responseTime
                            )
                        }
                        else -> {
                            HealthCheckResult(
                                isHealthy = false,
                                responseTimeMs = responseTime,
                                error = "Unexpected response: ${response.code}"
                            )
                        }
                    }
                }
            }

            result ?: HealthCheckResult(
                isHealthy = false,
                responseTimeMs = System.currentTimeMillis() - startTime,
                error = "Timeout after ${HEALTH_CHECK_TIMEOUT_MS}ms"
            )
        } catch (e: Exception) {
            HealthCheckResult(
                isHealthy = false,
                responseTimeMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }

    companion object {
        private const val TAG = "BackendHealthCheck"
        private const val STATUS_ENDPOINT = "/api/status"
        private const val HEALTH_CHECK_TIMEOUT_MS = 5_000L
        private const val CACHE_DURATION_MS = 30_000L // 30 seconds cache
        private const val INITIAL_RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val MAX_BACKOFF_EXPONENT = 4
    }
}
