package com.example.rocketplan_android

import android.app.Application
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.OfflineDatabase
import com.example.rocketplan_android.data.local.cache.PhotoCacheManager
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.ImageProcessorRepository
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.storage.ImageProcessingConfigStore
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.work.PhotoCacheScheduler

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

    private lateinit var imageProcessingConfigStore: ImageProcessingConfigStore

    override fun onCreate() {
        super.onCreate()

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
        offlineSyncRepository = OfflineSyncRepository(
            api = offlineSyncApi,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler,
            syncCheckpointStore = syncCheckpointStore
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
        val imageProcessorDao = offlineDb.imageProcessorDao()
        val offlineDao = offlineDb.offlineDao()
        val uploadStore = ImageProcessorUploadStore.getInstance(this)
        imageProcessorRepository = ImageProcessorRepository(
            context = this,
            api = imageProcessorApi,
            dao = imageProcessorDao,
            offlineDao = offlineDao,
            uploadStore = uploadStore,
            configurationRepository = imageProcessingConfigurationRepository,
            secureStorage = secureStorage,
            remoteLogger = remoteLogger
        )
    }
}
