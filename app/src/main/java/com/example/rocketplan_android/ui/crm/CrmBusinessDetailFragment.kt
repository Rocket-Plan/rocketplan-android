package com.example.rocketplan_android.ui.crm

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.CrmBusinessDto
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.databinding.FragmentCrmBusinessDetailBinding
import kotlinx.coroutines.launch

class CrmBusinessDetailFragment : Fragment() {

    private var _binding: FragmentCrmBusinessDetailBinding? = null
    private val binding get() = _binding!!

    private val args: CrmBusinessDetailFragmentArgs by navArgs()
    private val viewModel: CrmBusinessDetailViewModel by viewModels()

    private var contactsAdapter: CrmContactsAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmBusinessDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupContactsRecyclerView()

        binding.editButton.setOnClickListener {
            val action = CrmBusinessDetailFragmentDirections
                .actionCrmBusinessDetailToCrmBusinessForm(args.businessId)
            findNavController().navigate(action)
        }

        binding.addContactButton.setOnClickListener {
            viewModel.loadUnassociatedContacts()
        }

        binding.deleteButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.crm_delete_business_title)
                .setMessage(R.string.crm_delete_business_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.deleteBusiness(args.businessId)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadBusiness(args.businessId)
        }

        observeState()
        observeEvents()

        viewModel.loadBusiness(args.businessId)
    }

    private fun setupContactsRecyclerView() {
        contactsAdapter = CrmContactsAdapter(
            onContactClicked = { contact ->
                val action = CrmBusinessDetailFragmentDirections
                    .actionCrmBusinessDetailToCrmContactDetail(contact.id)
                findNavController().navigate(action)
            },
            onRemoveClicked = { contact ->
                confirmRemoveContact(contact)
            }
        )
        binding.contactsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsRecyclerView.adapter = contactsAdapter
    }

    private fun confirmRemoveContact(contact: CrmContactListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_remove_contact_title)
            .setMessage(getString(R.string.crm_remove_contact_message, contact.displayName))
            .setPositiveButton(R.string.crm_remove_contact_from_business) { _, _ ->
                // Find the matching CrmContactDto from current state
                val contactDto = viewModel.uiState.value.contacts.find { it.id == contact.id }
                if (contactDto != null) {
                    viewModel.removeContactFromBusiness(contactDto, args.businessId)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    binding.loadingIndicator.isVisible = state.isLoading && state.business == null
                    binding.swipeRefresh.isVisible = !state.isLoading || state.business != null

                    state.business?.let { bindBusiness(it, state) }

                    // Contacts section
                    binding.contactsLoading.isVisible = state.isContactsLoading
                    if (state.contactsLoaded && !state.isContactsLoading) {
                        if (state.contacts.isEmpty()) {
                            binding.contactsEmpty.isVisible = true
                            binding.contactsRecyclerView.isVisible = false
                        } else {
                            binding.contactsEmpty.isVisible = false
                            binding.contactsRecyclerView.isVisible = true
                            val items = state.contacts.map { contact ->
                                val first = contact.firstName?.trim().orEmpty()
                                val last = contact.lastName?.trim().orEmpty()
                                val displayName = listOfNotNull(
                                    first.takeIf { it.isNotBlank() },
                                    last.takeIf { it.isNotBlank() }
                                ).joinToString(" ").ifBlank { contact.email ?: contact.phone ?: "Unknown" }
                                val initials = buildString {
                                    if (first.isNotBlank()) append(first.first().uppercaseChar())
                                    if (last.isNotBlank()) append(last.first().uppercaseChar())
                                    if (isEmpty() && displayName.isNotBlank()) append(displayName.first().uppercaseChar())
                                }.ifBlank { "?" }
                                CrmContactListItem(
                                    id = contact.id ?: "",
                                    displayName = displayName,
                                    phone = contact.phone,
                                    email = contact.email,
                                    type = contact.type,
                                    initials = initials
                                )
                            }
                            contactsAdapter?.submitList(items)
                        }
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is CrmBusinessDetailEvent.BusinessNotFound -> {
                            Toast.makeText(requireContext(), "Business not found", Toast.LENGTH_SHORT).show()
                            findNavController().popBackStack()
                        }
                        is CrmBusinessDetailEvent.BusinessDeleted -> {
                            Toast.makeText(requireContext(), R.string.crm_business_deleted, Toast.LENGTH_SHORT).show()
                            findNavController().popBackStack()
                        }
                        is CrmBusinessDetailEvent.ShowError -> {
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                        }
                        is CrmBusinessDetailEvent.ContactAdded -> {
                            Toast.makeText(requireContext(), R.string.crm_contact_added_to_business, Toast.LENGTH_SHORT).show()
                        }
                        is CrmBusinessDetailEvent.ContactRemoved -> {
                            Toast.makeText(requireContext(), R.string.crm_contact_removed_from_business, Toast.LENGTH_SHORT).show()
                        }
                        is CrmBusinessDetailEvent.ShowContactPicker -> {
                            showContactPickerDialog(event.contacts)
                        }
                    }
                }
            }
        }
    }

    private fun showContactPickerDialog(contacts: List<CrmContactDto>) {
        val allItems = contacts.map { contact -> contactDtoToListItem(contact) }

        val dialog = Dialog(requireContext(), com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog)
        val dialogView = layoutInflater.inflate(R.layout.dialog_contact_picker, null)
        dialog.setContentView(dialogView)

        // Size the dialog to ~90% width and ~70% height
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.white)

        val closeButton = dialogView.findViewById<View>(R.id.closeButton)
        val searchInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchInput)
        val contactsList = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.contactsList)
        val emptyText = dialogView.findViewById<View>(R.id.emptyText)

        closeButton.setOnClickListener { dialog.dismiss() }

        val pickerAdapter = CrmContactsAdapter(onContactClicked = { item ->
            val selected = contacts.find { it.id == item.id }
            if (selected != null) {
                dialog.dismiss()
                viewModel.addContactToBusiness(selected, args.businessId)
            }
        })

        contactsList.layoutManager = LinearLayoutManager(requireContext())
        contactsList.adapter = pickerAdapter
        pickerAdapter.submitList(allItems)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase()
                val filtered = if (query.isBlank()) allItems else {
                    allItems.filter { item ->
                        item.displayName.lowercase().contains(query) ||
                            item.phone?.lowercase()?.contains(query) == true ||
                            item.email?.lowercase()?.contains(query) == true
                    }
                }
                pickerAdapter.submitList(filtered)
                emptyText.isVisible = filtered.isEmpty()
                contactsList.isVisible = filtered.isNotEmpty()
            }
        })

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private fun contactDtoToListItem(contact: CrmContactDto): CrmContactListItem {
        val first = contact.firstName?.trim().orEmpty()
        val last = contact.lastName?.trim().orEmpty()
        val displayName = listOfNotNull(
            first.takeIf { it.isNotBlank() },
            last.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { contact.email ?: contact.phone ?: "Unknown" }
        val initials = buildString {
            if (first.isNotBlank()) append(first.first().uppercaseChar())
            if (last.isNotBlank()) append(last.first().uppercaseChar())
            if (isEmpty() && displayName.isNotBlank()) append(displayName.first().uppercaseChar())
        }.ifBlank { "?" }
        return CrmContactListItem(
            id = contact.id ?: "",
            displayName = displayName,
            phone = contact.phone,
            email = contact.email,
            type = contact.type,
            initials = initials
        )
    }

    private fun bindBusiness(business: CrmBusinessDto, state: CrmBusinessDetailUiState) {
        binding.businessName.text = business.name?.takeIf { it.isNotBlank() } ?: "Unknown"
        binding.businessEmail.text = business.email ?: "\u2014"
        binding.businessPhone.text = business.phone ?: "\u2014"
        binding.businessWebsite.text = business.website ?: "\u2014"
        binding.businessDescription.text = business.description ?: "\u2014"

        val addressText = listOfNotNull(
            business.address1,
            business.city,
            business.state,
            business.postalCode,
            business.country
        ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "\u2014" }
        binding.businessAddress.text = addressText

        CrmCustomFieldRenderer.render(
            requireContext(),
            binding.customFieldsContainer,
            state.customFieldDefinitions,
            business.customFields
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        contactsAdapter = null
        _binding = null
    }
}
