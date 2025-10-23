package com.example.rocketplan_android.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val localDataService: LocalDataService =
        (application as RocketPlanApplication).localDataService
    private val offlineSyncRepository: OfflineSyncRepository =
        (application as RocketPlanApplication).offlineSyncRepository

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
        viewModelScope.launch {
            runCatching { offlineSyncRepository.syncProjectGraph(projectId) }
        }
    }

    fun refreshCompanyProjects(companyId: Long) {
        viewModelScope.launch {
            runCatching { offlineSyncRepository.syncCompanyProjects(companyId) }
        }
    }

    fun refreshUserProjects(userId: Long) {
        viewModelScope.launch {
            runCatching { offlineSyncRepository.syncUserProjects(userId) }
        }
    }
}
