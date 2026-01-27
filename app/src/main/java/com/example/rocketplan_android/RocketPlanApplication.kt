package com.example.rocketplan_android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.OfflineDatabase
import com.example.rocketplan_android.data.local.cache.PhotoCacheManager
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.ImageProcessorRepository
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.repository.sync.SupportSyncService
import com.example.rocketplan_android.data.storage.ImageProcessingConfigStore
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.OfflineRoomTypeCatalogStore
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.realtime.ImageProcessorRealtimeManager
import com.example.rocketplan_android.realtime.NotesRealtimeManager
import com.example.rocketplan_android.realtime.ProjectRealtimeManager
import com.example.rocketplan_android.realtime.PhotoSyncRealtimeManager
import com.example.rocketplan_android.realtime.PusherService
import com.example.rocketplan_android.data.network.SyncNetworkMonitor
import com.example.rocketplan_android.work.PhotoCacheScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.rocketplan_android.thermal.FlirSdkManager
import com.example.rocketplan_android.config.AppConfig
import android.os.Build
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

class RocketPlanApplication : Application() {

    lateinit var localDataService: LocalDataService
        private set

    lateinit var offlineSyncRepository: OfflineSyncRepository
        private set

    lateinit var photoCacheManager: PhotoCacheManager
        private set

    lateinit var photoCacheScheduler: PhotoCacheScheduler
        private set

    lateinit var remoteLogger: RemoteLogger
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var syncQueueManager: SyncQueueManager
        private set

    lateinit var secureStorage: SecureStorage
        private set

    lateinit var syncCheckpointStore: SyncCheckpointStore
        private set

    lateinit var imageProcessingConfigurationRepository: ImageProcessingConfigurationRepository
        private set

    lateinit var imageProcessorRepository: ImageProcessorRepository
        private set

    lateinit var imageProcessorRealtimeManager: ImageProcessorRealtimeManager
        private set

    lateinit var imageProcessorDao: com.example.rocketplan_android.data.local.dao.ImageProcessorDao
        private set

    lateinit var imageProcessorUploadStore: ImageProcessorUploadStore
        private set

    lateinit var imageProcessorQueueManager: com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
        private set

    lateinit var roomTypeRepository: RoomTypeRepository
        private set

    lateinit var supportSyncService: SupportSyncService
        private set

    lateinit var photoSyncRealtimeManager: PhotoSyncRealtimeManager
        private set
    lateinit var projectRealtimeManager: ProjectRealtimeManager
        private set
    lateinit var notesRealtimeManager: NotesRealtimeManager
        private set

    private lateinit var pusherService: PusherService

    private lateinit var imageProcessorNetworkMonitor: com.example.rocketplan_android.data.network.ImageProcessorNetworkMonitor

    private lateinit var imageProcessingConfigStore: ImageProcessingConfigStore

    lateinit var syncNetworkMonitor: SyncNetworkMonitor
        private set

