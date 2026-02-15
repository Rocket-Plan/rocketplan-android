package com.example.rocketplan_android.ui.crm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.model.CrmNoteDto
import com.example.rocketplan_android.data.model.CrmTaskDto
import com.example.rocketplan_android.data.model.CrmTaskRequest
import com.example.rocketplan_android.data.model.CrmTaskUpdateRequest
import com.example.rocketplan_android.data.repository.CrmBusinessRepository
import com.example.rocketplan_android.data.repository.CrmContactRepository
import com.example.rocketplan_android.data.repository.CrmNoteRepository
import com.example.rocketplan_android.data.repository.CrmTaskRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CrmContactDetailUiState(
    val contact: CrmContactDto? = null,
    val customFieldDefinitions: List<CrmCustomFieldDefinitionDto> = emptyList(),
    val businessName: String? = null,
    val notes: List<CrmNoteDto> = emptyList(),
    val tasks: List<CrmTaskDto> = emptyList(),
    val isLoading: Boolean = true,
    val isNotesLoading: Boolean = false,
    val isTasksLoading: Boolean = false,
    val notesLoaded: Boolean = false,
    val tasksLoaded: Boolean = false,
    val error: String? = null
)

sealed class CrmContactDetailEvent {
    data class ShowError(val message: String) : CrmContactDetailEvent()
    data object ContactNotFound : CrmContactDetailEvent()
    data object NoteCreated : CrmContactDetailEvent()
    data object NoteUpdated : CrmContactDetailEvent()
    data object NoteDeleted : CrmContactDetailEvent()
    data object TaskCreated : CrmContactDetailEvent()
    data object TaskUpdated : CrmContactDetailEvent()
    data object TaskDeleted : CrmContactDetailEvent()
}

class CrmContactDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val contactRepository: CrmContactRepository
    private val businessRepository: CrmBusinessRepository
    private val noteRepository: CrmNoteRepository
    private val taskRepository: CrmTaskRepository

    private val _uiState = MutableStateFlow(CrmContactDetailUiState())
    val uiState: StateFlow<CrmContactDetailUiState> = _uiState

    private val _events = MutableSharedFlow<CrmContactDetailEvent>()
    val events: SharedFlow<CrmContactDetailEvent> = _events

    init {
        val app = application as RocketPlanApplication
        contactRepository = CrmContactRepository(app.authRepository, app.remoteLogger)
        businessRepository = CrmBusinessRepository(app.authRepository, app.remoteLogger)
        noteRepository = CrmNoteRepository(app.authRepository, app.remoteLogger)
        taskRepository = CrmTaskRepository(app.authRepository, app.remoteLogger)
    }

    fun loadContact(contactId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val defsJob = launch {
                contactRepository.getCustomFieldDefinitions("contact").onSuccess { defs ->
                    _uiState.update { it.copy(customFieldDefinitions = defs.sortedBy { d -> d.position ?: Int.MAX_VALUE }) }
                }
            }

            contactRepository.getContact(contactId).onSuccess { contact ->
                defsJob.join()
                _uiState.update { it.copy(contact = contact, isLoading = false) }
                // Resolve business name if contact is associated with a business
                contact.businessId?.takeIf { it.isNotBlank() }?.let { bizId ->
                    launch {
                        businessRepository.getBusiness(bizId).onSuccess { biz ->
                            _uiState.update { it.copy(businessName = biz.name) }
                        }
                    }
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message) }
                _events.emit(CrmContactDetailEvent.ContactNotFound)
            }
        }
    }

    fun loadNotes(contactId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isNotesLoading = true) }

            val allNotes = mutableListOf<CrmNoteDto>()
            var page = 1
            var hasMore = true

            while (hasMore && page <= MAX_PAGES) {
                noteRepository.getNotes(contactId, page = page).onSuccess { response ->
                    allNotes.addAll(response.data)
                    hasMore = page < (response.meta?.lastPage ?: 1)
                    page++
                }.onFailure {
                    hasMore = false
                }
            }

            _uiState.update { it.copy(notes = allNotes, isNotesLoading = false, notesLoaded = true) }
        }
    }

    fun loadTasks(contactId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTasksLoading = true) }

            val allTasks = mutableListOf<CrmTaskDto>()
            var page = 1
            var hasMore = true

            while (hasMore && page <= MAX_PAGES) {
                taskRepository.getTasks(contactId, page = page).onSuccess { response ->
                    allTasks.addAll(response.data)
                    hasMore = page < (response.meta?.lastPage ?: 1)
                    page++
                }.onFailure {
                    hasMore = false
                }
            }

            _uiState.update { it.copy(tasks = allTasks, isTasksLoading = false, tasksLoaded = true) }
        }
    }

    private companion object {
        private const val MAX_PAGES = 10
    }

    fun createNote(contactId: String, body: String) {
        viewModelScope.launch {
            noteRepository.createNote(contactId, body).onSuccess {
                _uiState.update { it.copy(notesLoaded = false) }
                _events.emit(CrmContactDetailEvent.NoteCreated)
                loadNotes(contactId)
            }.onFailure {
                _events.emit(CrmContactDetailEvent.ShowError("Failed to create note"))
            }
        }
    }

    fun updateNote(contactId: String, noteId: String, body: String) {
        viewModelScope.launch {
            noteRepository.updateNote(noteId, body).onSuccess {
                _uiState.update { it.copy(notesLoaded = false) }
                _events.emit(CrmContactDetailEvent.NoteUpdated)
                loadNotes(contactId)
            }.onFailure {
                _events.emit(CrmContactDetailEvent.ShowError("Failed to update note"))
            }
        }
    }

    fun deleteNote(contactId: String, noteId: String) {
        viewModelScope.launch {
            noteRepository.deleteNote(noteId).onSuccess {
                _uiState.update { it.copy(notesLoaded = false) }
                _events.emit(CrmContactDetailEvent.NoteDeleted)
                loadNotes(contactId)
            }.onFailure {
                _events.emit(CrmContactDetailEvent.ShowError("Failed to delete note"))
            }
        }
    }

    fun createTask(contactId: String, request: CrmTaskRequest) {
        viewModelScope.launch {
            taskRepository.createTask(request).onSuccess {
                _uiState.update { it.copy(tasksLoaded = false) }
                _events.emit(CrmContactDetailEvent.TaskCreated)
                loadTasks(contactId)
            }.onFailure {
                _events.emit(CrmContactDetailEvent.ShowError("Failed to create task"))
            }
        }
    }

    fun updateTask(contactId: String, taskId: String, request: CrmTaskUpdateRequest) {
        viewModelScope.launch {
            taskRepository.updateTask(taskId, request).onSuccess {
                _uiState.update { it.copy(tasksLoaded = false) }
                _events.emit(CrmContactDetailEvent.TaskUpdated)
                loadTasks(contactId)
            }.onFailure {
                _events.emit(CrmContactDetailEvent.ShowError("Failed to update task"))
            }
        }
    }

    fun deleteTask(contactId: String, taskId: String) {
        viewModelScope.launch {
            taskRepository.deleteTask(taskId).onSuccess {
                _uiState.update { it.copy(tasksLoaded = false) }
                _events.emit(CrmContactDetailEvent.TaskDeleted)
                loadTasks(contactId)
            }.onFailure {
                _events.emit(CrmContactDetailEvent.ShowError("Failed to delete task"))
            }
        }
    }
}
