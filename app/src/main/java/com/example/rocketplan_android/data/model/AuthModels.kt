package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

/**
 * Request model for checking if an email is registered
 */
data class CheckEmailRequest(
    val email: String
)

/**
 * Response model for email check
 */
data class CheckEmailResponse(
    val registered: Boolean
)

/**
 * Request model for login
 * Matches Laravel LoginRequest validation
 */
data class LoginRequest(
    val email: String,
    val password: String
)

/**
 * Request model for registration
 * Matches Laravel RegisterRequest validation
 */
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerializedName("password_confirmation")
    val passwordConfirmation: String
)

/**
 * Response model for login
 * Laravel returns: { "token": "1|plainTextTokenString..." }
 */
data class LoginResponse(
    val token: String
)

/**
 * Aggregated authentication session details returned to the app after login/sign-up.
 * Combines the Sanctum token with the freshly fetched user context so callers can
 * immediately access identifiers like userId/companyId without another lookup.
 */
data class AuthSession(
    val token: String,
    val user: CurrentUserResponse
)

/**
 * User data model
 */
data class User(
    @SerializedName("id")
    val id: Long,
    @SerializedName("email")
    val email: String,
    @SerializedName("first_name")
    val firstName: String? = null,
    @SerializedName("last_name")
    val lastName: String? = null,
    @SerializedName("company_id")
    val companyId: Long? = null,
    @SerializedName("company")
    val company: Company? = null
)

/**
 * Company data model
 */
data class Company(
    @SerializedName("id")
    val id: Long,
    @SerializedName("name")
    val name: String?,
    @SerializedName("logoUrl")
    val logoUrl: String? = null
)

data class CurrentUserResponse(
    @SerializedName("id")
    val id: Long,
    @SerializedName("email")
    val email: String,
    @SerializedName("first_name")
    val firstName: String? = null,
    @SerializedName("last_name")
    val lastName: String? = null,
    @SerializedName("company_id")
    val companyId: Long? = null,
    @SerializedName("company")
    val company: Company? = null
)

/**
 * Wrapper for current user endpoint responses.
 */
data class CurrentUserEnvelope(
    @SerializedName("data")
    val data: CurrentUserResponse
)

/**
 * Request model for password reset
 */
data class ResetPasswordRequest(
    val email: String
)

/**
 * Response model for password reset
 */
data class ResetPasswordResponse(
    val message: String
)

/**
 * OAuth callback data parsed from deep link
 * Format: rocketplan://oauth2/redirect?token={JWT_TOKEN}&status=200
 */
data class OAuthCallbackData(
    val token: String,
    val status: Int
)
