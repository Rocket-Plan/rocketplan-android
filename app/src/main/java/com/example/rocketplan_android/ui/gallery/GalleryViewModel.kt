package com.example.rocketplan_android.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import com.example.rocketplan_android.RocketPlanApplication

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val localDataService = (application as RocketPlanApplication).localDataService

    private val cachedPhotoCount = localDataService.observeCachedPhotoCount().asLiveData()

    val text: LiveData<String> = cachedPhotoCount.map { count ->
        if (count == 0) {
            "No photos cached for offline use yet."
        } else {
            "Offline-ready photos: $count"
        }
    }
}
