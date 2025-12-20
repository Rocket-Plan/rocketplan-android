package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.ui.projects.addroom.RoomTypePickerMode
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
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
    private lateinit var atmosphericSectionTitle: TextView
    private lateinit var equipmentTotalCount: TextView
    private lateinit var equipmentStatusBreakdown: TextView
    private lateinit var equipmentLocationsRecyclerView: RecyclerView
    private lateinit var equipmentTotalsOpenButton: MaterialButton
    private lateinit var roomCard: View
    private lateinit var exteriorSpaceCard: View
    private lateinit var addExternalLogButton: ImageButton
    private lateinit var atmosphericRoomFilterContainer: View
    private lateinit var atmosphericRoomFilterGroup: ChipGroup
    private lateinit var atmosphericEmptyStateCard: View
    private lateinit var startAtmosphericLogButton: MaterialButton
    private lateinit var atmosphericLogsRecyclerView: RecyclerView
    private lateinit var locationsRecyclerView: RecyclerView

    private lateinit var atmosphericLogAdapter: AtmosphericLogAdapter
    private lateinit var locationLevelAdapter: LocationLevelAdapter
    private lateinit var equipmentLevelAdapter: EquipmentLevelAdapter
    private var suppressToggleChanges = false
    private lateinit var currentTab: RocketDryTab
    private var lastRenderedAtmosphericAreas: List<AtmosphericLogArea> = emptyList()
    private var latestAtmosphericSelection: Long? = null
    private var latestReadyState: RocketDryUiState.Ready? = null

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
        atmosphericSectionTitle = view.findViewById(R.id.atmosphericSectionTitle)
        equipmentTotalCount = view.findViewById(R.id.equipmentTotalCount)
        equipmentStatusBreakdown = view.findViewById(R.id.equipmentStatusBreakdown)
        equipmentLocationsRecyclerView = view.findViewById(R.id.equipmentLocationsRecyclerView)
        equipmentTotalsOpenButton = view.findViewById(R.id.equipmentTotalsOpenButton)
        roomCard = view.findViewById(R.id.roomCard)
        exteriorSpaceCard = view.findViewById(R.id.exteriorSpaceCard)
        addExternalLogButton = view.findViewById(R.id.addExternalLogButton)
        atmosphericRoomFilterContainer = view.findViewById(R.id.atmosphericRoomFilterContainer)
        atmosphericRoomFilterGroup = view.findViewById(R.id.atmosphericRoomFilterGroup)
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
            Log.d(TAG, "✏️ Edit address tapped (not implemented)")
            Toast.makeText(context, "Edit Address", Toast.LENGTH_SHORT).show()
        }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressToggleChanges) return@addOnButtonCheckedListener
            val selectedTab = when (checkedId) {
                R.id.equipmentButton -> RocketDryTab.EQUIPMENT
                else -> RocketDryTab.MOISTURE
            }
            Log.d(TAG, "🔀 Tab selected: $selectedTab")
            onTabSelected(selectedTab)
        }

        equipmentTotalsOpenButton.setOnClickListener {
            Log.d(TAG, "📦 Equipment totals Open tapped")
            showEquipmentTotalsDialog()
        }

        roomCard.setOnClickListener {
            Log.d(TAG, "➕ Add Room card tapped - opening room type picker")
            val action = RocketDryFragmentDirections
                .actionRocketDryFragmentToRoomTypePickerFragment(
                    projectId = args.projectId,
                    mode = RoomTypePickerMode.ROOM.name
                )
            findNavController().navigate(action)
        }

        exteriorSpaceCard.setOnClickListener {
            Log.d(TAG, "➕ Add Exterior Space card tapped")
            Toast.makeText(context, "Add Exterior Space", Toast.LENGTH_SHORT).show()
        }

        addExternalLogButton.setOnClickListener {
            Log.d(TAG, "➕ Add external atmospheric log tapped (button)")
            showAddExternalLogDialog()
        }

        atmosphericEmptyStateCard.setOnClickListener {
            Log.d(TAG, "➕ Add external atmospheric log tapped (empty state card)")
            showAddExternalLogDialog()
        }

        startAtmosphericLogButton.setOnClickListener {
            Log.d(TAG, "➕ Add external atmospheric log tapped (start button)")
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

        // Equipment by level RecyclerView
        equipmentLevelAdapter = EquipmentLevelAdapter { room ->
            onEquipmentRoomSelected(room)
        }
        equipmentLocationsRecyclerView.layoutManager = LinearLayoutManager(context)
        equipmentLocationsRecyclerView.adapter = equipmentLevelAdapter

        // Locations RecyclerView
        locationLevelAdapter = LocationLevelAdapter { location ->
            Log.d(TAG, "➡️ Location card tapped: roomId=${location.roomId}, name='${location.name}'")
            openRoomDry(location.roomId)
        }
        locationsRecyclerView.layoutManager = LinearLayoutManager(context)
        locationsRecyclerView.adapter = locationLevelAdapter
    }

    private fun onEquipmentRoomSelected(room: EquipmentRoomSummary) {
        if (room.roomId == null) {
            Toast.makeText(requireContext(), R.string.rocketdry_no_equipment, Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "➡️ Navigating to room equipment for roomId=${room.roomId}, name='${room.roomName}'")
        val action = RocketDryFragmentDirections
            .actionRocketDryFragmentToEquipmentRoomFragment(
                projectId = args.projectId,
                roomId = room.roomId
            )
        findNavController().navigate(action)
    }

    private fun openRoomDry(roomId: Long) {
        Log.d(TAG, "➡️ Navigating to room dry details for roomId=$roomId")
        val action = RocketDryFragmentDirections
            .actionRocketDryFragmentToRocketDryRoomFragment(
                projectId = args.projectId,
                roomId = roomId
            )
        findNavController().navigate(action)
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
        renderAtmosphericAreaFilters(emptyList(), null)
        updateAtmosphericLogs(emptyList())
        locationLevelAdapter.submitLevels(emptyList())
        equipmentLevelAdapter.submitLevels(emptyList())
        equipmentTotalCount.text = getString(R.string.loading_project)
        equipmentStatusBreakdown.text = ""
        latestReadyState = null
        showTab(currentTab)
    }

    private fun renderState(state: RocketDryUiState.Ready) {
        latestReadyState = state
        projectAddress.text = state.projectAddress
        renderAtmosphericAreaFilters(state.atmosphericAreas, state.selectedAtmosphericRoomId)
        updateAtmosphericLogs(state.atmosphericLogs)
        locationLevelAdapter.submitLevels(state.locationLevels)
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

    private fun renderAtmosphericAreaFilters(
        areas: List<AtmosphericLogArea>,
        selectedRoomId: Long?
    ) {
        latestAtmosphericSelection = selectedRoomId
        val selectedArea = areas.firstOrNull { it.roomId == selectedRoomId }
        val headerText = when {
            selectedArea?.roomId != null ->
                getString(R.string.rocketdry_atmospheric_log_for_area, selectedArea.label)
            else -> getString(R.string.external_atmospheric_log)
        }
        atmosphericSectionTitle.text = headerText
        atmosphericRoomFilterContainer.isVisible = areas.isNotEmpty()
        if (areas.isEmpty()) {
            atmosphericRoomFilterGroup.setOnCheckedStateChangeListener(null)
            atmosphericRoomFilterGroup.removeAllViews()
            lastRenderedAtmosphericAreas = emptyList()
            return
        }

        val hasChanged = areas != lastRenderedAtmosphericAreas
        if (hasChanged) {
            atmosphericRoomFilterGroup.setOnCheckedStateChangeListener(null)
            atmosphericRoomFilterGroup.removeAllViews()

            val context = atmosphericRoomFilterGroup.context
            areas.forEach { area ->
                val chip = Chip(context).apply {
                    id = View.generateViewId()
                    tag = area.roomId
                    text = getString(R.string.rocketdry_atmospheric_area_chip, area.label, area.logCount)
                    isCheckable = true
                    isChecked = area.roomId == selectedRoomId
                    isClickable = true
                    isFocusable = true
                    chipBackgroundColor = ContextCompat.getColorStateList(context, R.color.chip_background_selector)
                    setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text_selector))
                    isCheckedIconVisible = false
                    rippleColor = ContextCompat.getColorStateList(context, android.R.color.transparent)
                }
                atmosphericRoomFilterGroup.addView(chip)
            }

            atmosphericRoomFilterGroup.setOnCheckedStateChangeListener { group, checkedIds ->
                val checkedChipId = checkedIds.firstOrNull()
                val chip = checkedChipId?.let { id -> group.findViewById<Chip>(id) }
                val roomId = chip?.tag as? Long
                Log.d(
                    TAG,
                    "✅ Atmospheric area selected roomId=$roomId label='${chip?.text}'"
                )
                viewModel.selectAtmosphericRoom(roomId)
            }
            lastRenderedAtmosphericAreas = areas
        } else {
            for (index in 0 until atmosphericRoomFilterGroup.childCount) {
                val chip = atmosphericRoomFilterGroup.getChildAt(index) as? Chip ?: continue
                chip.isChecked = chip.tag as? Long == selectedRoomId
            }
        }
    }

    private fun showEquipmentTotalsDialog() {
        val state = latestReadyState
        if (state == null) {
            Toast.makeText(requireContext(), R.string.loading_project, Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_equipment_totals, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.equipmentTotalsRecyclerView)
        val emptyState = dialogView.findViewById<TextView>(R.id.equipmentTotalsEmptyState)
        recycler.layoutManager = LinearLayoutManager(context)
        val adapter = EquipmentSummaryAdapter()
        recycler.adapter = adapter

        val items = state.equipmentByType
        val hasItems = items.isNotEmpty()
        recycler.isVisible = hasItems
        emptyState.isVisible = !hasItems
        if (hasItems) {
            adapter.submitItems(items)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rocketdry_equipment_overview)
            .setView(dialogView)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showAddExternalLogDialog() {
        val now = Date()
        val title = getString(
            R.string.rocketdry_external_log_title,
            formatLogDate(now)
        )
        val currentAreaLabel = lastRenderedAtmosphericAreas
            .firstOrNull { it.roomId == latestAtmosphericSelection }
            ?.label
            ?: getString(R.string.rocketdry_atmos_room_external)
        Log.d(
            TAG,
            "🧪 Launching external atmospheric log dialog at '$title' (selectedRoomId=$latestAtmosphericSelection)"
        )
        showAtmosphericLogDialog(
            title = title,
            areaLabel = currentAreaLabel,
            onAreaClicked = { updateLabel ->
                showAtmosphericAreaPicker { area ->
                    updateLabel(area.label)
                }
            },
            onRenameAreaClicked = onRename@{ updateLabel ->
                val selectedRoomId = latestAtmosphericSelection
                if (selectedRoomId == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.rocketdry_select_area_first,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@onRename
                }
                showRenameAreaDialog(selectedRoomId) { updatedLabel ->
                    Log.d(TAG, "✏️ Atmospheric area renamed to '$updatedLabel'")
                    updateLabel(updatedLabel)
                }
            }
        ) { humidity, temperature, pressure, windSpeed ->
            Log.d(
                TAG,
                "📩 External atmospheric log submitted: rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed roomId=$latestAtmosphericSelection"
            )
            viewModel.addExternalAtmosphericLog(
                humidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                roomId = latestAtmosphericSelection
            )
        }
    }

    private fun showAtmosphericAreaPicker(onSelected: (AtmosphericLogArea) -> Unit) {
        val areas = lastRenderedAtmosphericAreas.takeIf { it.isNotEmpty() } ?: listOf(
            AtmosphericLogArea(
                roomId = null,
                label = getString(R.string.rocketdry_atmos_room_external),
                logCount = 0
            )
        )
        val labels = areas.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rocketdry_select_area_title)
            .setItems(labels) { dialog, index ->
                val selected = areas.getOrNull(index) ?: return@setItems
                latestAtmosphericSelection = selected.roomId
                viewModel.selectAtmosphericRoom(selected.roomId)
                onSelected(selected)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameAreaDialog(
        roomId: Long,
        onRenamed: (String) -> Unit
    ) {
        val currentArea = lastRenderedAtmosphericAreas.firstOrNull { it.roomId == roomId }
            ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_rename_area, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.areaNameInputLayout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.areaNameInput)
        input.setText(currentArea.label)
        input.setSelection(input.text?.length ?: 0)
        input.doAfterTextChanged {
            inputLayout.error = null
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rocketdry_rename_area_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    inputLayout.error = getString(R.string.rocketdry_rename_area_error)
                    return@setOnClickListener
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val success = viewModel.renameAtmosphericArea(roomId, newName)
                    if (success) {
                        onRenamed(newName)
                        Toast.makeText(
                            requireContext(),
                            R.string.rocketdry_area_renamed,
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    } else {
                        inputLayout.error = getString(R.string.rocketdry_rename_area_error)
                    }
                }
            }
        }
        dialog.show()
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
