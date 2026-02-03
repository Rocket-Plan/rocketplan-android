package com.example.rocketplan_android.data.network

import android.util.Log
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Executes API calls with configurable retry logic matching iOS behavior:
 * - Network errors: 3 retries with 2s, 4s, 6s delays
 * - 503 errors: exponential backoff (max 30s), then fail
 *
 * This helps ensure sync reliability by automatically retrying transient failures
 * while failing fast on permanent errors.
 */
object RetryingApiExecutor {
    private const val TAG = "RetryingApiExecutor"

    /** Default delays for network retries (in milliseconds) */
    private val DEFAULT_RETRY_DELAYS = listOf(2000L, 4000L, 6000L)

    /** Maximum backoff delay for 503 errors (in milliseconds) */
    private const val MAX_503_BACKOFF_MS = 30_000L

    /** Initial backoff for 503 errors (in milliseconds) */
    private const val INITIAL_503_BACKOFF_MS = 2000L

    /** Maximum number of retries for 503 errors */
    private const val MAX_503_RETRIES = 5

    /**
     * Result of an API execution attempt.
     */
    sealed class Result<out T> {
        /**
         * Successful execution with the returned value.
         */
        data class Success<T>(val value: T) : Result<T>()

        /**
         * Failed execution after all retry attempts.
         * @param error The last error encountered
         * @param retriesAttempted Number of retries attempted before giving up
         * @param is503Failure True if the failure was due to 503 server overload
         */
        data class Failure<T>(
            val error: Throwable,
            val retriesAttempted: Int,
            val is503Failure: Boolean = false
        ) : Result<T>()
    }

    /**
     * Execute an API call with automatic retry for network errors.
     *
     * Retry behavior:
     * - Network errors (IOException): Uses standard delays (2s, 4s, 6s by default)
     * - Server errors (5xx except 503): Uses standard delays
     * - 503 Server Overload: Uses exponential backoff (2s, 4s, 8s, 16s, 30s cap),
     *   respecting the minimum of maxRetries and MAX_503_RETRIES (5)
     * - Client errors (4xx): No retry, fails immediately
     *
     * @param name Descriptive name for logging (e.g., "syncProperty")
     * @param maxRetries Maximum number of retries (default: 3). For 503 errors,
     *                   this is capped at MAX_503_RETRIES (5) but honored if lower.
     * @param retryDelays Delays between retries in ms (default: 2s, 4s, 6s).
     *                    Not used for 503 errors which use exponential backoff.
     * @param remoteLogger Optional remote logger for telemetry
     * @param block The suspending API call to execute
     * @return Result.Success with the value, or Result.Failure with error details
     */
    suspend fun <T> execute(
        name: String,
        maxRetries: Int = DEFAULT_RETRY_DELAYS.size,
        retryDelays: List<Long> = DEFAULT_RETRY_DELAYS,
        remoteLogger: RemoteLogger? = null,
        block: suspend () -> T
    ): Result<T> {
        var lastError: Throwable? = null
        var retriesAttempted = 0

        for (attempt in 0..maxRetries) {
            try {
                val result = block()
                return Result.Success(result)
            } catch (e: CancellationException) {
                // Don't catch coroutine cancellation
                throw e
            } catch (e: HttpException) {
                val httpCode = e.code()
                lastError = e

                when {
                    // 503 Service Unavailable - use exponential backoff
                    httpCode == 503 -> {
                        return handle503Error(name, maxRetries, remoteLogger, block)
                    }
                    // Other server errors (5xx) - retry with standard delays
                    httpCode in 500..599 -> {
                        if (attempt < maxRetries) {
                            val delayMs = retryDelays.getOrElse(attempt) { retryDelays.last() }
                            logRetry(name, attempt + 1, maxRetries, "HTTP $httpCode", delayMs, remoteLogger)
                            delay(delayMs)
                            retriesAttempted++
                        }
                    }
                    // Client errors (4xx) - don't retry, fail immediately
                    httpCode in 400..499 -> {
                        Log.w(TAG, "[$name] Client error HTTP $httpCode, not retrying")
                        return Result.Failure(e, retriesAttempted)
                    }
                    else -> {
                        // Unknown HTTP error - don't retry
                        return Result.Failure(e, retriesAttempted)
                    }
                }
            } catch (e: IOException) {
                // Network errors - retry with standard delays
                lastError = e
                if (attempt < maxRetries) {
                    val delayMs = retryDelays.getOrElse(attempt) { retryDelays.last() }
                    val errorType = when (e) {
                        is SocketTimeoutException -> "timeout"
                        is UnknownHostException -> "DNS failure"
                        else -> "network error"
                    }
                    logRetry(name, attempt + 1, maxRetries, errorType, delayMs, remoteLogger)
                    delay(delayMs)
                    retriesAttempted++
                }
            } catch (e: Exception) {
                // Unknown error - don't retry
                Log.e(TAG, "[$name] Unexpected error, not retrying", e)
                return Result.Failure(e, 0)
            }
        }

        // All retries exhausted
        logMaxRetriesReached(name, retriesAttempted, lastError, remoteLogger)
        return Result.Failure(
            lastError ?: IllegalStateException("Unknown error after $retriesAttempted retries"),
            retriesAttempted
        )
    }