    override fun onCreate() {
        super.onCreate()

        initSentry()

        // Initialize FLIR SDK early so discovery is ready when users enter thermal capture
        FlirSdkManager.init(this)

        // Removed debug-time database purge to preserve cached data across launches
        localDataService = LocalDataService.initialize(this)
        photoCacheScheduler = PhotoCacheScheduler(this)
        secureStorage = SecureStorage.getInstance(this)
        remoteLogger = RemoteLogger(
            loggingService = RetrofitClient.loggingService,
            context = this,
            secureStorage = secureStorage
        )
        logDeviceInfo()
        photoCacheManager = PhotoCacheManager(this, localDataService, remoteLogger)
        syncCheckpointStore = SyncCheckpointStore(this)
        imageProcessingConfigStore = ImageProcessingConfigStore.getInstance(this)
        val offlineRoomTypeCatalogStore = OfflineRoomTypeCatalogStore.getInstance(this)

        authRepository = AuthRepository(secureStorage, remoteLogger)

        val offlineSyncApi = RetrofitClient.createService<OfflineSyncApi>()
        roomTypeRepository = RoomTypeRepository(
            api = offlineSyncApi,
            localDataService = localDataService,
            offlineRoomTypeCatalogStore = offlineRoomTypeCatalogStore
        )

        // Note: syncQueueEnqueuer is provided as a lazy lambda since SyncQueueManager
        // is not yet initialized at this point but will be by the time it's called
        supportSyncService = SupportSyncService(
            api = offlineSyncApi,
            localDataService = localDataService,
            syncQueueEnqueuer = { offlineSyncRepository.syncQueueEnqueuer }
        )

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isNetworkAvailable: () -> Boolean = {
            runCatching {
                val network = connectivityManager?.activeNetwork
                val capabilities = connectivityManager?.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(false) // Default to false (offline) on exceptions for safety
        }

        offlineSyncRepository = OfflineSyncRepository(
            api = offlineSyncApi,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler,
            syncCheckpointStore = syncCheckpointStore,
            roomTypeRepository = roomTypeRepository,
            photoCacheManager = photoCacheManager,
            remoteLogger = remoteLogger,
            isNetworkAvailable = isNetworkAvailable
        )

        // Note: syncQueueManager is initialized here but photoSyncRealtimeManager
        // will be connected later after Pusher is initialized
        syncQueueManager = SyncQueueManager(
            authRepository = authRepository,
            syncRepository = offlineSyncRepository,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler,
            remoteLogger = remoteLogger,
            connectivityManager = connectivityManager
        )

        val imageProcessorService = RetrofitClient.imageProcessorService
        imageProcessingConfigurationRepository = ImageProcessingConfigurationRepository(
            service = imageProcessorService,
            cacheStore = imageProcessingConfigStore,
            remoteLogger = remoteLogger
        )

        val offlineDb = OfflineDatabase.getInstance(this)
        val imageProcessorApi = RetrofitClient.imageProcessorApi
        imageProcessorDao = offlineDb.imageProcessorDao()
        val offlineDao = offlineDb.offlineDao()
        imageProcessorUploadStore = ImageProcessorUploadStore.getInstance(this)
        pusherService = PusherService(remoteLogger = remoteLogger)
        imageProcessorRealtimeManager = ImageProcessorRealtimeManager(
            dao = imageProcessorDao,
            pusherService = pusherService,
            remoteLogger = remoteLogger
        )
        photoSyncRealtimeManager = PhotoSyncRealtimeManager(pusherService)
        projectRealtimeManager = ProjectRealtimeManager(
            pusherService = pusherService,
            syncQueueManager = syncQueueManager,
            authRepository = authRepository,
            remoteLogger = remoteLogger
        )
        notesRealtimeManager = NotesRealtimeManager(
            pusherService = pusherService,
            syncQueueManager = syncQueueManager,
            remoteLogger = remoteLogger
        )
        // Connect PhotoSyncRealtimeManager to SyncQueueManager to handle Pusher events
        syncQueueManager.setPhotoSyncRealtimeManager(photoSyncRealtimeManager)
        syncQueueManager.setProjectRealtimeManager(projectRealtimeManager)

        imageProcessorRepository = ImageProcessorRepository(
            context = this,
            api = imageProcessorApi,
            dao = imageProcessorDao,
            offlineDao = offlineDao,
            uploadStore = imageProcessorUploadStore,
            configurationRepository = imageProcessingConfigurationRepository,
            secureStorage = secureStorage,
            remoteLogger = remoteLogger,
            realtimeManager = imageProcessorRealtimeManager
        )

        imageProcessorQueueManager = com.example.rocketplan_android.data.queue.ImageProcessorQueueManager(
            context = this,
            dao = imageProcessorDao,
            offlineDao = offlineDao,
            uploadStore = imageProcessorUploadStore,
            api = imageProcessorApi,
            configRepository = imageProcessingConfigurationRepository,
            secureStorage = secureStorage,
            remoteLogger = remoteLogger,
            realtimeManager = imageProcessorRealtimeManager
        )
        offlineSyncRepository.attachImageProcessorQueueManager(imageProcessorQueueManager)

        // Schedule periodic retry worker (every 15 minutes)
        val retryConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val retryWorkRequest = PeriodicWorkRequestBuilder<com.example.rocketplan_android.data.worker.ImageProcessorRetryWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(retryConstraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            com.example.rocketplan_android.data.worker.ImageProcessorRetryWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            retryWorkRequest
        )

        // Initialize network monitor for connectivity-based retries
        imageProcessorNetworkMonitor = com.example.rocketplan_android.data.network.ImageProcessorNetworkMonitor(
            context = this,
            queueManager = imageProcessorQueueManager,
            remoteLogger = remoteLogger
        )
        syncNetworkMonitor = SyncNetworkMonitor(
            context = this,
            syncQueueManager = syncQueueManager,
            remoteLogger = remoteLogger
        )

        // Cold-start recovery: Resume any existing assemblies that were interrupted
        // First, recover any assemblies left in UPLOADING state, then restart the queue
        CoroutineScope(Dispatchers.IO).launch {
            imageProcessorQueueManager.recoverStrandedAssemblies()
            imageProcessorQueueManager.processNextQueuedAssembly()
            runCatching { imageProcessorRepository.cleanupOldAssemblies() }
                .onFailure { error ->
                    Log.w(TAG, "Failed to cleanup old image processor assemblies", error)
                }
        }

        // One-time data repair: Fix photos with mismatched roomIds from previous sync bugs
        CoroutineScope(Dispatchers.IO).launch {
            val reassignedCount = localDataService.repairMismatchedPhotoRoomIds()
            if (reassignedCount > 0) {
                remoteLogger.log(
                    level = LogLevel.WARN,
                    tag = TAG,
                    message = "Data repair: reassigned photos with mismatched roomIds to project level",
                    metadata = mapOf("reassigned_count" to reassignedCount.toString())
                )
            }
        }
    }

    private fun logDeviceInfo() {
        val displayMetrics = resources.displayMetrics
        remoteLogger.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Device hardware information",
            metadata = mapOf(
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "hardware" to Build.HARDWARE,
                "manufacturer" to Build.MANUFACTURER,
                "board" to Build.BOARD,
                "product" to Build.PRODUCT,
                "brand" to Build.BRAND,
                "fingerprint" to Build.FINGERPRINT,
                "bootloader" to Build.BOOTLOADER,
                "display" to Build.DISPLAY,
                "id" to Build.ID,
                "sdk_int" to Build.VERSION.SDK_INT.toString(),
                "release" to Build.VERSION.RELEASE,
                "incremental" to Build.VERSION.INCREMENTAL,
                "screen_width" to displayMetrics.widthPixels.toString(),
                "screen_height" to displayMetrics.heightPixels.toString(),
                "screen_density" to displayMetrics.density.toString(),
                "density_dpi" to displayMetrics.densityDpi.toString()
            )
        )
    }

    private fun initSentry() {
        val dsn = AppConfig.sentryDsn
        if (!AppConfig.isCrashReportingEnabled || dsn.isBlank()) {
            return
        }

        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = AppConfig.environment
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.isDebug = BuildConfig.DEBUG
        }

        Sentry.configureScope { scope ->
            scope.setTag("device_flavor", if (BuildConfig.HAS_FLIR_SUPPORT) "flir" else "standard")
        }
    }

    private companion object {
        private const val TAG = "RocketPlanApp"
    }
}
