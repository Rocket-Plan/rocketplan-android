package com.example.rocketplan_android.util

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Utility functions for extracting meaningful error information from network exceptions.
 */
object NetworkErrorUtils {

    /**
     * Extracts a user-friendly error message from a network exception.
     */
    fun getErrorMessage(error: Throwable): String {
        return when (error) {
            is HttpException -> getHttpErrorMessage(error)
            is SocketTimeoutException -> "Request timed out. Please try again."
            is UnknownHostException -> "Unable to reach server. Check your connection."
            is IOException -> "Network error: ${error.message ?: "Unknown"}"
            else -> error.message ?: "An unknown error occurred"
        }
    }

    /**
     * Extracts HTTP status code from an exception, or null if not an HTTP error.
     */
    fun getHttpStatusCode(error: Throwable): Int? {
        return (error as? HttpException)?.code()
    }

    /**
     * Checks if the error is a client error (4xx).
     */
    fun isClientError(error: Throwable): Boolean {
        val code = getHttpStatusCode(error) ?: return false
        return code in 400..499
    }

    /**
     * Checks if the error is a server error (5xx).
     */
    fun isServerError(error: Throwable): Boolean {
        val code = getHttpStatusCode(error) ?: return false
        return code in 500..599
    }

    /**
     * Checks if the error is retryable (network issues or server errors).
     */
    fun isRetryable(error: Throwable): Boolean {
        return when {
            error is SocketTimeoutException -> true
            error is UnknownHostException -> true
            error is IOException -> true
            isServerError(error) -> true
            else -> false
        }
    }

    /**
     * Checks if error indicates network unavailability.
     */
    fun isNetworkError(error: Throwable): Boolean {
        return error is UnknownHostException ||
            error is SocketTimeoutException ||
            (error is IOException && error !is HttpException)
    }

    private fun getHttpErrorMessage(error: HttpException): String {
        val code = error.code()
        val body = try {
            error.response()?.errorBody()?.string()?.take(200)
        } catch (e: Exception) {
            null
        }

        val baseMessage = when (code) {
            400 -> "Invalid request"
            401 -> "Authentication required"
            403 -> "Access denied"
            404 -> "Not found"
            409 -> "Conflict with existing data"
            422 -> "Validation error"
            429 -> "Too many requests"
            500 -> "Server error"
            502 -> "Server temporarily unavailable"
            503 -> "Service unavailable"
            else -> "HTTP error $code"
        }

        return if (body.isNullOrBlank()) baseMessage else "$baseMessage: $body"
    }
}

/**
 * Extension to get detailed error info for logging.
 */
fun Throwable.toDetailedErrorString(): String {
    val errorType = this::class.simpleName ?: "Unknown"
    val httpCode = NetworkErrorUtils.getHttpStatusCode(this)?.let { " (HTTP $it)" } ?: ""
    return "$errorType$httpCode: ${message ?: "No message"}"
}
