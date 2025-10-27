package com.example.rocketplan_android

import android.app.Application
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.cache.PhotoCacheManager
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.storage.SecureStorage
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

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            // Temporary dev helper so we always start with a clean offline store.
            deleteDatabase("rocketplan_offline.db")
        }
        localDataService = LocalDataService.initialize(this)
        photoCacheManager = PhotoCacheManager(this, localDataService)
        photoCacheScheduler = PhotoCacheScheduler(this)
        secureStorage = SecureStorage.getInstance(this)
        remoteLogger = RemoteLogger(
            loggingService = RetrofitClient.loggingService,
            context = this,
            secureStorage = secureStorage
        )

        authRepository = AuthRepository(secureStorage)

        val offlineSyncApi = RetrofitClient.createService<OfflineSyncApi>()
        offlineSyncRepository = OfflineSyncRepository(
            api = offlineSyncApi,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler
        )

        syncQueueManager = SyncQueueManager(
            authRepository = authRepository,
            syncRepository = offlineSyncRepository,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler,
            remoteLogger = remoteLogger
        )
    }
}
