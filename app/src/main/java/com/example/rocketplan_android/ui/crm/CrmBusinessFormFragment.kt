package com.example.rocketplan_android.ui.crm

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.example.rocketplan_android.data.model.CrmBusinessRequest
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.repository.CrmBusinessRepository
import com.example.rocketplan_android.databinding.FragmentCrmBusinessFormBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class CrmBusinessFormFragment : Fragment() {

    private var _binding: FragmentCrmBusinessFormBinding? = null
    private val binding get() = _binding!!

    private val args: CrmBusinessFormFragmentArgs by navArgs()
    private lateinit var repository: CrmBusinessRepository

    private val isEditMode: Boolean get() = args.businessId != null

    private val customFieldViews = mutableMapOf<String, View>()
    private var customFieldDefinitions: List<CrmCustomFieldDefinitionDto> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmBusinessFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as RocketPlanApplication
        repository = CrmBusinessRepository(app.authRepository, app.remoteLogger)

        loadCustomFieldDefinitions()

        binding.saveButton.setOnClickListener { saveBusiness() }

        if (isEditMode) {
            binding.formTitle.text = getString(R.string.crm_edit_business)
            requireActivity().title = getString(R.string.crm_edit_business)
            loadExistingBusiness()
        } else {
            binding.formTitle.text = getString(R.string.crm_add_business)
            requireActivity().title = getString(R.string.crm_add_business)
        }
    }

    private fun loadCustomFieldDefinitions() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getCustomFieldDefinitions("business").onSuccess { definitions ->
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
                        val adapter = android.widget.ArrayAdapter(
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
                dataType == "FILE_UPLOAD" -> { /* skip */ }
                else -> {
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

    private fun loadExistingBusiness() {
        val businessId = args.businessId
        if (businessId == null) {
            Toast.makeText(requireContext(), "Business not found", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        binding.loadingIndicator.isVisible = true
        binding.formContent.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            repository.getBusiness(businessId).onSuccess { business ->
                if (!isAdded) return@launch
                populateForm(business)
            }.onFailure {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Business not found", Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            }
            binding.loadingIndicator.isVisible = false
            binding.formContent.isVisible = true
        }
    }

    private fun populateForm(business: CrmBusinessDto) {
        binding.nameInput.setText(business.name.orEmpty())
        binding.emailInput.setText(business.email.orEmpty())
        binding.phoneInput.setText(business.phone.orEmpty())
        binding.websiteInput.setText(business.website.orEmpty())
        binding.descriptionInput.setText(business.description.orEmpty())

        binding.streetInput.setText(business.address1.orEmpty())
        binding.cityInput.setText(business.city.orEmpty())
        binding.stateInput.setText(business.state.orEmpty())
        binding.postalCodeInput.setText(business.postalCode.orEmpty())
        binding.countryInput.setText(business.country.orEmpty())

        business.customFields?.forEach { cfValue ->
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

    private fun saveBusiness() {
        if (isSaving) return
        val name = binding.nameInput.text.toString().trim()
        if (name.isBlank()) {
            binding.nameLayout.error = getString(R.string.crm_business_name_required)
            return
        }
        binding.nameLayout.error = null

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

        val request = CrmBusinessRequest(
            name = name,
            email = email,
            phone = phone,
            website = binding.websiteInput.text.toString().trim().takeIf { it.isNotBlank() },
            description = binding.descriptionInput.text.toString().trim().takeIf { it.isNotBlank() },
            address1 = binding.streetInput.text.toString().trim().takeIf { it.isNotBlank() },
            city = binding.cityInput.text.toString().trim().takeIf { it.isNotBlank() },
            state = binding.stateInput.text.toString().trim().takeIf { it.isNotBlank() },
            postalCode = binding.postalCodeInput.text.toString().trim().takeIf { it.isNotBlank() },
            country = binding.countryInput.text.toString().trim().takeIf { it.isNotBlank() }
        )

        isSaving = true
        binding.saveButton.isEnabled = false
        binding.loadingIndicator.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (isEditMode) {
                repository.updateBusiness(args.businessId!!, request)
            } else {
                repository.createBusiness(request)
            }

            result.onSuccess {
                isSaving = false
                if (!isAdded) return@onSuccess
                Toast.makeText(
                    requireContext(),
                    if (isEditMode) R.string.crm_business_updated else R.string.crm_business_created,
                    Toast.LENGTH_SHORT
                ).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
