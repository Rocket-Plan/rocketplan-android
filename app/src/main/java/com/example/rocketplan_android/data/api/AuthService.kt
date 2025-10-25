package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CheckEmailRequest
import com.example.rocketplan_android.data.model.CheckEmailResponse
import com.example.rocketplan_android.data.model.CurrentUserEnvelope
import com.example.rocketplan_android.data.model.ResetPasswordRequest
import com.example.rocketplan_android.data.model.ResetPasswordResponse
import com.example.rocketplan_android.data.model.LoginRequest
import com.example.rocketplan_android.data.model.LoginResponse
import com.example.rocketplan_android.data.model.RegisterRequest
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
     * Request password reset
     */
    @POST("api/auth/forgot-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<ResetPasswordResponse>

    /**
     * Fetch the currently authenticated user's profile.
     */
    @GET("api/auth/user")
    suspend fun getCurrentUser(): Response<CurrentUserEnvelope>
}
