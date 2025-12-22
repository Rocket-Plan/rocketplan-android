package com.example.rocketplan_android.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

/**
 * Room type converters for complex types used across the offline data layer.
 */
class OfflineTypeConverters {

    private val gson = Gson()
    private val longListType = object : TypeToken<List<Long>>() {}.type

    @TypeConverter
    fun toDate(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun fromDate(date: Date?): Long? = date?.time

    @TypeConverter
    fun toSyncStatus(value: String?): SyncStatus? = value?.let { runCatching { SyncStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus?): String? = status?.name

    @TypeConverter
    fun toSyncPriority(value: Int?): SyncPriority? = value?.let { SyncPriority.fromLevel(it) }

    @TypeConverter
    fun fromSyncPriority(priority: SyncPriority?): Int? = priority?.level

    @TypeConverter
    fun toOperationType(value: String?): SyncOperationType? = value?.let { SyncOperationType.fromName(it) }

    @TypeConverter
    fun fromOperationType(type: SyncOperationType?): String? = type?.name

    @TypeConverter
    fun toPhotoCacheStatus(value: String?): PhotoCacheStatus? =
        value?.let { runCatching { PhotoCacheStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromPhotoCacheStatus(status: PhotoCacheStatus?): String? = status?.name

    @TypeConverter
    fun toLongList(value: String?): List<Long> =
        if (value.isNullOrBlank()) emptyList() else gson.fromJson(value, longListType)

    @TypeConverter
    fun fromLongList(values: List<Long>?): String? =
        values?.let { gson.toJson(it) }
}
