package com.example.rocketplan_android.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.rocketplan_android.data.model.offline.OfflineRoomTypeCatalogResponse
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

class OfflineRoomTypeCatalogStore private constructor(
    private val context: Context,
    private val gson: Gson = Gson()
) {

    companion object {
        private val Context.catalogDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "offline_room_type_catalog"
        )
        private val CATALOG_JSON = stringPreferencesKey("catalog_json")
        private val CATALOG_VERSION = stringPreferencesKey("catalog_version")
        private val CATALOG_UPDATED_AT = longPreferencesKey("catalog_updated_at")

        @Volatile
        private var INSTANCE: OfflineRoomTypeCatalogStore? = null

        fun getInstance(context: Context): OfflineRoomTypeCatalogStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineRoomTypeCatalogStore(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val dataStore = context.catalogDataStore

    suspend fun read(): StoredOfflineRoomTypeCatalog? {
        val prefs = dataStore.data.first()
        val rawJson = prefs[CATALOG_JSON] ?: return null
        val version = prefs[CATALOG_VERSION]
        val updatedAt = prefs[CATALOG_UPDATED_AT] ?: 0L
        val catalog = runCatching {
            gson.fromJson(rawJson, OfflineRoomTypeCatalogResponse::class.java)
        }.getOrNull() ?: return null
        return StoredOfflineRoomTypeCatalog(
            catalog = catalog,
            version = version,
            updatedAtMillis = updatedAt
        )
    }

    suspend fun write(catalog: OfflineRoomTypeCatalogResponse) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[CATALOG_JSON] = gson.toJson(catalog)
            val version = catalog.version
            if (version != null) {
                prefs[CATALOG_VERSION] = version
            } else {
                prefs.remove(CATALOG_VERSION)
            }
            prefs[CATALOG_UPDATED_AT] = now
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(CATALOG_JSON)
            prefs.remove(CATALOG_VERSION)
            prefs.remove(CATALOG_UPDATED_AT)
        }
    }
}

data class StoredOfflineRoomTypeCatalog(
    val catalog: OfflineRoomTypeCatalogResponse,
    val version: String?,
    val updatedAtMillis: Long
)
