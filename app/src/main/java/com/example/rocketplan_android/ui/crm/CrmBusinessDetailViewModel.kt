package com.example.rocketplan_android.ui.crm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmBusinessDto
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.model.CrmContactRequest
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.repository.CrmBusinessRepository
import com.example.rocketplan_android.data.repository.CrmContactRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CrmBusinessDetailUiState(
    val business: CrmBusinessDto? = null,
    val customFieldDefinitions: List<CrmCustomFieldDefinitionDto> = emptyList(),
    val contacts: List<CrmContactDto> = emptyList(),
    val isLoading: Boolean = true,
    val isContactsLoading: Boolean = false,
    val contactsLoaded: Boolean = false,
    val error: String? = null
)

sealed class CrmBusinessDetailEvent {
    data class ShowError(val message: String) : CrmBusinessDetailEvent()
    data object BusinessNotFound : CrmBusinessDetailEvent()
    data object BusinessDeleted : CrmBusinessDetailEvent()
    data class ContactAdded(val contactName: String) : CrmBusinessDetailEvent()
    data object ContactRemoved : CrmBusinessDetailEvent()
    data class ShowContactPicker(val contacts: List<CrmContactDto>) : CrmBusinessDetailEvent()
}

class CrmBusinessDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CrmBusinessRepository
    private val contactRepository: CrmContactRepository

    private val _uiState = MutableStateFlow(CrmBusinessDetailUiState())
    val uiState: StateFlow<CrmBusinessDetailUiState> = _uiState

    private val _events = MutableSharedFlow<CrmBusinessDetailEvent>()
    val events: SharedFlow<CrmBusinessDetailEvent> = _events

    init {
        val app = application as RocketPlanApplication
        repository = CrmBusinessRepository(app.authRepository, app.remoteLogger)
        contactRepository = CrmContactRepository(app.authRepository, app.remoteLogger)
    }

    fun loadBusiness(businessId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val defsJob = launch {
                repository.getCustomFieldDefinitions("business").onSuccess { defs ->
                    _uiState.update { it.copy(customFieldDefinitions = defs.sortedBy { d -> d.position ?: Int.MAX_VALUE }) }
                }
            }

            repository.getBusiness(businessId).onSuccess { business ->
                defsJob.join()
                _uiState.update { it.copy(business = business, isLoading = false) }
                loadContacts(businessId)
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message) }
                _events.emit(CrmBusinessDetailEvent.BusinessNotFound)
            }
        }
    }

    private fun loadContacts(businessId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isContactsLoading = true) }

            val allContacts = mutableListOf<CrmContactDto>()
            var page = 1
            var hasMore = true

            while (hasMore && page <= 10) {
                contactRepository.getContacts(businessId = businessId, page = page, limit = 50).onSuccess { response ->
                    allContacts.addAll(response.data)
                    hasMore = page < (response.meta?.lastPage ?: 1)
                    page++
                }.onFailure {
                    hasMore = false
                }
            }

            _uiState.update { it.copy(contacts = allContacts, isContactsLoading = false, contactsLoaded = true) }
        }
    }

    fun loadUnassociatedContacts() {
        viewModelScope.launch {
            val allContacts = mutableListOf<CrmContactDto>()
            var page = 1
            var hasMore = true

            while (hasMore && page <= 10) {
                contactRepository.getContacts(page = page, limit = 50).onSuccess { response ->
                    allContacts.addAll(response.data)
                    hasMore = page < (response.meta?.lastPage ?: 1)
                    page++
                }.onFailure {
                    hasMore = false
                }
            }

            // Filter to only contacts without a business_id
            val unassociated = allContacts.filter { it.businessId.isNullOrBlank() }
            if (unassociated.isEmpty()) {
                _events.emit(CrmBusinessDetailEvent.ShowError("No unassociated contacts available"))
            } else {
                _events.emit(CrmBusinessDetailEvent.ShowContactPicker(unassociated))
            }
        }
    }

    fun addContactToBusiness(contact: CrmContactDto, businessId: String) {
        viewModelScope.launch {
            val request = CrmContactRequest(
                firstName = contact.firstName ?: "",
                lastName = contact.lastName,
                email = contact.email,
                phone = contact.phone,
                type = contact.type,
                businessId = businessId
            )
            contactRepository.updateContact(contact.id ?: return@launch, request).onSuccess {
                val name = listOfNotNull(contact.firstName, contact.lastName)
                    .joinToString(" ").ifBlank { "Contact" }
                _events.emit(CrmBusinessDetailEvent.ContactAdded(name))
                loadContacts(businessId)
            }.onFailure {
                _events.emit(CrmBusinessDetailEvent.ShowError("Failed to add contact"))
            }
        }
    }

    fun removeContactFromBusiness(contact: CrmContactDto, businessId: String) {
        viewModelScope.launch {
            contactRepository.removeContactBusiness(contact.id ?: return@launch).onSuccess {
                _events.emit(CrmBusinessDetailEvent.ContactRemoved)
                loadContacts(businessId)
            }.onFailure {
                _events.emit(CrmBusinessDetailEvent.ShowError("Failed to remove contact"))
            }
        }
    }

    fun deleteBusiness(businessId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.deleteBusiness(businessId).onSuccess {
                _events.emit(CrmBusinessDetailEvent.BusinessDeleted)
            }.onFailure {
                _uiState.update { it.copy(isLoading = false) }
                _events.emit(CrmBusinessDetailEvent.ShowError("Failed to delete business"))
            }
        }
    }
}
