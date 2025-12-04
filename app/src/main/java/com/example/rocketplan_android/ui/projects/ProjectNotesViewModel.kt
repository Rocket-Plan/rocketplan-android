package com.example.rocketplan_android.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectNotesUiState(
    val items: List<NoteListItem> = emptyList(),
    val subtitle: String = "",
    val isEmpty: Boolean = true
)

class ProjectNotesViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long?,
    private val categoryId: Long?
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val notesRealtimeManager = rocketPlanApp.notesRealtimeManager
    private val formatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)

    private val _uiState = MutableStateFlow(ProjectNotesUiState())
    val uiState: StateFlow<ProjectNotesUiState> = _uiState

    init {
        viewModelScope.launch {
            val notesFlow = if (roomId != null) {
                localDataService.observeNotesForRoom(projectId, roomId)
            } else {
                localDataService.observeNotes(projectId)
            }
            combine(notesFlow, localDataService.observeRooms(projectId)) { notes, rooms ->
                val roomLookupById = rooms.associateBy { it.roomId }
                val roomLookupByServerId = rooms.mapNotNull { room ->
                    room.serverId?.let { serverId -> serverId to room }
                }.toMap()

                val items = notes.map { note ->
                    val roomTitle = note.roomId?.let { rid ->
                        roomLookupByServerId[rid]?.title
                            ?: roomLookupById[rid]?.title
                    }
                    note.toItem(roomTitle)
                }
                val subtitle = if (roomId != null) {
                    val title = roomLookupByServerId[roomId]?.title
                        ?: roomLookupById[roomId]?.title
                        ?: "Room $roomId"
                    getApplication<Application>().getString(
                        com.example.rocketplan_android.R.string.notes_for_room,
                        title
                    )
                } else {
                    getApplication<Application>().getString(
                        com.example.rocketplan_android.R.string.notes_for_project
                    )
                }

                val serverNoteIds = notes.mapNotNull { it.serverId }.toSet()
                notesRealtimeManager.updateProjectSubscriptions(projectId, serverNoteIds)

                ProjectNotesUiState(
                    items = items,
                    subtitle = subtitle,
                    isEmpty = items.isEmpty()
                )
            }.collect { state ->
                _uiState.update { state }
            }
        }
    }

    fun addNote(content: String) {
        viewModelScope.launch {
            offlineSyncRepository.createNote(
                projectId = projectId,
                content = content,
                roomId = roomId,
                categoryId = categoryId?.takeIf { it != 0L }
            )
        }
    }

    override fun onCleared() {
        notesRealtimeManager.clearProject(projectId)
        super.onCleared()
    }

    private fun OfflineNoteEntity.toItem(roomTitle: String?): NoteListItem {
        val dateText = formatter.format(updatedAt)
        val roomLabel = roomTitle?.takeIf { it.isNotBlank() }
        val meta = listOfNotNull(roomLabel, dateText).joinToString(" • ")
        val status = when {
            isDeleted -> "Deleted"
            isDirty || syncStatus != SyncStatus.SYNCED -> "Pending"
            else -> ""
        }
        return NoteListItem(
            id = uuid,
            content = content,
            meta = meta,
            status = status
        )
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long,
            roomId: Long?,
            categoryId: Long?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ProjectNotesViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return ProjectNotesViewModel(application, projectId, roomId, categoryId) as T
            }
        }
    }
}
