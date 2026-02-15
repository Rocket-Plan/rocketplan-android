package com.example.rocketplan_android.ui.crm

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.model.CrmOpportunityDto
import com.example.rocketplan_android.data.model.CrmOpportunityRequest
import com.example.rocketplan_android.data.model.CrmPipelineDto
import com.example.rocketplan_android.data.model.CrmPipelineStageDto
import com.example.rocketplan_android.data.repository.CrmContactRepository
import com.example.rocketplan_android.data.repository.CrmOpportunityRepository
import com.example.rocketplan_android.databinding.FragmentCrmOpportunityFormBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CrmOpportunityFormFragment : Fragment() {

    private var _binding: FragmentCrmOpportunityFormBinding? = null
    private val binding get() = _binding!!

    private val args: CrmOpportunityFormFragmentArgs by navArgs()
    private lateinit var opportunityRepository: CrmOpportunityRepository
    private lateinit var contactRepository: CrmContactRepository

    private val isEditMode: Boolean get() = args.opportunityId != null

    private var pipelines: List<CrmPipelineDto> = emptyList()
    private var selectedPipeline: CrmPipelineDto? = null
    private var selectedStage: CrmPipelineStageDto? = null

    private var allContacts: List<CrmContactDto> = emptyList()
    private var selectedContact: CrmContactDto? = null

    private val customFieldViews = mutableMapOf<String, View>()
    private var customFieldDefinitions: List<CrmCustomFieldDefinitionDto> = emptyList()
    private var contactSearchJob: Job? = null
    private var contactAdapter: ContactSearchAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmOpportunityFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as RocketPlanApplication
        opportunityRepository = CrmOpportunityRepository(app.authRepository, app.remoteLogger)
        contactRepository = CrmContactRepository(app.authRepository, app.remoteLogger)

        setupStatusDropdown()

        binding.saveButton.setOnClickListener { saveOpportunity() }

        if (isEditMode) {
            binding.formTitle.text = getString(R.string.crm_edit_opportunity)
            requireActivity().title = getString(R.string.crm_edit_opportunity)
            binding.deleteButton.isVisible = true
            binding.deleteButton.setOnClickListener { confirmDelete() }
        } else {
            binding.formTitle.text = getString(R.string.crm_add_opportunity)
            requireActivity().title = getString(R.string.crm_add_opportunity)
        }

        // Load all dependencies, then populate form if editing
        loadFormData()
    }

    private fun setupStatusDropdown() {
        val statuses = resources.getStringArray(R.array.crm_opportunity_statuses).toList()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, statuses)
        binding.statusDropdown.setAdapter(adapter)
        if (!isEditMode) {
            binding.statusDropdown.setText(statuses.firstOrNull() ?: "Open", false)
        }
    }

    private suspend fun loadContactsAsync() {
        // Load initial page for dropdown, search is handled server-side as user types
        contactRepository.getContacts(page = 1, limit = 20).onSuccess { response ->
            if (!isAdded) return@onSuccess
            allContacts = response.data
            setupContactDropdown()
        }
    }

    private fun getContactDisplayName(contact: CrmContactDto): String {
        val first = contact.firstName?.trim().orEmpty()
        val last = contact.lastName?.trim().orEmpty()
        val name = listOfNotNull(
            first.takeIf { it.isNotBlank() },
            last.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        return when {
            name.isNotBlank() && !contact.email.isNullOrBlank() -> "$name (${contact.email})"
            name.isNotBlank() -> name
            !contact.email.isNullOrBlank() -> contact.email
            !contact.phone.isNullOrBlank() -> contact.phone
            else -> "Unknown"
        }
    }

    private fun setupContactDropdown() {
        contactAdapter = ContactSearchAdapter(allContacts) { getContactDisplayName(it) }
        binding.contactDropdown.setAdapter(contactAdapter)
        binding.contactDropdown.threshold = 1

        binding.contactDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedContact = contactAdapter?.getContact(position)
            selectedContact?.let {
                binding.contactDropdown.setText(getContactDisplayName(it), false)
            }
        }

        // Debounced server-side search as user types
        binding.contactDropdown.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                contactSearchJob?.cancel()
                contactSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    if (query.isNotBlank()) {
                        contactRepository.getContacts(search = query, page = 1, limit = 20)
                            .onSuccess { response ->
                                if (isAdded) contactAdapter?.updateContacts(response.data)
                            }
                    } else {
                        contactAdapter?.updateContacts(allContacts)
                    }
                }
            }
        })
    }

    private fun loadFormData() {
        if (isEditMode) {
            binding.loadingIndicator.isVisible = true
            binding.formContent.isVisible = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Load pipelines, contacts, and custom fields in parallel
            coroutineScope {
                launch { loadPipelinesAsync() }
                launch { loadContactsAsync() }
                launch { loadCustomFieldDefinitionsAsync() }
            }

            if (!isAdded) return@launch

            // Now load and populate existing opportunity if editing
            if (isEditMode) {
                loadExistingOpportunity()
            }
        }
    }

    private suspend fun loadPipelinesAsync() {
        opportunityRepository.getPipelines().onSuccess { result ->
            if (!isAdded) return@onSuccess
            pipelines = result
            val pipelineNames = result.mapNotNull { it.name }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, pipelineNames)
            binding.pipelineDropdown.setAdapter(adapter)

            binding.pipelineDropdown.setOnItemClickListener { _, _, position, _ ->
                selectedPipeline = pipelines.getOrNull(position)
                updateStageDropdown()
            }

            // Auto-select pipeline from nav arg (for new opportunities)
            if (!isEditMode && args.pipelineId != null) {
                val pipeline = pipelines.find { it.id == args.pipelineId }
                if (pipeline != null) {
                    selectedPipeline = pipeline
                    binding.pipelineDropdown.setText(pipeline.name, false)
                    updateStageDropdown()
                    val firstStage = pipeline.stages?.sortedBy { it.sortOrder }?.firstOrNull()
                    if (firstStage != null) {
                        selectedStage = firstStage
                        binding.stageDropdown.setText(firstStage.name, false)
                    }
                }
            }
        }
    }

    private fun updateStageDropdown() {
        val stages = selectedPipeline?.stages?.sortedBy { it.sortOrder } ?: emptyList()
        val stageNames = stages.mapNotNull { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, stageNames)
        binding.stageDropdown.setAdapter(adapter)
        binding.stageDropdown.setText("", false)
        selectedStage = null

        binding.stageDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedStage = stages.getOrNull(position)
        }
    }

    private suspend fun loadCustomFieldDefinitionsAsync() {
        contactRepository.getCustomFieldDefinitions("opportunity").onSuccess { definitions ->
            if (!isAdded) return@onSuccess
            customFieldDefinitions = definitions.filter { it.isEditable != false }
                .sortedBy { it.position ?: Int.MAX_VALUE }
            buildCustomFieldInputs()
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
                        setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, definition.picklistOptions))
                    }
                    layout.addView(dropdown)
                    container.addView(layout)
                    customFieldViews[fieldId] = dropdown
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
                        "LARGE_TEXT" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
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

    private suspend fun loadExistingOpportunity() {
        val opportunityId = args.opportunityId ?: return

        val result = opportunityRepository.getOpportunity(opportunityId)

        if (!isAdded) return

        result.onSuccess { opportunity ->
            populateForm(opportunity)
        }.onFailure {
            Toast.makeText(requireContext(), "Opportunity not found", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
        binding.loadingIndicator.isVisible = false
        binding.formContent.isVisible = true
    }

    private fun populateForm(opportunity: CrmOpportunityDto) {
        binding.nameInput.setText(opportunity.name.orEmpty())
        binding.sourceInput.setText(opportunity.source.orEmpty())
        opportunity.monetaryValue?.let {
            binding.monetaryValueInput.setText(it.toString())
        }

        opportunity.status?.let { status ->
            binding.statusDropdown.setText(status.replaceFirstChar { it.uppercaseChar() }, false)
        }

        // Contact - set the selected contact and display name
        opportunity.contact?.let { contact ->
            selectedContact = contact
            binding.contactDropdown.setText(getContactDisplayName(contact), false)
        } ?: run {
            // If no nested contact but we have a contactId, find it in loaded contacts
            opportunity.contactId?.let { cId ->
                val contact = allContacts.find { it.id == cId }
                if (contact != null) {
                    selectedContact = contact
                    binding.contactDropdown.setText(getContactDisplayName(contact), false)
                }
            }
        }

        // Set pipeline and stage
        val pipeline = pipelines.find { it.id == opportunity.pipelineId }
        if (pipeline != null) {
            selectedPipeline = pipeline
            binding.pipelineDropdown.setText(pipeline.name, false)
            updateStageDropdown()

            val stage = pipeline.stages?.find { it.id == opportunity.pipelineStageId }
            if (stage != null) {
                selectedStage = stage
                binding.stageDropdown.setText(stage.name, false)
            }
        }

        // Custom fields
        opportunity.customFields?.forEach { cfValue ->
            val fieldId = cfValue.id ?: return@forEach
            val view = customFieldViews[fieldId] ?: return@forEach
            val value = cfValue.fieldValue.orEmpty()
            when (view) {
                is AutoCompleteTextView -> view.setText(value, false)
                is TextInputEditText -> view.setText(value)
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_delete_opportunity_title)
            .setMessage(R.string.crm_delete_opportunity_message)
            .setPositiveButton(R.string.delete) { _, _ -> deleteOpportunity() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteOpportunity() {
        val opportunityId = args.opportunityId ?: return
        binding.loadingIndicator.isVisible = true
        binding.saveButton.isEnabled = false
        binding.deleteButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            opportunityRepository.deleteOpportunity(opportunityId).onSuccess {
                if (!isAdded) return@onSuccess
                Toast.makeText(requireContext(), R.string.crm_opportunity_deleted, Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            }.onFailure { error ->
                if (!isAdded) return@onFailure
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.crm_delete_failed),
                    Toast.LENGTH_SHORT
                ).show()
                binding.loadingIndicator.isVisible = false
                binding.saveButton.isEnabled = true
                binding.deleteButton.isEnabled = true
            }
        }
    }

    private var isSaving = false

    private fun saveOpportunity() {
        if (isSaving) return
        val name = binding.nameInput.text.toString().trim()
        if (name.isBlank()) {
            binding.nameLayout.error = getString(R.string.crm_opportunity_name_required)
            return
        }
        binding.nameLayout.error = null

        val pipelineId = selectedPipeline?.id
        if (pipelineId == null) {
            binding.pipelineLayout.error = getString(R.string.crm_pipeline_required)
            return
        }
        binding.pipelineLayout.error = null

        val stageId = selectedStage?.id
        if (stageId == null) {
            binding.stageLayout.error = getString(R.string.crm_stage_required)
            return
        }
        binding.stageLayout.error = null

        if (selectedContact == null) {
            binding.contactLayout.error = getString(R.string.crm_contact_required)
            return
        }
        binding.contactLayout.error = null

        val monetaryValueText = binding.monetaryValueInput.text.toString().trim()
        val monetaryValue = monetaryValueText.toDoubleOrNull()

        val statusDisplay = binding.statusDropdown.text.toString().trim()
        val status = statusDisplay.lowercase().takeIf { it.isNotBlank() }

        val request = CrmOpportunityRequest(
            name = name,
            pipelineId = pipelineId,
            pipelineStageId = stageId,
            contactId = selectedContact?.id,
            monetaryValue = monetaryValue,
            status = status,
            source = binding.sourceInput.text.toString().trim().takeIf { it.isNotBlank() }
        )

        isSaving = true
        binding.saveButton.isEnabled = false
        binding.loadingIndicator.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (isEditMode) {
                opportunityRepository.updateOpportunity(args.opportunityId!!, request)
            } else {
                opportunityRepository.createOpportunity(request)
            }

            result.onSuccess { savedOpp ->
                isSaving = false
                if (!isAdded) return@onSuccess
                val oppId = savedOpp.id ?: args.opportunityId
                if (oppId != null && customFieldViews.isNotEmpty()) {
                    val cfResult = saveCustomFields(oppId)
                    if (cfResult?.isFailure == true) {
                        Toast.makeText(requireContext(), "Saved but custom fields failed to save", Toast.LENGTH_SHORT).show()
                    }
                }

                Toast.makeText(
                    requireContext(),
                    if (isEditMode) R.string.crm_opportunity_updated else R.string.crm_opportunity_created,
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().popBackStack()
            }.onFailure { error ->
                if (!isAdded) return@onFailure
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.crm_opportunity_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
                isSaving = false
                binding.saveButton.isEnabled = true
                binding.loadingIndicator.isVisible = false
            }
        }
    }

    private suspend fun saveCustomFields(opportunityId: String): Result<*>? {
        val payload = customFieldViews.mapNotNull { (fieldId, view) ->
            val value = when (view) {
                is AutoCompleteTextView -> view.text.toString().trim()
                is TextInputEditText -> view.text.toString().trim()
                else -> return@mapNotNull null
            }
            mapOf("id" to fieldId, "field_value" to value)
        }

        return if (payload.isNotEmpty()) {
            opportunityRepository.updateOpportunityCustomFields(opportunityId, payload)
        } else null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Simple adapter for contact dropdown. Server-side search is handled by the
     * debounced TextWatcher in setupContactDropdown() — no blocking calls needed.
     * Implements Filterable with a pass-through filter so AutoCompleteTextView accepts it.
     */
    private class ContactSearchAdapter(
        private var contacts: List<CrmContactDto>,
        private val displayNameFn: (CrmContactDto) -> String
    ) : android.widget.BaseAdapter(), android.widget.Filterable {

        fun getContact(position: Int): CrmContactDto = contacts[position]

        fun updateContacts(newContacts: List<CrmContactDto>) {
            contacts = newContacts
            notifyDataSetChanged()
        }

        override fun getCount(): Int = contacts.size

        override fun getItem(position: Int): String = displayNameFn(contacts[position])

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val textView = (convertView as? android.widget.TextView)
                ?: android.widget.TextView(parent.context).apply {
                    setPadding(32, 24, 32, 24)
                    textSize = 14f
                }
            textView.text = getItem(position)
            return textView
        }

        override fun getFilter(): android.widget.Filter = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                // No-op: server-side search is handled by the TextWatcher
                return FilterResults().apply {
                    values = contacts
                    count = contacts.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                // Data is updated externally via updateContacts()
            }
        }
    }
}
