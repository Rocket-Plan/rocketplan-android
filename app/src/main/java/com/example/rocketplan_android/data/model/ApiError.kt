package com.example.rocketplan_android.data.model

import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Sealed class representing different types of API errors
 * Provides user-friendly error messages
 */
sealed class ApiError(val message: String, val displayMessage: String) {

    /**
     * Network-related errors (no internet, timeout, etc.)
     */
    data class NetworkError(
        val originalMessage: String = "Network connection failed"
    ) : ApiError(
        message = originalMessage,
        displayMessage = "Unable to connect. Please check your internet connection and try again."
    )

    /**
     * Authentication errors (401 Unauthorized)
     */
    data class AuthenticationError(
        val originalMessage: String = "Authentication failed"
    ) : ApiError(
        message = originalMessage,
        displayMessage = "Your session has expired. Please sign in again."
    )

    /**
     * Validation errors (422 Unprocessable Entity)
     */
    data class ValidationError(
        val field: String?,
        val originalMessage: String
    ) : ApiError(
        message = originalMessage,
        displayMessage = originalMessage
    )

    /**
     * Not found errors (404)
     */
    data class NotFoundError(
        val originalMessage: String = "Resource not found"
    ) : ApiError(
        message = originalMessage,
        displayMessage = "The requested resource could not be found. Please try again later."
    )

    /**
     * Rate limiting errors (429 Too Many Requests)
     */
    data class RateLimitError(
        val retryAfter: Int? = null
    ) : ApiError(
        message = "Too many requests",
        displayMessage = if (retryAfter != null) {
            "Too many attempts. Please wait $retryAfter seconds and try again."
        } else {
            "Too many attempts. Please try again later."
        }
    )

    /**
     * Server errors (5xx)
     */
    data class ServerError(
        val code: Int,
        val originalMessage: String = "Server error"
    ) : ApiError(
        message = originalMessage,
        displayMessage = "We're experiencing technical difficulties. Please try again later."
    )

    /**
     * Timeout errors
     */
    data class TimeoutError(
        val originalMessage: String = "Request timed out"
    ) : ApiError(
        message = originalMessage,
        displayMessage = "The request took too long. Please check your connection and try again."
    )

    /**
     * Unknown/generic errors
     */
    data class UnknownError(
        val originalMessage: String = "An unexpected error occurred"
    ) : ApiError(
        message = originalMessage,
        displayMessage = "Something went wrong. Please try again."
    )

    companion object {
        /**
         * Parse error from HTTP response
         */
        fun fromHttpResponse(code: Int, errorBody: String?): ApiError {
            return when (code) {
                401 -> AuthenticationError(errorBody ?: "Unauthorized")
                404 -> NotFoundError(errorBody ?: "Not found")
                422 -> parseValidationError(errorBody)
                429 -> RateLimitError()
                in 500..599 -> ServerError(code, errorBody ?: "Server error")
                else -> UnknownError(errorBody ?: "Unknown error (HTTP $code)")
            }
        }

        /**
         * Parse error from exception
         */
        fun fromException(exception: Throwable): ApiError {
            return when (exception) {
                is UnknownHostException -> NetworkError("No internet connection")
                is SocketTimeoutException -> TimeoutError("Connection timed out")
                is java.io.IOException -> NetworkError("Network error: ${exception.message}")
                else -> UnknownError(exception.message ?: "An unexpected error occurred")
            }
        }

        /**
         * Parse Laravel validation errors
         */
        private fun parseValidationError(errorBody: String?): ValidationError {
            if (errorBody.isNullOrBlank()) {
                return ValidationError(null, "Invalid input. Please check your information.")
            }

            return try {
                val json = JSONObject(errorBody)

                // Try to extract validation errors
                if (json.has("errors")) {
                    val errors = json.getJSONObject("errors")

                    // Priority fields for better error messages
                    val priorityFields = listOf("email", "password", "password_confirmation", "auth")

                    for (field in priorityFields) {
                        if (errors.has(field)) {
                            val messages = errors.optJSONArray(field)
                            if (messages != null && messages.length() > 0) {
                                return ValidationError(field, messages.getString(0))
                            }
                        }
                    }

                    // Get first error if no priority field found
                    val keys = errors.keys()
                    if (keys.hasNext()) {
                        val field = keys.next()
                        val messages = errors.optJSONArray(field)
                        if (messages != null && messages.length() > 0) {
                            return ValidationError(field, messages.getString(0))
                        }
                    }
                }

                // Try to extract general message
                if (json.has("message")) {
                    return ValidationError(null, json.getString("message"))
                }

                ValidationError(null, "Invalid input. Please check your information.")
            } catch (e: Exception) {
                ValidationError(null, errorBody)
            }
        }

        /**
         * Create a user-friendly error message for common auth scenarios
         */
        fun forAuthScenario(scenario: String, originalError: String? = null): ApiError {
            return when (scenario) {
                "invalid_credentials" -> ValidationError(
                    "auth",
                    "The email or password you entered is incorrect. Please try again."
                )
                "email_exists" -> ValidationError(
                    "email",
                    "An account with this email already exists. Please sign in instead."
                )
                "weak_password" -> ValidationError(
                    "password",
                    "Password must be at least 8 characters long."
                )
                "passwords_mismatch" -> ValidationError(
                    "password_confirmation",
                    "Passwords don't match. Please try again."
                )
                else -> UnknownError(originalError ?: "Authentication failed")
            }
        }
    }
}
