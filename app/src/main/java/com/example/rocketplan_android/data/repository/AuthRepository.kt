package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.data.api.AuthService
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.ApiError
import com.example.rocketplan_android.data.model.ApiErrorException
import com.example.rocketplan_android.data.model.CheckEmailRequest
import com.example.rocketplan_android.data.model.CheckEmailResponse
import com.example.rocketplan_android.data.model.ResetPasswordRequest
import com.example.rocketplan_android.data.model.ResetPasswordResponse
import com.example.rocketplan_android.data.model.LoginRequest
import com.example.rocketplan_android.data.model.LoginResponse
import com.example.rocketplan_android.data.model.RegisterRequest
import com.example.rocketplan_android.data.model.AuthSession
import com.example.rocketplan_android.data.model.CurrentUserResponse
import com.example.rocketplan_android.data.model.Company
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.flow.Flow
import java.util.UUID

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

                // Save credentials if Remember Me is enabled, otherwise clear stored secrets
                if (rememberMe) {
                    secureStorage.setRememberMe(true)
                    secureStorage.saveEncryptedPassword(password)
                } else {
                    secureStorage.setRememberMe(false)
                    secureStorage.clearEncryptedPassword()
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
            val result = refreshUserContext()
            if (result.isSuccess) {
                return true
            }

            val error = result.exceptionOrNull()
            val isAuthError = (error as? ApiErrorException)?.apiError is ApiError.AuthenticationError
            if (isAuthError) {
                return false
            }

            // If we have cached identity, allow offline access and let sync recover when online.
            val hasUserId = secureStorage.getUserIdSync()?.let { it > 0L } == true
            val hasCompanyId = secureStorage.getCompanyIdSync() != null
            return hasUserId || hasCompanyId
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

    /**
     * Create and persist an OAuth state/nonce for callback validation.
     */
    fun createOAuthState(): String {
        val state = UUID.randomUUID().toString()
        secureStorage.saveOAuthState(state)
        return state
    }

    fun getStoredOAuthState(): String? = secureStorage.getOAuthState()

    fun clearOAuthState() {
        secureStorage.clearOAuthState()
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
                val storedCompanyId = secureStorage.getCompanyIdSync()
                val availableCompanyIds = buildSet {
                    currentUser.companyId?.let { add(it) }
                    currentUser.companies?.forEach { add(it.id) }
                }
                val selectedCompanyId = when {
                    storedCompanyId != null && availableCompanyIds.contains(storedCompanyId) -> storedCompanyId
                    else -> primaryCompanyId
                }
                Log.d(
                    "AuthRepository",
                    "refreshUserContext - userId=${currentUser.id}, companyId=${currentUser.companyId}, primaryCompanyId=$primaryCompanyId, storedCompanyId=$storedCompanyId, selectedCompanyId=$selectedCompanyId, companies=${currentUser.companies?.map { it.id }}, email=${currentUser.email}"
                )
                if (currentUser.id > 0L) {
                    secureStorage.saveUserId(currentUser.id)
                } else {
                    secureStorage.clearUserId()
                }
                if (selectedCompanyId != null) {
                    Log.d("AuthRepository", "Saving companyId=$selectedCompanyId")
                    secureStorage.saveCompanyId(selectedCompanyId)
                } else {
                    Log.w("AuthRepository", "No company found in API response - clearing stored companyId")
                    secureStorage.clearCompanyId()
                }
                Result.success(currentUser)
            } else {
                val apiError = ApiError.fromHttpResponse(response.code(), response.errorBody()?.string())
                Result.failure(ApiErrorException(apiError))
            }
        } catch (e: Exception) {
            val apiError = ApiError.fromException(e)
            Result.failure(ApiErrorException(apiError))
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

    fun observeCompanyId(): Flow<Long?> = secureStorage.getCompanyId()

    suspend fun setActiveCompany(companyId: Long) {
        secureStorage.saveCompanyId(companyId)
    }

    /**
     * Fetch the current user's companies from the API.
     */
    suspend fun getUserCompanies(): Result<List<Company>> {
        return try {
            val response = authService.getCurrentUser()
            val envelope = response.body()
            val currentUser = envelope?.data
            if (response.isSuccessful && currentUser != null) {
                Result.success(currentUser.companies.orEmpty())
            } else {
                val apiError = ApiError.fromHttpResponse(response.code(), response.errorBody()?.string())
                Result.failure(Exception(apiError.displayMessage))
            }
        } catch (e: Exception) {
            val apiError = ApiError.fromException(e)
            Result.failure(Exception(apiError.displayMessage))
        }
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

    // ==================== Logout ====================

    /**
     * Logout user and clear all data
     */
    suspend fun logout() {
        secureStorage.clearAll()
        RetrofitClient.setAuthToken(null)
    }
}
