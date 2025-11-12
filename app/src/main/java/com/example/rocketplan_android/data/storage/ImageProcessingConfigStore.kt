package com.example.rocketplan_android.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

data class StoredImageProcessingConfiguration(
    val rawJson: String,
    val version: Int,
    val updatedAtMillis: Long
)

/**
 * Persists the latest image processor configuration so uploads can start
 * even when the network is temporarily unavailable.
 */
class ImageProcessingConfigStore private constructor(private val context: Context) {

    companion object {
        private val CONFIG_JSON = stringPreferencesKey("image_processing_config_json")
        private val CONFIG_VERSION = intPreferencesKey("image_processing_config_version")
        private val CONFIG_UPDATED_AT = longPreferencesKey("image_processing_config_updated_at")

        private val Context.imageProcessingConfigDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "image_processing_configuration"
        )

        @Volatile
        private var INSTANCE: ImageProcessingConfigStore? = null

        fun getInstance(context: Context): ImageProcessingConfigStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ImageProcessingConfigStore(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val dataStore = context.imageProcessingConfigDataStore

    suspend fun read(): StoredImageProcessingConfiguration? {
        val preferences = dataStore.data.first()
        val json = preferences[CONFIG_JSON] ?: return null
        val version = preferences[CONFIG_VERSION] ?: return null
        val updatedAt = preferences[CONFIG_UPDATED_AT] ?: 0L
        return StoredImageProcessingConfiguration(
            rawJson = json,
            version = version,
            updatedAtMillis = updatedAt
        )
    }

    suspend fun write(rawJson: String, version: Int, updatedAt: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[CONFIG_JSON] = rawJson
            prefs[CONFIG_VERSION] = version
            prefs[CONFIG_UPDATED_AT] = updatedAt
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(CONFIG_JSON)
            prefs.remove(CONFIG_VERSION)
            prefs.remove(CONFIG_UPDATED_AT)
        }
    }
}