    /**
     * Handle 503 errors with exponential backoff.
     * 503 indicates server overload - we use longer backoff to give the server time to recover.
     *
     * Uses the minimum of caller's maxRetries and MAX_503_RETRIES to respect caller intent
     * while still providing reasonable backoff for server recovery.
     */
    private suspend fun <T> handle503Error(
        name: String,
        callerMaxRetries: Int,
        remoteLogger: RemoteLogger?,
        block: suspend () -> T
    ): Result<T> {
        // Honor caller's retry limit, but cap at MAX_503_RETRIES for 503-specific handling
        val effectiveMaxRetries = minOf(callerMaxRetries, MAX_503_RETRIES)
        var lastError: Throwable? = null
        var backoffMs = INITIAL_503_BACKOFF_MS

        for (attempt in 1..effectiveMaxRetries) {
            Log.w(TAG, "[$name] 503 Server Overload, attempt $attempt/$effectiveMaxRetries, backoff ${backoffMs}ms")
            remoteLogger?.log(
                LogLevel.WARN,
                TAG,
                "503 backoff retry",
                mapOf(
                    "operation" to name,
                    "attempt" to attempt.toString(),
                    "maxAttempts" to effectiveMaxRetries.toString(),
                    "backoffMs" to backoffMs.toString()
                )
            )

            delay(backoffMs)

            try {
                val result = block()
                Log.d(TAG, "[$name] Succeeded after 503 backoff (attempt $attempt)")
                return Result.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 503) {
                    lastError = e
                    // Exponential backoff: 2s, 4s, 8s, 16s, 30s (capped)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_503_BACKOFF_MS)
                } else {
                    // Different error - fail immediately
                    return Result.Failure(e, attempt)
                }
            } catch (e: IOException) {
                lastError = e
                // Network error during 503 recovery - continue with backoff
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_503_BACKOFF_MS)
            } catch (e: Exception) {
                return Result.Failure(e, attempt)
            }
        }

        // 503 retries exhausted - this triggers cascade cancellation upstream
        log503MaxRetriesReached(name, effectiveMaxRetries, lastError, remoteLogger)
        return Result.Failure(
            lastError ?: HttpException(retrofit2.Response.error<Any>(503, "".toResponseBody(null))),
            effectiveMaxRetries,
            is503Failure = true
        )
    }

    private fun logRetry(
        name: String,
        attempt: Int,
        maxAttempts: Int,
        errorType: String,
        delayMs: Long,
        remoteLogger: RemoteLogger?
    ) {
        Log.w(TAG, "[$name] $errorType, retry $attempt/$maxAttempts after ${delayMs}ms")
        remoteLogger?.log(
            LogLevel.WARN,
            "api_queue_retry",
            "API retry",
            mapOf(
                "operation" to name,
                "attempt" to attempt.toString(),
                "maxAttempts" to maxAttempts.toString(),
                "errorType" to errorType,
                "delayMs" to delayMs.toString()
            )
        )
    }

    private fun logMaxRetriesReached(
        name: String,
        retriesAttempted: Int,
        lastError: Throwable?,
        remoteLogger: RemoteLogger?
    ) {
        Log.e(TAG, "[$name] Max retries ($retriesAttempted) reached", lastError)
        remoteLogger?.log(
            LogLevel.ERROR,
            "api_queue_max_retries",
            "Max retries reached",
            mapOf(
                "operation" to name,
                "retriesAttempted" to retriesAttempted.toString(),
                "lastError" to (lastError?.message ?: "unknown")
            )
        )
    }

    private fun log503MaxRetriesReached(
        name: String,
        retriesAttempted: Int,
        lastError: Throwable?,
        remoteLogger: RemoteLogger?
    ) {
        Log.e(TAG, "[$name] 503 max retries ($retriesAttempted) reached - server overloaded", lastError)
        remoteLogger?.log(
            LogLevel.ERROR,
            "api_queue_503_max_retries",
            "503 max retries reached - server overloaded",
            mapOf(
                "operation" to name,
                "retriesAttempted" to retriesAttempted.toString(),
                "lastError" to (lastError?.message ?: "unknown")
            )
        )
    }
}

/**
 * Extension function to execute with retry and return the value directly,
 * throwing an exception on failure.
 */
suspend fun <T> RetryingApiExecutor.executeOrThrow(
    name: String,
    maxRetries: Int = 3,
    remoteLogger: RemoteLogger? = null,
    block: suspend () -> T
): T {
    return when (val result = execute(name, maxRetries, remoteLogger = remoteLogger, block = block)) {
        is RetryingApiExecutor.Result.Success -> result.value
        is RetryingApiExecutor.Result.Failure -> throw result.error
    }
}
