package com.example.rocketplan_android

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
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
import com.example.rocketplan_android.data.storage.ImageProcessingConfigStore
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.realtime.ImageProcessorRealtimeManager
import com.example.rocketplan_android.realtime.PusherService
import com.example.rocketplan_android.work.PhotoCacheScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.rocketplan_android.thermal.FlirSdkManager

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

    private lateinit var imageProcessorNetworkMonitor: com.example.rocketplan_android.data.network.ImageProcessorNetworkMonitor

    private lateinit var imageProcessingConfigStore: ImageProcessingConfigStore

    override fun onCreate() {
        super.onCreate()

        // Initialize FLIR SDK early so discovery is ready when users enter thermal capture
        FlirSdkManager.init(this)

        // Removed debug-time database purge to preserve cached data across launches
        localDataService = LocalDataService.initialize(this)
        photoCacheManager = PhotoCacheManager(this, localDataService)
        photoCacheScheduler = PhotoCacheScheduler(this)
        secureStorage = SecureStorage.getInstance(this)
        remoteLogger = RemoteLogger(
            loggingService = RetrofitClient.loggingService,
            context = this,
            secureStorage = secureStorage
        )
        syncCheckpointStore = SyncCheckpointStore(this)
        imageProcessingConfigStore = ImageProcessingConfigStore.getInstance(this)

        authRepository = AuthRepository(secureStorage)

        val offlineSyncApi = RetrofitClient.createService<OfflineSyncApi>()
        roomTypeRepository = RoomTypeRepository(
            api = offlineSyncApi,
            localDataService = localDataService
        )
        offlineSyncRepository = OfflineSyncRepository(
            api = offlineSyncApi,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler,
            syncCheckpointStore = syncCheckpointStore,
            roomTypeRepository = roomTypeRepository
        )

        syncQueueManager = SyncQueueManager(
            authRepository = authRepository,
            syncRepository = offlineSyncRepository,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler,
            remoteLogger = remoteLogger
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
        val pusherService = PusherService(remoteLogger = remoteLogger)
        imageProcessorRealtimeManager = ImageProcessorRealtimeManager(
            dao = imageProcessorDao,
            pusherService = pusherService,
            remoteLogger = remoteLogger
        )
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
            configRepository = imageProcessingConfigurationRepository,
            secureStorage = secureStorage,
            remoteLogger = remoteLogger
        )

        // Schedule periodic retry worker (every 15 minutes)
        val retryWorkRequest = PeriodicWorkRequestBuilder<com.example.rocketplan_android.data.worker.ImageProcessorRetryWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            com.example.rocketplan_android.data.worker.ImageProcessorRetryWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            retryWorkRequest
        )

        // Initialize network monitor for connectivity-based retries
        imageProcessorNetworkMonitor = com.example.rocketplan_android.data.network.ImageProcessorNetworkMonitor(
            context = this,
            queueManager = imageProcessorQueueManager
        )

        // Cold-start recovery: Resume any existing assemblies that were interrupted
        // First, recover any assemblies left in UPLOADING state, then restart the queue
        CoroutineScope(Dispatchers.IO).launch {
            imageProcessorQueueManager.recoverStrandedAssemblies()
            imageProcessorQueueManager.processNextQueuedAssembly()
        }

        // One-time data repair: Fix photos with mismatched roomIds from previous sync bugs
        CoroutineScope(Dispatchers.IO).launch {
            localDataService.repairMismatchedPhotoRoomIds()
        }
    }
}
