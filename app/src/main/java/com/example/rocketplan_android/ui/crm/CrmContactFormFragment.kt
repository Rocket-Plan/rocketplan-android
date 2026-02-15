package com.example.rocketplan_android.ui.crm

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmBusinessDto
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.model.CrmContactRequest
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.repository.CrmBusinessRepository
import com.example.rocketplan_android.data.repository.CrmContactRepository
import com.example.rocketplan_android.databinding.FragmentCrmContactFormBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class CrmContactFormFragment : Fragment() {

    private var _binding: FragmentCrmContactFormBinding? = null
    private val binding get() = _binding!!

    private val args: CrmContactFormFragmentArgs by navArgs()
    private lateinit var repository: CrmContactRepository
    private lateinit var businessRepository: CrmBusinessRepository

    private val isEditMode: Boolean get() = args.contactId != null

    // Custom field definitions and their corresponding input views
    private val customFieldViews = mutableMapOf<String, View>() // fieldId -> input view
    private var customFieldDefinitions: List<CrmCustomFieldDefinitionDto> = emptyList()

    // Business dropdown data
    private var businesses: List<CrmBusinessDto> = emptyList()
    private var selectedBusinessId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmContactFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as RocketPlanApplication
        repository = CrmContactRepository(app.authRepository, app.remoteLogger)
        businessRepository = CrmBusinessRepository(app.authRepository, app.remoteLogger)

        setupTypeDropdown()
        setupBusinessDropdown()
        loadCustomFieldDefinitions()

        binding.saveButton.setOnClickListener { saveContact() }

        if (isEditMode) {
            binding.formTitle.text = getString(R.string.crm_edit_contact)
            requireActivity().title = getString(R.string.crm_edit_contact)
            loadExistingContact()
        } else {
            binding.formTitle.text = getString(R.string.crm_add_contact)
            requireActivity().title = getString(R.string.crm_add_contact)
        }
    }

    private fun setupTypeDropdown() {
        val knownTypes = listOf("lead", "referral_partner")
        viewLifecycleOwner.lifecycleScope.launch {
            val apiTypes = repository.getContactTypes().getOrDefault(emptyList())
            if (!isAdded) return@launch
            val allTypes = (knownTypes + apiTypes).distinct().sorted()
            val displayTypes = allTypes.map { it.replace("_", " ").replaceFirstChar { c -> c.uppercaseChar() } }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayTypes)
            binding.typeDropdown.setAdapter(adapter)
        }
    }

    private fun setupBusinessDropdown() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Fetch all businesses for the dropdown (paginate to get a reasonable list)
            val allBusinesses = mutableListOf<CrmBusinessDto>()
            var page = 1
            var hasMore = true
            while (hasMore && page <= 5) {
                businessRepository.getBusinesses(page = page, limit = 50).onSuccess { response ->
                    allBusinesses.addAll(response.data)
                    hasMore = page < (response.meta?.lastPage ?: 1)
                    page++
                }.onFailure {
                    hasMore = false
                }
            }

            if (!isAdded) return@launch
            businesses = allBusinesses

            val displayNames = listOf("(None)") + businesses.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
            binding.businessDropdown.setAdapter(adapter)

            binding.businessDropdown.setOnItemClickListener { _, _, position, _ ->
                selectedBusinessId = if (position == 0) null else businesses.getOrNull(position - 1)?.id
            }
        }
    }

    private fun loadCustomFieldDefinitions() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getCustomFieldDefinitions("contact").onSuccess { definitions ->
                if (!isAdded) return@onSuccess
                customFieldDefinitions = definitions.filter { it.isEditable != false }
                    .sortedBy { it.position ?: Int.MAX_VALUE }
                buildCustomFieldInputs()
            }
        }
    }

    private fun buildCustomFieldInputs() {
        val container = binding.customFieldsContainer
        container.removeAllViews()
        customFieldViews.clear()

        for (definition in customFieldDefinitions) {
            val fieldId = definition.id ?: continue
            val fieldName = definition.name ?: continue
            val dataType = definition.dataType

            when {
                !definition.picklistOptions.isNullOrEmpty() -> {
                    // Dropdown for any field with picklist options
                    val layout = TextInputLayout(
                        requireContext(),
                        null,
                        com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
                    ).apply {
                        hint = fieldName
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (12 * resources.displayMetrics.density).toInt()
                        }
                    }
                    val dropdown = AutoCompleteTextView(requireContext()).apply {
                        inputType = android.text.InputType.TYPE_NULL
                        val adapter = ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            definition.picklistOptions
                        )
                        setAdapter(adapter)
                    }
                    layout.addView(dropdown)
                    container.addView(layout)
                    customFieldViews[fieldId] = dropdown
                }
                dataType == "LARGE_TEXT" -> {
                    val layout = TextInputLayout(
                        requireContext(),
                        null,
                        com.google.android.material.R.attr.textInputOutlinedStyle
                    ).apply {
                        hint = fieldName
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (12 * resources.displayMetrics.density).toInt()
                        }
                    }
                    val editText = TextInputEditText(requireContext()).apply {
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        minLines = 3
                    }
                    layout.addView(editText)
                    container.addView(layout)
                    customFieldViews[fieldId] = editText
                }
                dataType == "FILE_UPLOAD" -> {
                    // Skip file upload fields for now
                }
                else -> {
                    // Default: text input (TEXT, NUMBER, DATE, etc.)
                    val layout = TextInputLayout(
                        requireContext(),
                        null,
                        com.google.android.material.R.attr.textInputOutlinedStyle
                    ).apply {
                        hint = fieldName
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (12 * resources.displayMetrics.density).toInt()
                        }
                    }
                    val inputType = when (dataType) {
                        "NUMBER", "FLOAT", "MONETARY" ->
                            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        "DATE" -> android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE
                        else -> android.text.InputType.TYPE_CLASS_TEXT
                    }
                    val editText = TextInputEditText(requireContext()).apply {
                        this.inputType = inputType
                    }
                    layout.addView(editText)
                    container.addView(layout)
                    customFieldViews[fieldId] = editText
                }
            }
        }
    }

    private fun loadExistingContact() {
        val contactId = args.contactId
        if (contactId == null) {
            Toast.makeText(requireContext(), "Contact not found", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        binding.loadingIndicator.isVisible = true
        binding.formContent.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            repository.getContact(contactId).onSuccess { contact ->
                if (!isAdded) return@launch
                populateForm(contact)
            }.onFailure {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Contact not found", Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            }
            binding.loadingIndicator.isVisible = false
            binding.formContent.isVisible = true
        }
    }

    private fun populateForm(contact: CrmContactDto) {
        binding.firstNameInput.setText(contact.firstName.orEmpty())
        binding.lastNameInput.setText(contact.lastName.orEmpty())
        binding.emailInput.setText(contact.email.orEmpty())
        binding.phoneInput.setText(contact.phone.orEmpty())
        binding.sourceInput.setText(contact.source.orEmpty())
        binding.companyNameInput.setText(contact.companyName.orEmpty())
        binding.websiteInput.setText(contact.website.orEmpty())

        contact.type?.let { type ->
            val display = type.replace("_", " ").replaceFirstChar { it.uppercaseChar() }
            binding.typeDropdown.setText(display, false)
        }

        // Business dropdown
        contact.businessId?.let { bizId ->
            selectedBusinessId = bizId
            val matchingBusiness = businesses.find { it.id == bizId }
            if (matchingBusiness != null) {
                binding.businessDropdown.setText(matchingBusiness.name ?: bizId, false)
            } else {
                // Business list may not be loaded yet — set text with ID as fallback
                binding.businessDropdown.setText(bizId, false)
            }
        }

        // Address fields (flat)
        binding.streetInput.setText(contact.address1.orEmpty())
        binding.cityInput.setText(contact.city.orEmpty())
        binding.stateInput.setText(contact.state.orEmpty())
        binding.postalCodeInput.setText(contact.postalCode.orEmpty())
        binding.countryInput.setText(contact.country.orEmpty())

        // Populate custom field values
        contact.customFields?.forEach { cfValue ->
            val fieldId = cfValue.id ?: return@forEach
            val view = customFieldViews[fieldId] ?: return@forEach
            val value = cfValue.fieldValue.orEmpty()
            when (view) {
                is AutoCompleteTextView -> view.setText(value, false)
                is TextInputEditText -> view.setText(value)
            }
        }
    }

    private var isSaving = false

    private fun saveContact() {
        if (isSaving) return
        val firstName = binding.firstNameInput.text.toString().trim()
        if (firstName.isBlank()) {
            binding.firstNameLayout.error = getString(R.string.crm_first_name_required)
            return
        }
        binding.firstNameLayout.error = null

        // Validate email
        val email = binding.emailInput.text.toString().trim().takeIf { it.isNotBlank() }
        if (email != null && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = getString(R.string.crm_invalid_email)
            return
        }
        binding.emailLayout.error = null

        // Validate phone
        val phone = binding.phoneInput.text.toString().trim().takeIf { it.isNotBlank() }
        if (phone != null && !Patterns.PHONE.matcher(phone).matches()) {
            binding.phoneLayout.error = getString(R.string.crm_invalid_phone)
            return
        }
        binding.phoneLayout.error = null

        val typeDisplay = binding.typeDropdown.text.toString().trim()
        val type = typeDisplay.lowercase().replace(" ", "_").takeIf { it.isNotBlank() }

        val request = CrmContactRequest(
            firstName = firstName,
            lastName = binding.lastNameInput.text.toString().trim().takeIf { it.isNotBlank() },
            email = email,
            phone = phone,
            type = type,
            source = binding.sourceInput.text.toString().trim().takeIf { it.isNotBlank() },
            address1 = binding.streetInput.text.toString().trim().takeIf { it.isNotBlank() },
            city = binding.cityInput.text.toString().trim().takeIf { it.isNotBlank() },
            state = binding.stateInput.text.toString().trim().takeIf { it.isNotBlank() },
            postalCode = binding.postalCodeInput.text.toString().trim().takeIf { it.isNotBlank() },
            country = binding.countryInput.text.toString().trim().takeIf { it.isNotBlank() },
            companyName = binding.companyNameInput.text.toString().trim().takeIf { it.isNotBlank() },
            website = binding.websiteInput.text.toString().trim().takeIf { it.isNotBlank() },
            businessId = selectedBusinessId
        )

        isSaving = true
        binding.saveButton.isEnabled = false
        binding.loadingIndicator.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (isEditMode) {
                repository.updateContact(args.contactId!!, request)
            } else {
                repository.createContact(request)
            }

            result.onSuccess { savedContact ->
                isSaving = false
                if (!isAdded) return@onSuccess
                // Save custom fields and await result before navigating
                val contactId = savedContact.id ?: args.contactId
                var customFieldsFailed = false
                if (contactId != null && customFieldViews.isNotEmpty()) {
                    val cfResult = saveCustomFields(contactId)
                    if (cfResult?.isFailure == true) {
                        customFieldsFailed = true
                    }
                }

                if (customFieldsFailed) {
                    Toast.makeText(requireContext(), R.string.crm_custom_fields_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        if (isEditMode) R.string.crm_contact_updated else R.string.crm_contact_created,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                findNavController().popBackStack()
            }.onFailure { error ->
                if (!isAdded) return@onFailure
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.crm_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
                isSaving = false
                binding.saveButton.isEnabled = true
                binding.loadingIndicator.isVisible = false
            }
        }
    }

    private suspend fun saveCustomFields(contactId: String): Result<*>? {
        val customFieldPayload = customFieldViews.mapNotNull { (fieldId, view) ->
            val value = when (view) {
                is AutoCompleteTextView -> view.text.toString().trim()
                is TextInputEditText -> view.text.toString().trim()
                else -> return@mapNotNull null
            }
            mapOf("id" to fieldId, "field_value" to value)
        }

        return if (customFieldPayload.isNotEmpty()) {
            repository.updateContactCustomFields(contactId, customFieldPayload)
        } else null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
