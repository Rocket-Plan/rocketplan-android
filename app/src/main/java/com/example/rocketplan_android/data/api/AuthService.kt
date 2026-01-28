package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CheckEmailRequest
import com.example.rocketplan_android.data.model.CheckEmailResponse
import com.example.rocketplan_android.data.model.CurrentUserEnvelope
import com.example.rocketplan_android.data.model.GoogleAuthRequest
import com.example.rocketplan_android.data.model.GoogleAuthResponse
import com.example.rocketplan_android.data.model.ResetPasswordRequest
import com.example.rocketplan_android.data.model.ResetPasswordResponse
import com.example.rocketplan_android.data.model.LoginRequest
import com.example.rocketplan_android.data.model.LoginResponse
import com.example.rocketplan_android.data.model.RegisterRequest
import com.example.rocketplan_android.data.model.SetActiveCompanyRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit service interface for authentication API endpoints
 * Matches Laravel API routes
 */
interface AuthService {

    /**
     * Check if an email is registered
     */
    @POST("api/auth/email-check")
    suspend fun checkEmail(@Body request: CheckEmailRequest): Response<CheckEmailResponse>

    /**
     * Login with email and password
     * Endpoint: POST /api/auth/login
     * Returns Sanctum token on success
     */
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    /**
     * Register new user with email and password
     * Endpoint: POST /api/auth/register
     * Returns Sanctum token on success
     */
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<LoginResponse>

    /**
     * Authenticate with Google ID token
     * Endpoint: POST /api/auth/google
     * Backend verifies the Google ID token and returns app JWT
     */
    @POST("api/auth/google")
    suspend fun authenticateWithGoogle(@Body request: GoogleAuthRequest): Response<GoogleAuthResponse>

    /**
     * Request password reset
     */
    @POST("api/auth/forgot-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<ResetPasswordResponse>

    /**
     * Fetch the currently authenticated user's profile.
     */
    @GET("api/auth/user")
    suspend fun getCurrentUser(): Response<CurrentUserEnvelope>

    /**
     * Set the active company for the current session.
     * This tells the backend which company context to use for API requests.
     */
    @POST("api/active-company")
    suspend fun setActiveCompany(@Body request: SetActiveCompanyRequest): Response<Unit>
}
