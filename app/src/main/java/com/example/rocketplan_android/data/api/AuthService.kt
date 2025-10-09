package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CheckEmailRequest
import com.example.rocketplan_android.data.model.CheckEmailResponse
import com.example.rocketplan_android.data.model.ResetPasswordRequest
import com.example.rocketplan_android.data.model.ResetPasswordResponse
import com.example.rocketplan_android.data.model.LoginRequest
import com.example.rocketplan_android.data.model.LoginResponse
import com.example.rocketplan_android.data.model.GoogleSignInRequest
import com.example.rocketplan_android.data.model.GoogleSignInResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit service interface for authentication API endpoints
 * Matches Laravel API routes
 */
interface AuthService {

    /**
     * Check if an email is registered
     */
    @POST("auth/check-email")
    suspend fun checkEmail(@Body request: CheckEmailRequest): Response<CheckEmailResponse>

    /**
     * Login with email and password
     * Endpoint: POST /auth/login
     * Returns Sanctum token on success
     */
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    /**
     * Request password reset
     */
    @POST("auth/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<ResetPasswordResponse>

    /**
     * Sign in with Google
     * Endpoint: POST /auth/google
     * Sends Google ID token to backend for verification
     * Returns Sanctum token on success
     */
    @POST("auth/google")
    suspend fun googleSignIn(@Body request: GoogleSignInRequest): Response<GoogleSignInResponse>
}
