package com.example.rocketplan_android.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.LibraryMediaItem
import com.example.rocketplan_android.data.repository.LibraryMediaRepository
import kotlinx.coroutines.launch

data class GalleryUiState(
    val isLoading: Boolean = false,
    val items: List<LibraryMediaItem> = emptyList(),
    val errorMessage: String? = null
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LibraryMediaRepository(application)

    private val _uiState = MutableLiveData(GalleryUiState())
    val uiState: LiveData<GalleryUiState> = _uiState

    fun loadLibraryMedia() {
        _uiState.value = _uiState.value?.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.loadDcimMedia()
            _uiState.value = result.fold(
                onSuccess = { media ->
                    GalleryUiState(isLoading = false, items = media)
                },
                onFailure = { error ->
                    GalleryUiState(
                        isLoading = false,
                        errorMessage = error.message
                            ?: getApplication<Application>().getString(R.string.library_load_error)
                    )
                }
            )
        }
    }
}
