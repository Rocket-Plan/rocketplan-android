package com.example.rocketplan_android.data.storage

import android.content.Context
import java.util.Date

class SyncCheckpointStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCheckpoint(key: String): Date? {
        val millis = prefs.getLong(key, 0L)
        return if (millis == 0L) {
            null
        } else {
            Date(millis)
        }
    }

    fun updateCheckpoint(key: String, timestamp: Date = Date()) {
        prefs.edit().putLong(key, timestamp.time).apply()
    }

    fun clearCheckpoint(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val PREFS_NAME = "sync_checkpoints"
    }
}
