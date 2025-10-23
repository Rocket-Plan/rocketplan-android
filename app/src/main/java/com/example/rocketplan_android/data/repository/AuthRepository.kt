package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.AuthService
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.ApiError
import com.example.rocketplan_android.data.model.CheckEmailRequest
import com.example.rocketplan_android.data.model.CheckEmailResponse
import com.example.rocketplan_android.data.model.ResetPasswordRequest
import com.example.rocketplan_android.data.model.ResetPasswordResponse
import com.example.rocketplan_android.data.model.LoginRequest
import com.example.rocketplan_android.data.model.LoginResponse
import com.example.rocketplan_android.data.model.RegisterRequest
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.flow.Flow

/**
 * Repository for authentication operations
 * Handles API calls and local storage
 */
class AuthRepository(
    private val secureStorage: SecureStorage,
    private val authService: AuthService = RetrofitClient.authService
) {

    // ==================== API Operations ====================

    /**
     * Check if an email is registered
     */
    suspend fun checkEmail(email: String): Result<CheckEmailResponse> {
        return try {
            val response = authService.checkEmail(CheckEmailRequest(email))

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val apiError = ApiError.fromHttpResponse(
                    response.code(),
                    response.errorBody()?.string()
                )
                Result.failure(Exception(apiError.displayMessage))
            }
        } catch (e: Exception) {
            val apiError = ApiError.fromException(e)
            Result.failure(Exception(apiError.displayMessage))
        }
    }

    /**
     * Login with email and password
     * Calls Laravel /auth/login endpoint
     * Returns Sanctum token on success
     */
    suspend fun signIn(email: String, password: String, rememberMe: Boolean = false): Result<LoginResponse> {
        return try {
            val response = authService.login(LoginRequest(email, password))

            if (response.isSuccessful && response.body() != null) {
                val loginResponse = response.body()!!

                // Save token (format: "1|plainTextTokenString...")
                saveAuthToken(loginResponse.token)
                secureStorage.saveUserEmail(email)

                // Save credentials if Remember Me is enabled
                if (rememberMe) {
                    secureStorage.setRememberMe(true)
                    secureStorage.saveEncryptedPassword(password)
                }

                Result.success(loginResponse)
            } else {
                val errorBody = response.errorBody()?.string()
                val apiError = when (response.code()) {
                    422 -> {
                        // Check if it's an authentication failure vs validation error
                        if (errorBody?.contains("credentials", ignoreCase = true) == true ||
                            errorBody?.contains("password", ignoreCase = true) == true) {
                            ApiError.forAuthScenario("invalid_credentials")
                        } else {
                            ApiError.fromHttpResponse(422, errorBody)
                        }
                    }
                    429 -> ApiError.RateLimitError()
                    else -> ApiError.fromHttpResponse(response.code(), errorBody)
                }
                Result.failure(Exception(apiError.displayMessage))
            }
        } catch (e: Exception) {
            val apiError = ApiError.fromException(e)
            Result.failure(Exception(apiError.displayMessage))
        }
    }

    /**
     * Register a new user account
     * Calls Laravel /auth/register endpoint
     * Returns Sanctum token on success
     */
    suspend fun signUp(email: String, password: String, passwordConfirmation: String): Result<LoginResponse> {
        return try {
            val response = authService.register(RegisterRequest(email, password, passwordConfirmation))

            if (response.isSuccessful && response.body() != null) {
                val registerResponse = response.body()!!

                // Save token for newly registered user
                saveAuthToken(registerResponse.token)
                secureStorage.saveUserEmail(email)

                // Reset Remember Me related preferences on fresh sign up
                secureStorage.setRememberMe(false)
                secureStorage.clearEncryptedPassword()

                Result.success(registerResponse)
            } else {
                val errorBody = response.errorBody()?.string()
                val apiError = when (response.code()) {
                    422 -> ApiError.fromHttpResponse(422, errorBody)
                    409 -> ApiError.forAuthScenario("email_exists")
                    else -> ApiError.fromHttpResponse(response.code(), errorBody)
                }
                Result.failure(Exception(apiError.displayMessage))
            }
        } catch (e: Exception) {
            val apiError = ApiError.fromException(e)
            Result.failure(Exception(apiError.displayMessage))
        }
    }

    /**
     * Request password reset
     */
    suspend fun resetPassword(email: String): Result<ResetPasswordResponse> {
        return try {
            val response = authService.resetPassword(ResetPasswordRequest(email))

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val apiError = ApiError.fromHttpResponse(
                    response.code(),
                    response.errorBody()?.string()
                )
                Result.failure(Exception(apiError.displayMessage))
            }
        } catch (e: Exception) {
            val apiError = ApiError.fromException(e)
            Result.failure(Exception(apiError.displayMessage))
        }
    }

    // Note: Google OAuth Sign-In is now handled via Chrome Custom Tabs + deep link callback
    // See MainActivity.handleOAuthCallback() for OAuth token handling
    // No repository method needed as the backend returns JWT token directly via deep link

    // ==================== Token Management ====================

    /**
     * Save authentication token
     */
    suspend fun saveAuthToken(token: String) {
        secureStorage.saveAuthToken(token)
        RetrofitClient.setAuthToken(token)
    }

    /**
     * Get authentication token
     */
    fun getAuthToken(): Flow<String?> {
        return secureStorage.getAuthToken()
    }

    /**
     * Get authentication token synchronously
     */
    suspend fun getAuthTokenSync(): String? {
        return secureStorage.getAuthTokenSync()
    }

    /**
     * Check if user is logged in
     */
    suspend fun isLoggedIn(): Boolean {
        val token = getAuthTokenSync()
        if (token != null) {
            RetrofitClient.setAuthToken(token)
            return true
        }
        return false
    }

    /**
     * Clear authentication token
     */
    suspend fun clearAuthToken() {
        secureStorage.clearAuthToken()
        RetrofitClient.setAuthToken(null)
    }

    // ==================== User Data ====================

    /**
     * Get saved user email
     */
    suspend fun getSavedEmail(): String? {
        return secureStorage.getUserEmailSync()
    }

    /**
     * Get saved email as Flow
     */
    fun getSavedEmailFlow(): Flow<String?> {
        return secureStorage.getUserEmail()
    }

    // ==================== Remember Me ====================

    /**
     * Check if Remember Me is enabled
     */
    suspend fun isRememberMeEnabled(): Boolean {
        return secureStorage.getRememberMeSync()
    }

    /**
     * Get saved credentials for auto-login
     */
    suspend fun getSavedCredentials(): Pair<String?, String?> {
        val email = secureStorage.getUserEmailSync()
        val password = secureStorage.getEncryptedPassword()
        return Pair(email, password)
    }

    // ==================== Biometric ====================

    /**
     * Check if biometric authentication is enabled
     */
    suspend fun isBiometricEnabled(): Boolean {
        return secureStorage.getBiometricEnabledSync()
    }

    /**
     * Enable/disable biometric authentication
     */
    suspend fun setBiometricEnabled(enabled: Boolean) {
        secureStorage.setBiometricEnabled(enabled)
    }

    // ==================== Logout ====================

    /**
     * Logout user and clear all data
     */
    suspend fun logout() {
        secureStorage.clearAll()
        RetrofitClient.setAuthToken(null)
    }
}
