package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.ImageProcessorService
import com.example.rocketplan_android.data.model.ImageProcessingConfiguration
import com.example.rocketplan_android.data.storage.ImageProcessingConfigStore
import com.example.rocketplan_android.data.storage.StoredImageProcessingConfiguration
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ImageProcessingConfigurationRepository(
    private val service: ImageProcessorService,
    private val cacheStore: ImageProcessingConfigStore,
    private val remoteLogger: RemoteLogger?,
    private val gson: Gson = Gson(),
    private val cacheVersion: Int = CURRENT_CACHE_VERSION
) {

    companion object {
        private const val TAG = "ImageProcessorConfigRepo"
        private const val CURRENT_CACHE_VERSION = 3
    }

    private val mutex = Mutex()
    private val _configuration = MutableStateFlow<ImageProcessingConfiguration?>(null)
    val configuration: StateFlow<ImageProcessingConfiguration?> = _configuration.asStateFlow()

    suspend fun getConfiguration(forceRefresh: Boolean = false): Result<ImageProcessingConfiguration> {
        return mutex.withLock {
            if (!forceRefresh) {
                _configuration.value?.let { return Result.success(it) }
                loadFromCacheLocked()?.let { cached ->
                    _configuration.value = cached
                    return Result.success(cached)
                }
            }

            return fetchAndCacheLocked()
        }
    }

    suspend fun clearCachedConfiguration() {
        mutex.withLock {
            cacheStore.clear()
            _configuration.value = null
        }
    }

    private suspend fun fetchAndCacheLocked(): Result<ImageProcessingConfiguration> {
        return runCatching {
            val response = service.getProcessingConfiguration()
            response.data.also { config ->
                cacheStore.write(gson.toJson(config), cacheVersion)
                _configuration.value = config
                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Fetched image processor configuration",
                    metadata = mapOf(
                        "service" to config.service,
                        "url" to config.url
                    )
                )
            }
        }.onFailure { error ->
            remoteLogger?.log(
                level = LogLevel.ERROR,
                tag = TAG,
                message = "Failed to fetch image processor configuration",
                metadata = mapOf("reason" to (error.message ?: "unknown"))
            )
        }
    }

    private suspend fun loadFromCacheLocked(): ImageProcessingConfiguration? {
        val stored: StoredImageProcessingConfiguration = cacheStore.read() ?: return null
        if (stored.version != cacheVersion) {
            cacheStore.clear()
            remoteLogger?.log(
                level = LogLevel.DEBUG,
                tag = TAG,
                message = "Cache version mismatch; clearing stored configuration",
                metadata = mapOf(
                    "stored_version" to stored.version.toString(),
                    "expected_version" to cacheVersion.toString()
                )
            )
            return null
        }

        return runCatching {
            gson.fromJson(stored.rawJson, ImageProcessingConfiguration::class.java)
        }.getOrElse { error ->
            remoteLogger?.log(
                level = LogLevel.ERROR,
                tag = TAG,
                message = "Failed to decode cached image processor configuration",
                metadata = mapOf("reason" to (error.message ?: "unknown"))
            )
            cacheStore.clear()
            null
        }
    }
}
