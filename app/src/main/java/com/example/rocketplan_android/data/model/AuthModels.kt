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
 * Response model for login
 * Laravel returns: { "token": "1|plainTextTokenString..." }
 */
data class LoginResponse(
    val token: String
)

/**
 * User data model
 */
data class User(
    @SerializedName("id")
    val id: String,
    @SerializedName("email")
    val email: String,
    @SerializedName("firstName")
    val firstName: String? = null,
    @SerializedName("lastName")
    val lastName: String? = null,
    @SerializedName("company")
    val company: Company? = null
)

/**
 * Company data model
 */
data class Company(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String?,
    @SerializedName("logoUrl")
    val logoUrl: String? = null
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
 * Request model for Google Sign-In
 * Sends Google ID token to backend for verification
 */
data class GoogleSignInRequest(
    @SerializedName("id_token")
    val idToken: String
)

/**
 * Response model for Google Sign-In
 * Returns authentication token (same as regular login)
 */
data class GoogleSignInResponse(
    val token: String,
    val user: User? = null
)
