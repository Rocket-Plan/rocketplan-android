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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
        private val BIOMETRIC_ENABLED_KEY = booleanPreferencesKey("biometric_enabled")
        private val USER_ID_KEY = longPreferencesKey("user_id")
        private val COMPANY_ID_KEY = longPreferencesKey("company_id")
        private const val OAUTH_STATE_KEY = "oauth_state"

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

    // ==================== Token Management ====================

    /**
     * Save authentication token
     */
    suspend fun saveAuthToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_TOKEN_KEY] = token
        }
    }

    /**
     * Get authentication token as Flow
     */
    fun getAuthToken(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[AUTH_TOKEN_KEY]
        }
    }

    /**
     * Get authentication token synchronously
     */
    suspend fun getAuthTokenSync(): String? {
        return getAuthToken().first()
    }

    /**
     * Clear authentication token
     */
    suspend fun clearAuthToken() {
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_TOKEN_KEY)
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

    // ==================== Biometric Authentication ====================

    /**
     * Enable/disable biometric authentication
     */
    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] = enabled
        }
    }

    /**
     * Get biometric enabled status
     */
    fun getBiometricEnabled(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] ?: false
        }
    }

    /**
     * Get biometric enabled status synchronously
     */
    suspend fun getBiometricEnabledSync(): Boolean {
        return getBiometricEnabled().first()
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
            preferences.remove(BIOMETRIC_ENABLED_KEY)
            preferences.remove(USER_ID_KEY)
            preferences.remove(COMPANY_ID_KEY)
        }

        // Clear EncryptedSharedPreferences
        encryptedPrefs.edit().clear().apply()
    }
}
