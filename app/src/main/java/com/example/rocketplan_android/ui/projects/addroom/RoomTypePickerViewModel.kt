package com.example.rocketplan_android.ui.projects.addroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class RoomTypeUiModel(
    val id: Long,
    val displayName: String,
    val category: RoomTypeCategory,
    val iconRes: Int,
    val backendType: String?,
    val isStandard: Boolean,
    val isExterior: Boolean
)

data class RoomTypePickerUiState(
    val isLoading: Boolean = true,
    val items: List<RoomTypeUiModel> = emptyList(),
    val errorMessage: String? = null,
    val isCreating: Boolean = false
)

sealed interface RoomTypePickerEvent {
    data class RoomCreated(val roomName: String) : RoomTypePickerEvent
    data class RoomCreationFailed(val message: String?) : RoomTypePickerEvent
}

class RoomTypePickerViewModel(
    application: Application,
    private val projectId: Long,
    private val mode: RoomTypePickerMode
) : AndroidViewModel(application) {

    private val repository = (application as RocketPlanApplication).roomTypeRepository
    private val syncRepository = (application as RocketPlanApplication).offlineSyncRepository
    private val requestType = if (mode == RoomTypePickerMode.EXTERIOR) {
        RoomTypeRepository.RequestType.EXTERIOR
    } else {
        RoomTypeRepository.RequestType.INTERIOR
    }

    private val _uiState = MutableStateFlow(RoomTypePickerUiState())
    val uiState: StateFlow<RoomTypePickerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<RoomTypePickerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<RoomTypePickerEvent> = _events

    init {
        if (projectId <= 0L) {
            _uiState.value = RoomTypePickerUiState(
                isLoading = false,
                items = emptyList(),
                errorMessage = getApplication<Application>().getString(R.string.room_type_error_missing_property)
            )
        } else {
            refresh()
        }
    }

    fun refresh(force: Boolean = false) {
        if (projectId <= 0L || _uiState.value.isCreating) return
        viewModelScope.launch {
            val cachedResult = repository.getCachedRoomTypes(projectId, requestType)
            val cachedItems = cachedResult.getOrElse { emptyList() }
            val cachedError = cachedResult.exceptionOrNull()

            if (cachedItems.isNotEmpty()) {
                val models = cachedItems.toUiModels()
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        items = models,
                        errorMessage = null
                    )
                }
            } else {
                if (cachedError is RoomTypeRepository.MissingPropertyException ||
                    cachedError is RoomTypeRepository.UnsyncedPropertyException
                ) {
                    val message = when (cachedError) {
                        is RoomTypeRepository.MissingPropertyException ->
                            getApplication<Application>().getString(R.string.room_type_error_missing_property)
                        else -> getApplication<Application>().getString(R.string.room_type_error_generic)
                    }
                    _uiState.value = RoomTypePickerUiState(
                        isLoading = false,
                        items = emptyList(),
                        errorMessage = message
                    )
                    return@launch
                }
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            repository.getRoomTypes(projectId, requestType, force)
                .onSuccess { types ->
                    val items = types.toUiModels()
                    _uiState.value = RoomTypePickerUiState(
                        isLoading = false,
                        items = items,
                        errorMessage = null
                    )
                }
                .onFailure { throwable ->
                    val message = when (throwable) {
                        is RoomTypeRepository.MissingPropertyException ->
                            getApplication<Application>().getString(R.string.room_type_error_missing_property)
                        is RoomTypeRepository.UnsyncedPropertyException ->
                            getApplication<Application>().getString(R.string.room_type_error_generic)
                        else -> throwable.message ?: getApplication<Application>().getString(R.string.room_type_error_generic)
                    }
                    _uiState.update { current ->
                        if (current.items.isNotEmpty()) {
                            current.copy(isLoading = false, errorMessage = null)
                        } else {
                            current.copy(isLoading = false, errorMessage = message)
                        }
                    }
                }
            }
    }

    fun createRoom(roomType: RoomTypeUiModel) {
        if (projectId <= 0L || _uiState.value.isCreating) return
        val roomName = roomType.displayName.ifBlank { "Room ${roomType.id}" }
        android.util.Log.d("RoomTypePicker", "🆕 createRoom: id=${roomType.id}, displayName='${roomType.displayName}', roomName='$roomName'")
        _uiState.update { it.copy(isCreating = true, errorMessage = null) }

        viewModelScope.launch {
            syncRepository.createRoom(
                projectId = projectId,
                roomName = roomName,
                roomTypeId = roomType.id,
                isSource = false
            )
                .onSuccess { _ ->
                    _uiState.update { state -> state.copy(isCreating = false) }
                    _events.emit(RoomTypePickerEvent.RoomCreated(roomName))
                }
                .onFailure { throwable: Throwable ->
                    val message = when (throwable) {
                        is RoomTypeRepository.MissingPropertyException ->
                            getApplication<Application>().getString(R.string.room_type_error_missing_property)
                        is RoomTypeRepository.UnsyncedPropertyException ->
                            getApplication<Application>().getString(R.string.room_creation_property_sync_pending)
                        else -> throwable.message ?: getApplication<Application>().getString(R.string.room_type_error_generic)
                    }
                    _uiState.update { state ->
                        state.copy(
                            isCreating = false,
                            errorMessage = if (state.items.isEmpty()) message else state.errorMessage
                        )
                    }
                    _events.emit(RoomTypePickerEvent.RoomCreationFailed(message))
                }
        }
    }

    private fun RoomTypeDto.toUiModel(): RoomTypeUiModel {
        val app = getApplication<Application>()
        val metadata = RoomTypeCatalog.metadataForName(name)
        val iconRes = RoomTypeCatalog.resolveIconRes(app, id, name)
        val category = metadata?.category ?: RoomTypeCategory.OTHER
        val displayName = name?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.room_type_fallback_name, id)
        val standardFlag = isStandard ?: true
        return RoomTypeUiModel(
            id = id,
            displayName = displayName,
            category = category,
            iconRes = iconRes,
            backendType = type,
            isStandard = standardFlag,
            isExterior = RoomTypeCatalog.isExteriorType(type)
        )
    }

    private fun List<RoomTypeDto>.toUiModels(): List<RoomTypeUiModel> =
        map { it.toUiModel() }
            .sortedBy { it.displayName.lowercase(Locale.US) }

    class Factory(
        private val application: Application,
        private val projectId: Long,
        private val mode: RoomTypePickerMode
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(RoomTypePickerViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return RoomTypePickerViewModel(application, projectId, mode) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
        }
    }
}
