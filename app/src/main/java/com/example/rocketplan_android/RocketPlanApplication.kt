package com.example.rocketplan_android

import android.app.Application
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RocketPlanApplication : Application() {

    lateinit var localDataService: LocalDataService
        private set

    lateinit var offlineSyncRepository: OfflineSyncRepository
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        localDataService = LocalDataService.initialize(this)
        val offlineSyncApi = RetrofitClient.createService<OfflineSyncApi>()
        offlineSyncRepository = OfflineSyncRepository(offlineSyncApi, localDataService)
        applicationScope.launch {
            localDataService.seedDemoDataIfEmpty()
        }
    }
}
