package com.example.rocketplan_android.data.repository

import android.util.Log
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
import com.example.rocketplan_android.data.model.AuthSession
import com.example.rocketplan_android.data.model.CurrentUserResponse
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
    suspend fun signIn(email: String, password: String, rememberMe: Boolean = false): Result<AuthSession> {
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

                val userContextResult = refreshUserContext()
                if (userContextResult.isFailure) {
                    return Result.failure(userContextResult.exceptionOrNull()!!)
                }
                val currentUser = userContextResult.getOrNull()!!

                Result.success(
                    AuthSession(
                        token = loginResponse.token,
                        user = currentUser
                    )
                )
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
    suspend fun signUp(email: String, password: String, passwordConfirmation: String): Result<AuthSession> {
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

                val userContextResult = refreshUserContext()
                if (userContextResult.isFailure) {
                    return Result.failure(userContextResult.exceptionOrNull()!!)
                }
                val currentUser = userContextResult.getOrNull()!!

                Result.success(
                    AuthSession(
                        token = registerResponse.token,
                        user = currentUser
                    )
                )
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
            return try {
                ensureUserContext()
                true
            } catch (t: Throwable) {
                false
            }
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

    suspend fun refreshUserContext(): Result<CurrentUserResponse> {
        return try {
            val response = authService.getCurrentUser()
            val envelope = response.body()
            val currentUser = envelope?.data

            if (response.isSuccessful && currentUser != null) {
                val primaryCompanyId = currentUser.getPrimaryCompanyId()
                Log.d("AuthRepository", "refreshUserContext - userId=${currentUser.id}, companyId=${currentUser.companyId}, primaryCompanyId=$primaryCompanyId, companies=${currentUser.companies?.map { it.id }}, email=${currentUser.email}")
                if (currentUser.id > 0L) {
                    secureStorage.saveUserId(currentUser.id)
                } else {
                    secureStorage.clearUserId()
                }
                if (primaryCompanyId != null) {
                    Log.d("AuthRepository", "Saving primaryCompanyId=$primaryCompanyId")
                    secureStorage.saveCompanyId(primaryCompanyId)
                } else {
                    Log.w("AuthRepository", "No company found in API response - clearing stored companyId")
                    secureStorage.clearCompanyId()
                }
                Result.success(currentUser)
            } else {
                val apiError = ApiError.fromHttpResponse(response.code(), response.errorBody()?.string())
                Result.failure(Exception(apiError.displayMessage))
            }
        } catch (e: Exception) {
            val apiError = ApiError.fromException(e)
            Result.failure(Exception(apiError.displayMessage))
        }
    }

    suspend fun ensureUserContext() {
        val userId = secureStorage.getUserIdSync()
        if (userId == null || userId <= 0L) {
            refreshUserContext().getOrElse { error -> throw error }
        }
    }

    suspend fun getStoredUserId(): Long? = secureStorage.getUserIdSync()?.takeIf { it > 0L }

    suspend fun getStoredCompanyId(): Long? = secureStorage.getCompanyIdSync()

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
