package com.example.rocketplan_android.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Secure storage for authentication tokens and user credentials
 * Uses DataStore for general preferences and EncryptedSharedPreferences for sensitive data
 */
class SecureStorage(private val context: Context) {

    companion object {
        // DataStore keys
        private val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
        private val USER_EMAIL_KEY = stringPreferencesKey("user_email")
        private val REMEMBER_ME_KEY = booleanPreferencesKey("remember_me")
        private val USER_ID_KEY = longPreferencesKey("user_id")
        private val COMPANY_ID_KEY = longPreferencesKey("company_id")
        private val USER_NAME_KEY = stringPreferencesKey("user_name")
        private val COMPANY_NAME_KEY = stringPreferencesKey("company_name")
        private const val OAUTH_STATE_KEY = "oauth_state"
        private const val AUTH_TOKEN_PREF_KEY = "auth_token"

        // EncryptedSharedPreferences name
        private const val ENCRYPTED_PREFS_NAME = "rocketplan_encrypted_prefs"
        private const val SAVED_PASSWORD_KEY = "saved_password"

        @Volatile
        private var INSTANCE: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureStorage(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // DataStore instance
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "rocketplan_preferences"
    )

    // Encrypted SharedPreferences for sensitive data
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        ENCRYPTED_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authTokenState = MutableStateFlow<String?>(
        encryptedPrefs.getString(AUTH_TOKEN_PREF_KEY, null)
    )

    init {
        scope.launch {
            migrateLegacyAuthToken()
        }
    }

    // ==================== Token Management ====================

    /**
     * Save authentication token
     */
    suspend fun saveAuthToken(token: String) {
        withContext(Dispatchers.IO) {
            saveAuthTokenInternal(token)
            authTokenState.value = token
        }
    }

    /**
     * Get authentication token as Flow
     */
    fun getAuthToken(): Flow<String?> {
        return authTokenState.asStateFlow()
    }

    /**
     * Get authentication token synchronously
     */
    suspend fun getAuthTokenSync(): String? {
        val current = authTokenState.value
        if (current != null) return current

        // One-time migration from legacy DataStore storage if present.
        return migrateLegacyAuthToken()
    }

    /**
     * Clear authentication token
     */
    suspend fun clearAuthToken() {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().remove(AUTH_TOKEN_PREF_KEY).apply()
            context.dataStore.edit { preferences ->
                preferences.remove(AUTH_TOKEN_KEY)
            }
            authTokenState.value = null
        }
    }

    // ==================== User Email Management ====================

    /**
     * Save user email
     */
    suspend fun saveUserEmail(email: String) {
        context.dataStore.edit { preferences ->
            preferences[USER_EMAIL_KEY] = email
        }
    }

    /**
     * Get user email
     */
    fun getUserEmail(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[USER_EMAIL_KEY]
        }
    }

    /**
     * Get user email synchronously
     */
    suspend fun getUserEmailSync(): String? {
        return getUserEmail().first()
    }

    // ==================== Remember Me ====================

    /**
     * Save Remember Me preference
     */
    suspend fun setRememberMe(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMEMBER_ME_KEY] = enabled
        }
    }

    /**
     * Get Remember Me preference
     */
    fun getRememberMe(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[REMEMBER_ME_KEY] ?: false
        }
    }

    /**
     * Get Remember Me preference synchronously
     */
    suspend fun getRememberMeSync(): Boolean {
        return getRememberMe().first()
    }

    /**
     * Save encrypted password (only when Remember Me is enabled)
     */
    fun saveEncryptedPassword(password: String) {
        encryptedPrefs.edit().putString(SAVED_PASSWORD_KEY, password).apply()
    }

    /**
     * Get encrypted password
     */
    fun getEncryptedPassword(): String? {
        return encryptedPrefs.getString(SAVED_PASSWORD_KEY, null)
    }

    /**
     * Clear encrypted password
     */
    fun clearEncryptedPassword() {
        encryptedPrefs.edit().remove(SAVED_PASSWORD_KEY).apply()
    }

    // ==================== OAuth State ====================

    /**
     * Save the pending OAuth state/nonce for callback validation.
     */
    fun saveOAuthState(state: String) {
        encryptedPrefs.edit().putString(OAUTH_STATE_KEY, state).apply()
    }

    /**
     * Get the currently stored OAuth state/nonce, if any.
     */
    fun getOAuthState(): String? = encryptedPrefs.getString(OAUTH_STATE_KEY, null)

    /**
     * Clear the stored OAuth state/nonce.
     */
    fun clearOAuthState() {
        encryptedPrefs.edit().remove(OAUTH_STATE_KEY).apply()
    }

    // ==================== User Context ====================

    suspend fun saveUserId(userId: Long) {
        context.dataStore.edit { preferences ->
            preferences[USER_ID_KEY] = userId
        }
    }

    suspend fun getUserIdSync(): Long? {
        return context.dataStore.data.map { it[USER_ID_KEY] }.first()
    }

    fun getUserId(): Flow<Long?> =
        context.dataStore.data.map { preferences -> preferences[USER_ID_KEY] }

    suspend fun clearUserId() {
        context.dataStore.edit { preferences ->
            preferences.remove(USER_ID_KEY)
        }
    }

    suspend fun saveCompanyId(companyId: Long) {
        context.dataStore.edit { preferences ->
            preferences[COMPANY_ID_KEY] = companyId
        }
    }

    suspend fun getCompanyIdSync(): Long? {
        return context.dataStore.data.map { it[COMPANY_ID_KEY] }.first()
    }

    fun getCompanyId(): Flow<Long?> =
        context.dataStore.data.map { preferences -> preferences[COMPANY_ID_KEY] }

    suspend fun clearCompanyId() {
        context.dataStore.edit { preferences ->
            preferences.remove(COMPANY_ID_KEY)
        }
    }

    // ==================== User/Company Names (for offline display) ====================

    suspend fun saveUserName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[USER_NAME_KEY] = name
        }
    }

    suspend fun getUserNameSync(): String? {
        return context.dataStore.data.map { it[USER_NAME_KEY] }.first()
    }

    suspend fun saveCompanyName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[COMPANY_NAME_KEY] = name
        }
    }

    suspend fun getCompanyNameSync(): String? {
        return context.dataStore.data.map { it[COMPANY_NAME_KEY] }.first()
    }

    // ==================== Clear All Data ====================

    /**
     * Clear all stored authentication data (logout)
     */
    suspend fun clearAll() {
        // Clear DataStore
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_TOKEN_KEY)
            preferences.remove(USER_EMAIL_KEY)
            preferences.remove(REMEMBER_ME_KEY)
            preferences.remove(USER_ID_KEY)
            preferences.remove(COMPANY_ID_KEY)
            preferences.remove(USER_NAME_KEY)
            preferences.remove(COMPANY_NAME_KEY)
        }

        // Clear EncryptedSharedPreferences
        encryptedPrefs.edit().clear().apply()
        authTokenState.value = null
    }

    /**
     * Persist token in encrypted prefs and migrate away from legacy DataStore storage.
     */
    private fun saveAuthTokenInternal(token: String) {
        encryptedPrefs.edit().putString(AUTH_TOKEN_PREF_KEY, token).apply()
    }

    private suspend fun migrateLegacyAuthToken(): String? {
        val legacyToken = context.dataStore.data.first()[AUTH_TOKEN_KEY] ?: return null
        saveAuthTokenInternal(legacyToken)
        context.dataStore.edit { prefs ->
            prefs.remove(AUTH_TOKEN_KEY)
        }
        authTokenState.value = legacyToken
        return legacyToken
    }
}
