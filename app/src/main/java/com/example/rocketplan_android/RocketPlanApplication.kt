package com.example.rocketplan_android

import android.app.Application
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.cache.PhotoCacheManager
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.work.PhotoCacheScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RocketPlanApplication : Application() {

    lateinit var localDataService: LocalDataService
        private set

    lateinit var offlineSyncRepository: OfflineSyncRepository
        private set

    lateinit var photoCacheManager: PhotoCacheManager
        private set

    lateinit var photoCacheScheduler: PhotoCacheScheduler
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        localDataService = LocalDataService.initialize(this)
        photoCacheManager = PhotoCacheManager(this, localDataService)
        photoCacheScheduler = PhotoCacheScheduler(this)
        val offlineSyncApi = RetrofitClient.createService<OfflineSyncApi>()
        offlineSyncRepository = OfflineSyncRepository(
            api = offlineSyncApi,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler
        )
        applicationScope.launch {
            localDataService.seedDemoDataIfEmpty()
        }
    }
}
