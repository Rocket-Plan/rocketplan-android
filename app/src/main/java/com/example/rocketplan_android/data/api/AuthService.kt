package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.AppVersionResponse
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
import com.example.rocketplan_android.data.model.SmsSendVerificationRequest
import com.example.rocketplan_android.data.model.SmsVerifyCodeRequest
import com.example.rocketplan_android.data.model.UpdateUserRequest
import com.example.rocketplan_android.data.model.UpdateUserResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

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

    /**
     * Send SMS verification code to the given phone number (E164 format).
     * Server returns 204 No Content on success.
     */
    @POST("api/auth/sms-send-verification")
    suspend fun sendSmsVerification(@Body request: SmsSendVerificationRequest): Response<Unit>

    /**
     * Verify the SMS code entered by the user.
     * Server returns 204 No Content on success.
     */
    @POST("api/auth/sms-verify-code")
    suspend fun verifySmsCode(@Body request: SmsVerifyCodeRequest): Response<Unit>

    /**
     * Update the current user's profile (name, phone, etc.)
     */
    @PUT("api/users/{userId}")
    suspend fun updateUser(
        @Path("userId") userId: Long,
        @Body request: UpdateUserRequest
    ): Response<UpdateUserResponse>

    /**
     * Check app version and flavor status
     * Endpoint: GET /api/app-version?platform=Android&version=X&flavor=Y
     */
    @GET("api/app-version")
    suspend fun checkAppVersion(
        @Query("platform") platform: String = "Android",
        @Query("version") version: String,
        @Query("flavor") flavor: String? = null
    ): Response<AppVersionResponse>
}
