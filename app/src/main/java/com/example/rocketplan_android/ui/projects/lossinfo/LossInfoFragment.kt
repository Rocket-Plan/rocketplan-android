package com.example.rocketplan_android.ui.projects.lossinfo

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.DamageCauseDto
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class LossInfoFragment : Fragment() {

    private val projectId: Long by lazy {
        requireArguments().getLong(ARG_PROJECT_ID)
    }

    private val viewModel: ProjectLossInfoViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        factoryProducer = {
            ProjectLossInfoViewModel.provideFactory(requireActivity().application, projectId)
        }
    )

    private lateinit var damageTypesGroup: ChipGroup
    private lateinit var damageCauseLayout: TextInputLayout
    private lateinit var damageCauseInput: MaterialAutoCompleteTextView
    private lateinit var damageCategoryInput: TextInputEditText
    private lateinit var lossClassInput: TextInputEditText
    private lateinit var lossDateInput: TextInputEditText
    private lateinit var callReceivedInput: TextInputEditText
    private lateinit var crewDispatchedInput: TextInputEditText
    private lateinit var arrivedOnSiteInput: TextInputEditText
    private lateinit var affectedLocationsValue: MaterialTextView
    private lateinit var saveButton: MaterialButton

    private var allDamageCauses: List<DamageCauseDto> = emptyList()
    private var filteredDamageCauses: List<DamageCauseDto> = emptyList()
    private val selectedDamageTypeIds = mutableSetOf<Long>()
    private var selectedDamageCauseId: Long? = null
    private var lossDate: Date? = null
    private var callReceived: Date? = null
    private var crewDispatched: Date? = null
    private var arrivedOnSite: Date? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_loss_info_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        damageTypesGroup = view.findViewById(R.id.damageTypesGroup)
        damageCauseLayout = view.findViewById(R.id.damageCauseLayout)
        damageCauseInput = view.findViewById(R.id.damageCauseInput)
        damageCategoryInput = view.findViewById(R.id.damageCategoryInput)
        lossClassInput = view.findViewById(R.id.lossClassInput)
        lossDateInput = view.findViewById(R.id.lossDateInput)
        callReceivedInput = view.findViewById(R.id.callReceivedInput)
        crewDispatchedInput = view.findViewById(R.id.crewDispatchedInput)
        arrivedOnSiteInput = view.findViewById(R.id.arrivedOnSiteInput)
        affectedLocationsValue = view.findViewById(R.id.affectedLocationsValue)
        saveButton = view.findViewById(R.id.saveLossInfoButton)

        bindInputs()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
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
                        is ProjectLossInfoEvent.PropertyMissing -> Unit
                        is ProjectLossInfoEvent.ClaimUpdated,
                        is ProjectLossInfoEvent.ClaimUpdateFailed -> Unit
                    }
                }
            }
        }
    }

    private fun render(state: ProjectLossInfoUiState) {
        renderDamageTypes(state)

        lossDate = state.lossDate
        callReceived = state.callReceived
        crewDispatched = state.crewDispatched
        arrivedOnSite = state.arrivedOnSite
        selectedDamageCauseId = state.selectedDamageCause?.id

        renderDamageCauses(state)
        damageCategoryInput.setText(state.damageCategory?.toString().orEmpty())
        lossClassInput.setText(state.lossClass?.toString().orEmpty())
        setDateInputText(lossDateInput, lossDate)
        setDateInputText(callReceivedInput, callReceived)
        setDateInputText(crewDispatchedInput, crewDispatched)
        setDateInputText(arrivedOnSiteInput, arrivedOnSite)

        affectedLocationsValue.text = if (state.affectedLocations.isNotEmpty()) {
            state.affectedLocations.joinToString(separator = "\n")
        } else {
            getString(R.string.loss_info_locations_placeholder)
        }

        saveButton.isEnabled = !state.isSaving && !state.isLoading
    }

    private fun renderDamageTypes(state: ProjectLossInfoUiState) {
        damageTypesGroup.removeAllViews()
        selectedDamageTypeIds.clear()
        if (state.damageTypes.isEmpty()) {
            val chip = Chip(requireContext()).apply {
                text = getString(R.string.loss_info_value_not_available)
                isCheckable = false
                isClickable = false
            }
            damageTypesGroup.addView(chip)
            return
        }

        selectedDamageTypeIds.addAll(state.selectedDamageTypeIds)

        state.damageTypes.forEach { damageType ->
            val chip = Chip(requireContext()).apply {
                text = damageType.title ?: damageType.name ?: damageType.id.toString()
                isCheckable = true
                isClickable = true
                isChecked = state.selectedDamageTypeIds.contains(damageType.id)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedDamageTypeIds.add(damageType.id)
                    } else {
                        selectedDamageTypeIds.remove(damageType.id)
                        clearDamageCauseIfFilteredOut(damageType.id)
                    }
                    applyDamageCauseFilter()
                }
            }
            damageTypesGroup.addView(chip)
        }
    }

    private fun renderDamageCauses(state: ProjectLossInfoUiState) {
        allDamageCauses = state.damageCauses
        applyDamageCauseFilter()
    }

    private fun applyDamageCauseFilter() {
        filteredDamageCauses = when {
            allDamageCauses.isEmpty() -> emptyList()
            selectedDamageTypeIds.isEmpty() -> allDamageCauses
            else -> {
                val matching = allDamageCauses.filter { cause ->
                    val typeId = cause.propertyDamageType?.id
                    typeId != null && selectedDamageTypeIds.contains(typeId)
                }
                if (matching.isNotEmpty()) matching else allDamageCauses
            }
        }
        val labels = filteredDamageCauses.map { cause ->
            cause.name ?: getString(R.string.loss_info_value_not_available)
        }
        val adapter = ArrayAdapter(requireContext(), R.layout.list_item_dropdown, labels)
        damageCauseInput.setAdapter(adapter)

        val selected = filteredDamageCauses.firstOrNull { it.id == selectedDamageCauseId }
        if (selected != null) {
            damageCauseInput.setText(selected.name.orEmpty(), false)
        } else {
            selectedDamageCauseId = null
            damageCauseInput.setText("", false)
        }

        val hasOptions = filteredDamageCauses.isNotEmpty()
        damageCauseLayout.isEnabled = hasOptions
        damageCauseInput.isEnabled = hasOptions
    }

    private fun bindInputs() {
        damageCauseInput.threshold = 0
        damageCauseInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                showKeyboard(view)
                if (damageCauseInput.isEnabled && (damageCauseInput.adapter?.count ?: 0) > 0) {
                    damageCauseInput.showDropDown()
                }
            }
        }
        damageCauseInput.setOnClickListener { view ->
            showKeyboard(view)
            if (damageCauseInput.isEnabled && (damageCauseInput.adapter?.count ?: 0) > 0) {
                damageCauseInput.showDropDown()
            }
        }
        damageCauseInput.doAfterTextChanged { text ->
            if (text.isNullOrBlank()) {
                selectedDamageCauseId = null
                return@doAfterTextChanged
            }
            val match = filteredDamageCauses.firstOrNull {
                it.name?.equals(text.toString(), ignoreCase = true) == true
            }
            selectedDamageCauseId = match?.id
        }
        damageCauseInput.setOnItemClickListener { _, _, position, _ ->
            selectedDamageCauseId = filteredDamageCauses.getOrNull(position)?.id
        }

        listOf(damageCategoryInput, lossClassInput).forEach { input ->
            input.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) showKeyboard(view)
            }
            input.setOnClickListener { showKeyboard(it) }
        }

        lossDateInput.setOnClickListener {
            openDatePicker(
                current = lossDate,
                title = getString(R.string.loss_info_loss_date_label)
            ) { selected ->
                lossDate = selected
                setDateInputText(lossDateInput, selected)
            }
        }
        callReceivedInput.setOnClickListener {
            openDatePicker(
                current = callReceived,
                title = getString(R.string.loss_info_call_received_label)
            ) { selected ->
                callReceived = selected
                setDateInputText(callReceivedInput, selected)
            }
        }
        crewDispatchedInput.setOnClickListener {
            openDatePicker(
                current = crewDispatched,
                title = getString(R.string.loss_info_crew_dispatched_label)
            ) { selected ->
                crewDispatched = selected
                setDateInputText(crewDispatchedInput, selected)
            }
        }
        arrivedOnSiteInput.setOnClickListener {
            openDatePicker(
                current = arrivedOnSite,
                title = getString(R.string.loss_info_arrived_on_site_label)
            ) { selected ->
                arrivedOnSite = selected
                setDateInputText(arrivedOnSiteInput, selected)
            }
        }

        saveButton.setOnClickListener { handleSave() }
    }

    private fun openDatePicker(
        current: Date?,
        title: String,
        onSelected: (Date) -> Unit
    ) {
        val selection = current?.time ?: MaterialDatePicker.todayInUtcMilliseconds()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(title)
            .setSelection(selection)
            .build()

        picker.addOnPositiveButtonClickListener { millis ->
            onSelected(materialDateSelectionToDate(millis))
        }
        picker.show(parentFragmentManager, "date_picker_$title")
    }

    private fun showKeyboard(target: View) {
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        target.post {
            target.requestFocus()
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun materialDateSelectionToDate(selection: Long): Date {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = selection
        }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, utc.get(Calendar.YEAR))
            set(Calendar.MONTH, utc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    private fun setDateInputText(input: TextInputEditText, value: Date?) {
        if (value == null) {
            input.setText("")
        } else {
            input.setText(viewModel.formatDate(value))
        }
    }

    private fun handleSave() {
        val damageCategory = damageCategoryInput.text?.toString()?.toIntOrNull()
        val lossClass = lossClassInput.text?.toString()?.toIntOrNull()

        val form = LossInfoFormInput(
            selectedDamageTypeIds = selectedDamageTypeIds.toSet(),
            damageCauseId = selectedDamageCauseId,
            damageCategory = damageCategory,
            lossClass = lossClass,
            lossDate = lossDate,
            callReceived = callReceived,
            crewDispatched = crewDispatched,
            arrivedOnSite = arrivedOnSite
        )
        viewModel.saveLossInfo(form)
    }

    private fun clearDamageCauseIfFilteredOut(removedDamageTypeId: Long) {
        val selected = filteredDamageCauses.firstOrNull { it.id == selectedDamageCauseId }
            ?: allDamageCauses.firstOrNull { it.id == selectedDamageCauseId }
        val selectedTypeId = selected?.propertyDamageType?.id
        if (selectedTypeId == removedDamageTypeId) {
            selectedDamageCauseId = null
            damageCauseInput.setText("", false)
        }
    }

    companion object {
        private const val ARG_PROJECT_ID = "arg_project_id"

        fun newInstance(projectId: Long): LossInfoFragment {
            return LossInfoFragment().apply {
                arguments = Bundle().apply { putLong(ARG_PROJECT_ID, projectId) }
            }
        }
    }
}
