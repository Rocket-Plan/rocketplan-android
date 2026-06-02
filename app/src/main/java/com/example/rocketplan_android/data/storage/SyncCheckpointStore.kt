package com.example.rocketplan_android.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.sentry.Sentry
import java.util.Date

class SyncCheckpointStore(private val context: Context) {

    private val prefs: SharedPreferences? by lazy { createEncryptedPrefs() }

    private fun createEncryptedPrefs(): SharedPreferences? {
        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Throwable) {
            Sentry.withScope { scope ->
                scope.setTag("event", "sync_checkpoint_store_init_failed")
                Sentry.captureException(e)
            }
            Log.w(TAG, "encrypted checkpoint store unavailable; checkpoints disabled", e)
            null
        }
    }

    fun getCheckpoint(key: String): Date? {
        val millis = prefs?.getLong(key, 0L) ?: 0L
        return if (millis == 0L) null else Date(millis)
    }

    fun updateCheckpoint(key: String, timestamp: Date = Date()) {
        prefs?.edit()?.putLong(key, timestamp.time)?.apply()
    }

    fun clearCheckpoint(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }

    private companion object {
        private const val TAG = "SyncCheckpointStore"
        private const val ENCRYPTED_PREFS_NAME = "sync_checkpoints_encrypted"
        private const val LEGACY_PREFS_NAME = "sync_checkpoints"
    }
}
