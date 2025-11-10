package com.example.rocketplan_android.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager

    val projects: LiveData<List<OfflineProjectEntity>> =
        localDataService.observeProjects().asLiveData()

    val projectSummary: LiveData<String> = projects.map { offlineProjects ->
        if (offlineProjects.isEmpty()) {
            "No offline projects yet. Connect once to sync your assignments."
        } else {
            val active = offlineProjects.count { !it.isDeleted }
            val names = offlineProjects.take(3).joinToString { it.title }
            if (offlineProjects.size > 3) {
                "Offline projects ($active): $names and more…"
            } else {
                "Offline projects ($active): $names"
            }
        }
    }

    fun refreshProject(projectId: Long) {
        // Route through SyncQueueManager so it can be cancelled
        syncQueueManager.prioritizeProject(projectId)
    }

    fun refreshCompanyProjects(companyId: Long) {
        // Route through SyncQueueManager
        syncQueueManager.refreshProjects()
    }

    fun refreshUserProjects(userId: Long) {
        // Route through SyncQueueManager
        syncQueueManager.refreshProjects()
    }
}
