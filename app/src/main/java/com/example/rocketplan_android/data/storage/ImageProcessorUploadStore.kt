package com.example.rocketplan_android.data.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson

/**
 * Persists the complete payload required to recover an upload after process death.
 */
class ImageProcessorUploadStore private constructor(
    context: Context,
    private val gson: Gson = Gson()
) {

    companion object {
        private const val PREFS_NAME = "image_processor_upload_store"
        private const val KEY_PREFIX = "upload_data_"

        @Volatile
        private var instance: ImageProcessorUploadStore? = null

        fun getInstance(context: Context): ImageProcessorUploadStore =
            instance ?: synchronized(this) {
                instance ?: ImageProcessorUploadStore(context.applicationContext).also {
                    instance = it
                }
            }
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun write(assemblyId: String, data: StoredUploadData) {
        prefs.edit()
            .putString(keyFor(assemblyId), gson.toJson(data))
            .apply()
    }

    fun read(assemblyId: String): StoredUploadData? {
        val raw = prefs.getString(keyFor(assemblyId), null) ?: return null
        return runCatching { gson.fromJson(raw, StoredUploadData::class.java) }.getOrNull()
    }

    fun remove(assemblyId: String) {
        prefs.edit()
            .remove(keyFor(assemblyId))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(assemblyId: String) = "$KEY_PREFIX$assemblyId"
}

data class StoredUploadData(
    val processingUrl: String,
    val apiKey: String?,
    val templateId: String,
    val projectId: Long,
    val roomId: Long?,
    val groupUuid: String,
    val userId: Long,
    val albums: Map<String, List<String>>,
    val order: List<String>,
    val notes: Map<String, List<String>>,
    val entityType: String? = null,
    val entityId: Long? = null
)
