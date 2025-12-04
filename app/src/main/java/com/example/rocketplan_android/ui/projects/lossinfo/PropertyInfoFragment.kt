package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.ui.projects.PropertyType
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch

class PropertyInfoFragment : Fragment() {

    private val projectId: Long by lazy {
        requireArguments().getLong(ARG_PROJECT_ID)
    }

    private val viewModel: ProjectLossInfoViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        factoryProducer = {
            ProjectLossInfoViewModel.provideFactory(requireActivity().application, projectId)
        }
    )

    private lateinit var loadingIndicator: CircularProgressIndicator
    private lateinit var headerCard: MaterialCardView
    private lateinit var projectTitleView: MaterialTextView
    private lateinit var projectCodeView: MaterialTextView
    private lateinit var projectCreatedView: MaterialTextView
    private lateinit var projectTypeInput: MaterialAutoCompleteTextView
    private lateinit var classificationGroup: MaterialButtonToggleGroup
    private lateinit var residentialToggle: MaterialButton
    private lateinit var commercialToggle: MaterialButton
    private lateinit var bothToggle: MaterialButton
    private lateinit var yearBuiltInput: TextInputEditText
    private lateinit var buildingNameInput: TextInputEditText
    private lateinit var referralNameInput: TextInputEditText
    private lateinit var referralPhoneInput: TextInputEditText
    private lateinit var platinumAgentInput: MaterialAutoCompleteTextView
    private lateinit var asbestosView: MaterialTextView
    private lateinit var saveButton: MaterialButton

    private val propertyTypes = PropertyType.entries.toList()
    private var selectedPropertyType: PropertyType? = null
    private var selectedPlatinumAgent: Boolean? = null
    private var selectedAsbestosStatusId: Int? = null
    private var isResidentialSelected = false
    private var isCommercialSelected = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_property_info_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadingIndicator = view.findViewById(R.id.propertyInfoLoading)
        headerCard = view.findViewById(R.id.propertyHeaderCard)
        projectTitleView = view.findViewById(R.id.projectTitleValue)
        projectCodeView = view.findViewById(R.id.projectCodeValue)
        projectCreatedView = view.findViewById(R.id.projectCreatedValue)
        projectTypeInput = view.findViewById(R.id.projectTypeInput)
        classificationGroup = view.findViewById(R.id.propertyClassificationGroup)
        residentialToggle = view.findViewById(R.id.residentialToggle)
        commercialToggle = view.findViewById(R.id.commercialToggle)
        bothToggle = view.findViewById(R.id.bothToggle)
        yearBuiltInput = view.findViewById(R.id.yearBuiltInput)
        buildingNameInput = view.findViewById(R.id.buildingNameInput)
        referralNameInput = view.findViewById(R.id.referralNameInput)
        referralPhoneInput = view.findViewById(R.id.referralPhoneInput)
        platinumAgentInput = view.findViewById(R.id.platinumAgentInput)
        asbestosView = view.findViewById(R.id.asbestosValue)
        saveButton = view.findViewById(R.id.savePropertyInfoButton)

        bindInputs()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.events.collect { event ->
                    when (event) {
                        ProjectLossInfoEvent.SaveSuccess -> {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.loss_info_save_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        is ProjectLossInfoEvent.SaveFailed -> {
                            val message = event.message.ifBlank {
                                getString(R.string.loss_info_save_failed)
                            }
                            Toast.makeText(
                                requireContext(),
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is ProjectLossInfoEvent.ClaimUpdated,
                        is ProjectLossInfoEvent.ClaimUpdateFailed -> Unit
                    }
                }
            }
        }
    }

    private fun render(state: ProjectLossInfoUiState) {
        loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        headerCard.visibility = if (state.isLoading) View.INVISIBLE else View.VISIBLE
        saveButton.isEnabled = !state.isSaving && !state.isLoading

        projectTitleView.text = state.projectTitle ?: getString(R.string.loss_info_property_placeholder_title)
        projectCodeView.text = state.projectCode ?: getString(R.string.loss_info_property_placeholder_code)
        projectCreatedView.text = state.projectCreatedAt ?: getString(R.string.loss_info_value_not_available)

        val property = state.property
        selectedPropertyType = resolvePropertyType(property)
        projectTypeInput.setText(
            selectedPropertyType?.let { friendlyPropertyTypeLabel(it) }.orEmpty(),
            false
        )

        setClassification(property)

        asbestosView.text = property?.asbestosStatus?.title
            ?: getString(R.string.loss_info_value_not_available)
        selectedAsbestosStatusId = property?.asbestosStatusId?.toInt()

        yearBuiltInput.setText(property?.yearBuilt?.takeIf { it > 0 }?.toString().orEmpty())
        buildingNameInput.setText(property?.name.orEmpty())
        referralNameInput.setText(property?.referredByName.orEmpty())
        referralPhoneInput.setText(property?.referredByPhone.orEmpty())

        selectedPlatinumAgent = property?.isPlatinumAgent
        platinumAgentInput.setText(
            selectedPlatinumAgent?.let {
                if (it) getString(R.string.loss_info_yes) else getString(R.string.loss_info_no)
            }.orEmpty(),
            false
        )
    }

    private fun bindInputs() {
        val propertyTypeLabels = propertyTypes.map { friendlyPropertyTypeLabel(it) }
        val propertyTypeAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, propertyTypeLabels)
        projectTypeInput.setAdapter(propertyTypeAdapter)
        projectTypeInput.setOnItemClickListener { _, _, position, _ ->
            selectedPropertyType = propertyTypes.getOrNull(position)
        }

        val yesNoAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            listOf(getString(R.string.loss_info_yes), getString(R.string.loss_info_no))
        )
        platinumAgentInput.setAdapter(yesNoAdapter)
        platinumAgentInput.setOnItemClickListener { _, _, position, _ ->
            selectedPlatinumAgent = position == 0
        }

        classificationGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                residentialToggle.id -> {
                    isResidentialSelected = true
                    isCommercialSelected = false
                }

                commercialToggle.id -> {
                    isResidentialSelected = false
                    isCommercialSelected = true
                }

                bothToggle.id -> {
                    isResidentialSelected = true
                    isCommercialSelected = true
                }
            }
        }

        saveButton.setOnClickListener { handleSave() }
    }

    private fun handleSave() {
        val form = PropertyInfoFormInput(
            propertyType = selectedPropertyType,
            referredByName = referralNameInput.text?.toString()?.trim().orEmpty().ifBlank { null },
            referredByPhone = referralPhoneInput.text?.toString()?.trim().orEmpty().ifBlank { null },
            isPlatinumAgent = selectedPlatinumAgent,
            isResidential = if (isResidentialSelected || isCommercialSelected) isResidentialSelected else null,
            isCommercial = if (isResidentialSelected || isCommercialSelected) isCommercialSelected else null,
            asbestosStatusId = selectedAsbestosStatusId,
            yearBuilt = yearBuiltInput.text?.toString()?.trim()?.toIntOrNull(),
            buildingName = buildingNameInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        )
        viewModel.savePropertyInfo(form)
    }

    private fun setClassification(property: PropertyDto?) {
        val isResidential = property?.isResidential == true
        val isCommercial = property?.isCommercial == true
        isResidentialSelected = isResidential
        isCommercialSelected = isCommercial
        when {
            isResidential && isCommercial -> classificationGroup.check(bothToggle.id)
            isResidential -> classificationGroup.check(residentialToggle.id)
            isCommercial -> classificationGroup.check(commercialToggle.id)
            else -> classificationGroup.clearChecked()
        }
    }

    private fun resolvePropertyType(property: PropertyDto?): PropertyType? {
        val fromId = property?.propertyTypeId?.toInt()
        fromId?.let { id ->
            propertyTypes.firstOrNull { it.propertyTypeId == id }?.let { return it }
        }
        return PropertyType.fromApiValue(property?.propertyType)
    }

    private fun friendlyPropertyTypeLabel(type: PropertyType): String = when (type) {
        PropertyType.SINGLE_UNIT -> "Single Unit"
        PropertyType.MULTI_UNIT -> "Multi Unit"
        PropertyType.EXTERIOR -> "Exterior"
        PropertyType.COMMERCIAL -> "Commercial"
        PropertyType.SINGLE_LOCATION -> "Single Location"
    }

    companion object {
        private const val ARG_PROJECT_ID = "arg_project_id"

        fun newInstance(projectId: Long): PropertyInfoFragment {
            return PropertyInfoFragment().apply {
                arguments = Bundle().apply { putLong(ARG_PROJECT_ID, projectId) }
            }
        }
    }
}
