package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RocketDryFragment : Fragment() {

    companion object {
        private const val TAG = "RocketDryFragment"
    }

    private val args: RocketDryFragmentArgs by navArgs()
    private val viewModel: RocketDryViewModel by viewModels {
        RocketDryViewModel.provideFactory(requireActivity().application, args.projectId)
    }
    private val initialTab: RocketDryTab by lazy {
        if (args.startTab.equals("moisture", ignoreCase = true)) RocketDryTab.MOISTURE else RocketDryTab.EQUIPMENT
    }

    private lateinit var projectAddress: TextView
    private lateinit var editAddressButton: ImageButton
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var equipmentButton: MaterialButton
    private lateinit var moistureButton: MaterialButton
    private lateinit var equipmentContentGroup: View
    private lateinit var moistureContentGroup: View
    private lateinit var equipmentTotalCount: TextView
    private lateinit var equipmentStatusBreakdown: TextView
    private lateinit var equipmentSummaryRecyclerView: RecyclerView
    private lateinit var equipmentLocationsRecyclerView: RecyclerView
    private lateinit var roomCard: View
    private lateinit var exteriorSpaceCard: View
    private lateinit var addExternalLogButton: ImageButton
    private lateinit var atmosphericEmptyStateCard: View
    private lateinit var startAtmosphericLogButton: MaterialButton
    private lateinit var atmosphericLogsRecyclerView: RecyclerView
    private lateinit var locationsRecyclerView: RecyclerView

    private lateinit var atmosphericLogAdapter: AtmosphericLogAdapter
    private lateinit var locationLevelAdapter: LocationLevelAdapter
    private lateinit var equipmentSummaryAdapter: EquipmentSummaryAdapter
    private lateinit var equipmentLevelAdapter: EquipmentLevelAdapter
    private var suppressToggleChanges = false
    private lateinit var currentTab: RocketDryTab

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_rocket_dry, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        selectInitialTab()
        setupClickListeners()
        setupRecyclerViews()
        observeViewModel()
    }

    private fun initializeViews(view: View) {
        projectAddress = view.findViewById(R.id.projectAddress)
        editAddressButton = view.findViewById(R.id.editAddressButton)
        toggleGroup = view.findViewById(R.id.toggleGroup)
        equipmentButton = view.findViewById(R.id.equipmentButton)
        moistureButton = view.findViewById(R.id.moistureButton)
        equipmentContentGroup = view.findViewById(R.id.equipmentContentGroup)
        moistureContentGroup = view.findViewById(R.id.moistureContentGroup)
        equipmentTotalCount = view.findViewById(R.id.equipmentTotalCount)
        equipmentStatusBreakdown = view.findViewById(R.id.equipmentStatusBreakdown)
        equipmentSummaryRecyclerView = view.findViewById(R.id.equipmentSummaryRecyclerView)
        equipmentLocationsRecyclerView = view.findViewById(R.id.equipmentLocationsRecyclerView)
        roomCard = view.findViewById(R.id.roomCard)
        exteriorSpaceCard = view.findViewById(R.id.exteriorSpaceCard)
        addExternalLogButton = view.findViewById(R.id.addExternalLogButton)
        atmosphericEmptyStateCard = view.findViewById(R.id.atmosphericEmptyStateCard)
        startAtmosphericLogButton = view.findViewById(R.id.startAtmosphericLogButton)
        atmosphericLogsRecyclerView = view.findViewById(R.id.atmosphericLogsRecyclerView)
        locationsRecyclerView = view.findViewById(R.id.locationsRecyclerView)
    }

    private fun selectInitialTab() {
        suppressToggleChanges = true
        toggleGroup.check(
            when (initialTab) {
                RocketDryTab.EQUIPMENT -> R.id.equipmentButton
                RocketDryTab.MOISTURE -> R.id.moistureButton
            }
        )
        currentTab = initialTab
        updateToggleStyles(initialTab)
        showTab(initialTab)
        toggleGroup.post { suppressToggleChanges = false }
    }

    private fun setupClickListeners() {
        editAddressButton.setOnClickListener {
            Toast.makeText(context, "Edit Address", Toast.LENGTH_SHORT).show()
        }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressToggleChanges) return@addOnButtonCheckedListener
            val selectedTab = when (checkedId) {
                R.id.equipmentButton -> RocketDryTab.EQUIPMENT
                else -> RocketDryTab.MOISTURE
            }
            onTabSelected(selectedTab)
        }

        roomCard.setOnClickListener {
            Toast.makeText(context, "Add Room", Toast.LENGTH_SHORT).show()
        }

        exteriorSpaceCard.setOnClickListener {
            Toast.makeText(context, "Add Exterior Space", Toast.LENGTH_SHORT).show()
        }

        addExternalLogButton.setOnClickListener {
            showAddExternalLogDialog()
        }

        atmosphericEmptyStateCard.setOnClickListener {
            showAddExternalLogDialog()
        }

        startAtmosphericLogButton.setOnClickListener {
            showAddExternalLogDialog()
        }
    }

    private fun onTabSelected(tab: RocketDryTab) {
        currentTab = tab
        updateToggleStyles(tab)
        showTab(tab)
    }

    private fun updateToggleStyles(active: RocketDryTab) {
        listOf(
            equipmentButton to RocketDryTab.EQUIPMENT,
            moistureButton to RocketDryTab.MOISTURE
        ).forEach { (button, tab) ->
            val isSelected = tab == active
            button.isChecked = isSelected
        }
    }

    private fun showTab(tab: RocketDryTab) {
        equipmentContentGroup.isVisible = tab == RocketDryTab.EQUIPMENT
        moistureContentGroup.isVisible = tab == RocketDryTab.MOISTURE
    }

    private fun setupRecyclerViews() {
        // Atmospheric Logs RecyclerView
        atmosphericLogAdapter = AtmosphericLogAdapter { showAddExternalLogDialog() }
        atmosphericLogsRecyclerView.layoutManager = LinearLayoutManager(context)
        atmosphericLogsRecyclerView.adapter = atmosphericLogAdapter

        // Equipment summary RecyclerView
        equipmentSummaryAdapter = EquipmentSummaryAdapter()
        equipmentSummaryRecyclerView.layoutManager = GridLayoutManager(context, 2)
        equipmentSummaryRecyclerView.adapter = equipmentSummaryAdapter

        // Equipment by level RecyclerView
        equipmentLevelAdapter = EquipmentLevelAdapter()
        equipmentLocationsRecyclerView.layoutManager = LinearLayoutManager(context)
        equipmentLocationsRecyclerView.adapter = equipmentLevelAdapter

        // Locations RecyclerView
        locationLevelAdapter = LocationLevelAdapter()
        locationsRecyclerView.layoutManager = LinearLayoutManager(context)
        locationsRecyclerView.adapter = locationLevelAdapter
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is RocketDryUiState.Ready -> renderState(state)
                        RocketDryUiState.Loading -> showLoadingState()
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        projectAddress.text = getString(R.string.loading_project)
        updateAtmosphericLogs(emptyList())
        locationLevelAdapter.submitLevels(emptyList())
        equipmentSummaryAdapter.submitItems(emptyList())
        equipmentLevelAdapter.submitLevels(emptyList())
        equipmentTotalCount.text = getString(R.string.loading_project)
        equipmentStatusBreakdown.text = ""
        showTab(currentTab)
    }

    private fun renderState(state: RocketDryUiState.Ready) {
        projectAddress.text = state.projectAddress
        updateAtmosphericLogs(state.atmosphericLogs)
        locationLevelAdapter.submitLevels(state.locationLevels)
        equipmentSummaryAdapter.submitItems(state.equipmentByType)
        equipmentLevelAdapter.submitLevels(state.equipmentLevels)
        equipmentTotalCount.text = resources.getQuantityString(
            R.plurals.rocketdry_equipment_units,
            state.equipmentTotals.total,
            state.equipmentTotals.total
        )
        equipmentStatusBreakdown.text = getString(
            R.string.rocketdry_equipment_status_breakdown,
            state.equipmentTotals.active,
            state.equipmentTotals.removed,
            state.equipmentTotals.damaged
        )
        showTab(currentTab)
    }

    private fun updateAtmosphericLogs(logs: List<AtmosphericLogItem>) {
        atmosphericLogAdapter.submitLogs(logs)
        val hasLogs = logs.isNotEmpty()
        atmosphericLogsRecyclerView.isVisible = hasLogs
        atmosphericEmptyStateCard.isVisible = !hasLogs
    }

    private fun showAddExternalLogDialog() {
        data class InputStep(
            val labelRes: Int,
            val unit: String,
            val getter: () -> Double?,
            val setter: (Double) -> Unit
        )

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_external_log, null)
        val wizardTitle = dialogView.findViewById<TextView>(R.id.wizardTitle)
        val stepLabel = dialogView.findViewById<TextView>(R.id.stepLabel)
        val stepPreview = dialogView.findViewById<TextView>(R.id.stepPreview)
        val stepPosition = dialogView.findViewById<TextView>(R.id.stepPosition)
        val stepInputLayout = dialogView.findViewById<TextInputLayout>(R.id.stepInputLayout)
        val stepInput = dialogView.findViewById<TextInputEditText>(R.id.stepInput)
        val previousStepButton = dialogView.findViewById<MaterialButton>(R.id.previousStepButton)
        val cancelWizardButton = dialogView.findViewById<MaterialButton>(R.id.cancelWizardButton)
        val nextStepButton = dialogView.findViewById<MaterialButton>(R.id.nextStepButton)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val now = Date()
        wizardTitle.text = getString(
            R.string.rocketdry_external_log_title,
            formatLogDate(now)
        )

        var humidity: Double? = null
        var temperature: Double? = null
        var pressure: Double? = null
        var windSpeed: Double? = null

        val numberFormatter = DecimalFormat("#.##")
        val steps = listOf(
            InputStep(
                labelRes = R.string.rocketdry_relative_humidity_label,
                unit = getString(R.string.percent),
                getter = { humidity },
                setter = { humidity = it }
            ),
            InputStep(
                labelRes = R.string.rocketdry_temperature_label,
                unit = getString(R.string.fahrenheit),
                getter = { temperature },
                setter = { temperature = it }
            ),
            InputStep(
                labelRes = R.string.rocketdry_pressure_label,
                unit = getString(R.string.kpa),
                getter = { pressure },
                setter = { pressure = it }
            ),
            InputStep(
                labelRes = R.string.rocketdry_wind_speed_label,
                unit = getString(R.string.mph),
                getter = { windSpeed },
                setter = { windSpeed = it }
            )
        )

        var currentStep = 0

        fun formatPreview(value: Double?, unit: String): String {
            val formattedValue = value?.let { numberFormatter.format(it) } ?: "-"
            return if (unit.isBlank()) formattedValue else "$formattedValue $unit"
        }

        fun bindStep() {
            val step = steps[currentStep]
            val currentValue = step.getter()
            stepLabel.text = getString(step.labelRes)
            stepInputLayout.hint = getString(step.labelRes)
            stepPreview.text = formatPreview(currentValue, step.unit)
            stepPosition.text = getString(
                R.string.rocketdry_step_indicator,
                currentStep + 1,
                steps.size
            )
            stepInputLayout.error = null
            val textValue = currentValue?.let { numberFormatter.format(it) } ?: ""
            stepInput.setText(textValue)
            stepInput.setSelection(stepInput.text?.length ?: 0)
            stepInput.imeOptions = if (currentStep == steps.lastIndex) {
                EditorInfo.IME_ACTION_DONE
            } else {
                EditorInfo.IME_ACTION_NEXT
            }
            previousStepButton.isEnabled = currentStep > 0
            nextStepButton.text = if (currentStep == steps.lastIndex) {
                getString(R.string.save)
            } else {
                getString(R.string.rocketdry_next)
            }
        }

        fun persistCurrentValue(): Boolean {
            val value = stepInput.text?.toString()?.toDoubleOrNull()
            return if (value == null) {
                stepInputLayout.error = getString(R.string.rocketdry_add_external_log_error)
                false
            } else {
                stepInputLayout.error = null
                steps[currentStep].setter(value)
                true
            }
        }

        stepInput.doAfterTextChanged {
            stepInputLayout.error = null
            steps.getOrNull(currentStep)?.let { step ->
                stepPreview.text = formatPreview(it?.toString()?.toDoubleOrNull(), step.unit)
            }
        }

        stepInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                nextStepButton.performClick()
                true
            } else {
                false
            }
        }

        previousStepButton.setOnClickListener {
            if (currentStep == 0) return@setOnClickListener
            stepInputLayout.error = null
            stepInput.text?.toString()?.toDoubleOrNull()?.let { value ->
                steps[currentStep].setter(value)
            }
            currentStep -= 1
            bindStep()
        }

        cancelWizardButton.setOnClickListener {
            dialog.dismiss()
        }

        nextStepButton.setOnClickListener {
            val isValid = persistCurrentValue()
            if (!isValid) return@setOnClickListener

            if (currentStep == steps.lastIndex) {
                viewModel.addExternalAtmosphericLog(
                    humidity = humidity ?: return@setOnClickListener,
                    temperature = temperature ?: return@setOnClickListener,
                    pressure = pressure ?: return@setOnClickListener,
                    windSpeed = windSpeed ?: return@setOnClickListener
                )
                dialog.dismiss()
            } else {
                currentStep += 1
                bindStep()
            }
        }

        bindStep()
        dialog.show()
        stepInput.requestFocus()
    }

    private fun formatLogDate(date: Date): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
        return formatted
            .replace("AM", "am")
            .replace("PM", "pm")
    }
}

private enum class RocketDryTab {
    EQUIPMENT,
    MOISTURE
}
