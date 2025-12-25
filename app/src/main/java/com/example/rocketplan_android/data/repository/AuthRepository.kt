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
import com.example.rocketplan_android.data.model.SetActiveCompanyRequest
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Repository for authentication operations
 * Handles API calls and local storage
 */
class AuthRepository(
    private val secureStorage: SecureStorage,
    private val remoteLogger: RemoteLogger? = null,
    private val localDataServiceProvider: () -> LocalDataService = { LocalDataService.getInstance() },
    private val authService: AuthService = RetrofitClient.authService
) {
    private val localDataService: LocalDataService by lazy { localDataServiceProvider() }

    companion object {
        private const val TAG = "AuthRepository"
    }

    // ==================== API Operations ====================

    /**
     * Check if an email is registered
     */
    suspend fun checkEmail(email: String): Result<CheckEmailResponse> {
        return try {
            android.util.Log.d("AuthRepository", "checkEmail: calling API for $email")
            val response = authService.checkEmail(CheckEmailRequest(email))
            android.util.Log.d("AuthRepository", "checkEmail: response code=${response.code()}")

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
            android.util.Log.e("AuthRepository", "checkEmail EXCEPTION: ${e.javaClass.name}: ${e.message}", e)
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
            val cachedCompanyId = secureStorage.getCompanyIdSync()
            if (cachedCompanyId != null) {
                localDataService.setCurrentCompanyId(cachedCompanyId)
                RetrofitClient.setCompanyId(cachedCompanyId)
            } else {
                RetrofitClient.setCompanyId(null)
            }
            return hasUserId || cachedCompanyId != null
        }
        return false
    }

    /**
     * Clear authentication token and company context
     */
    suspend fun clearAuthToken() {
        secureStorage.clearAuthToken()
        RetrofitClient.setAuthToken(null)
        RetrofitClient.setCompanyId(null)
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
                // Cache user name for offline display
                val userName = listOfNotNull(currentUser.firstName, currentUser.lastName)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { currentUser.email }
                // Always save (even empty) to clear stale data when user context changes
                secureStorage.saveUserName(userName)
                if (selectedCompanyId != null) {
                    Log.d("AuthRepository", "Saving companyId=$selectedCompanyId")
                    // Cache company name for offline display
                    // Try to find by ID first, then fall back to any available company name
                    val selectedCompany = currentUser.companies?.firstOrNull { it.id == selectedCompanyId }
                        ?: currentUser.company?.takeIf { it.id == selectedCompanyId }
                        ?: currentUser.company // Fallback: use primary company if ID lookup fails
                    val companyName = selectedCompany?.name?.takeIf { it.isNotBlank() }
                    if (companyName != null) {
                        secureStorage.saveCompanyName(companyName)
                    } else {
                        // Clear stale company name if we can't find the current one
                        Log.w("AuthRepository", "Could not find company name for companyId=$selectedCompanyId, clearing cached name")
                        secureStorage.saveCompanyName("")
                    }
                    // Notify the backend which company is active for this session
                    runCatching {
                        val activeCompanyResponse = authService.setActiveCompany(SetActiveCompanyRequest(selectedCompanyId))
                        if (activeCompanyResponse.isSuccessful) {
                            Log.d("AuthRepository", "Active company set on server: $selectedCompanyId")
                        } else {
                            Log.w("AuthRepository", "Failed to set active company on server: ${activeCompanyResponse.code()}")
                        }
                    }.onFailure { e ->
                        Log.w("AuthRepository", "Failed to set active company on server", e)
                    }
                    secureStorage.saveCompanyId(selectedCompanyId)
                    localDataService.setCurrentCompanyId(selectedCompanyId)
                    RetrofitClient.setCompanyId(selectedCompanyId)
                    remoteLogger?.log(
                        LogLevel.INFO,
                        TAG,
                        "Company context set",
                        mapOf(
                            "companyId" to selectedCompanyId.toString(),
                            "userId" to currentUser.id.toString(),
                            "source" to "refreshUserContext"
                        )
                    )
                } else {
                    Log.w("AuthRepository", "No company found in API response - clearing stored companyId and name")
                    secureStorage.clearCompanyId()
                    secureStorage.saveCompanyName("") // Clear stale company name
                    localDataService.clearCurrentCompanyId()
                    RetrofitClient.setCompanyId(null)
                    remoteLogger?.log(
                        LogLevel.WARN,
                        TAG,
                        "Company context cleared - no company in API response",
                        mapOf("source" to "refreshUserContext")
                    )
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

    suspend fun getStoredUserName(): String? = secureStorage.getUserNameSync()

    suspend fun getStoredCompanyName(): String? = secureStorage.getCompanyNameSync()

    fun observeCompanyId(): Flow<Long?> = secureStorage.getCompanyId()

    /**
     * Set the active company for API requests.
     * This notifies the backend which company context to use.
     */
    suspend fun setActiveCompany(companyId: Long): Result<Unit> {
        return try {
            Log.d("AuthRepository", "setActiveCompany: notifying server of companyId=$companyId")
            val response = authService.setActiveCompany(SetActiveCompanyRequest(companyId))
            if (response.isSuccessful) {
                Log.d("AuthRepository", "setActiveCompany: server acknowledged companyId=$companyId")
                secureStorage.saveCompanyId(companyId)
                localDataService.setCurrentCompanyId(companyId)
                RetrofitClient.setCompanyId(companyId)
                remoteLogger?.log(
                    LogLevel.INFO,
                    TAG,
                    "Company switched",
                    mapOf("companyId" to companyId.toString(), "source" to "setActiveCompany")
                )
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("AuthRepository", "setActiveCompany: failed ${response.code()} - $errorBody")
                // Still save locally even if server call fails, so we can retry later
                secureStorage.saveCompanyId(companyId)
                localDataService.setCurrentCompanyId(companyId)
                RetrofitClient.setCompanyId(companyId)
                remoteLogger?.log(
                    LogLevel.WARN,
                    TAG,
                    "Company switch failed on server but saved locally",
                    mapOf("companyId" to companyId.toString(), "httpCode" to response.code().toString())
                )
                Result.failure(Exception("Failed to set active company: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "setActiveCompany: exception", e)
            // Still save locally even if server call fails
            secureStorage.saveCompanyId(companyId)
            localDataService.setCurrentCompanyId(companyId)
            RetrofitClient.setCompanyId(companyId)
            remoteLogger?.log(
                LogLevel.WARN,
                TAG,
                "Company switch exception but saved locally",
                mapOf("companyId" to companyId.toString(), "error" to (e.message ?: "unknown"))
            )
            Result.failure(e)
        }
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
        remoteLogger?.log(LogLevel.INFO, TAG, "User logged out")
        secureStorage.clearAll()
        localDataService.clearCurrentCompanyId()
        RetrofitClient.setAuthToken(null)
        RetrofitClient.setCompanyId(null)
    }
}
