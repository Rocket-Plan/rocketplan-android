package com.example.rocketplan_android.ui.rocketdry

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.view.isVisible
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
import com.example.rocketplan_android.data.feature.SerializedEquipmentMode
import com.example.rocketplan_android.ui.common.SinglePhotoCaptureFragment
import com.example.rocketplan_android.ui.projects.addroom.RoomTypePickerMode
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.io.File
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
    private lateinit var equipmentTotalsCard: View
    private lateinit var equipmentUnknownPlaceholder: TextView
    private lateinit var roomCard: View
    private lateinit var exteriorSpaceCard: View
    private lateinit var addExternalLogButton: ImageButton
    private lateinit var externalLogEmptyCard: MaterialCardView
    private lateinit var externalLogSummaryCard: MaterialCardView
    private lateinit var externalLogDateTime: TextView
    private lateinit var externalLogAddButton: ImageButton
    private lateinit var externalLogHumidity: TextView
    private lateinit var externalLogTemperature: TextView
    private lateinit var externalLogPressure: TextView
    private lateinit var externalLogWindSpeed: TextView
    private lateinit var startAtmosphericLogButton: MaterialButton
    private lateinit var locationsRecyclerView: RecyclerView

    private lateinit var locationLevelAdapter: LocationLevelAdapter
    private lateinit var equipmentLevelAdapter: EquipmentLevelAdapter
    private var suppressToggleChanges = false
    private var latestReadyState: RocketDryUiState.Ready? = null

    // No longer needed - using ViewModel-based pending capture pattern

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
        // RP-FR-033: set up the recyclers/adapters BEFORE the first selectTab — selectTab → showTab
        // now renders equipment content, which touches equipmentLevelAdapter. It must exist first.
        setupRecyclerViews()
        // Restore tab from ViewModel (survives navigation) or use initial tab
        val tabToSelect = viewModel.currentTab.value ?: initialTab
        selectTab(tabToSelect)
        setupClickListeners()
        observeViewModel()
        observePhotoResult()
    }

    private fun observePhotoResult() {
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
            ?.observe(viewLifecycleOwner) { photoPath ->
                if (!photoPath.isNullOrBlank()) {
                    Log.d(TAG, "📸 Received photo from camera: $photoPath")
                    val file = File(photoPath)
                    val validPath = if (file.exists()) photoPath else null

                    // Clear the result to avoid re-processing
                    findNavController().currentBackStackEntry?.savedStateHandle
                        ?.remove<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)

                    val pending = viewModel.pendingLogCapture.value
                    if (pending != null) {
                        Log.d(TAG, "📸 Restoring dialog with pending values and photo")
                        viewModel.clearPendingCapture()
                        showAddLogDialogWithPhoto(pending, validPath)
                    } else {
                        Log.w(TAG, "📸 No pending capture found, photo may be lost")
                    }
                }
            }
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
        equipmentTotalsCard = view.findViewById(R.id.equipmentTotalsCard)
        equipmentUnknownPlaceholder = view.findViewById(R.id.equipmentUnknownPlaceholder)
        roomCard = view.findViewById(R.id.roomCard)
        exteriorSpaceCard = view.findViewById(R.id.exteriorSpaceCard)
        addExternalLogButton = view.findViewById(R.id.addExternalLogButton)
        externalLogEmptyCard = view.findViewById(R.id.externalLogEmptyCard)
        externalLogSummaryCard = view.findViewById(R.id.externalLogSummaryCard)
        externalLogDateTime = view.findViewById(R.id.externalLogDateTime)
        externalLogAddButton = view.findViewById(R.id.externalLogAddButton)
        externalLogHumidity = view.findViewById(R.id.externalLogHumidity)
        externalLogTemperature = view.findViewById(R.id.externalLogTemperature)
        externalLogPressure = view.findViewById(R.id.externalLogPressure)
        externalLogWindSpeed = view.findViewById(R.id.externalLogWindSpeed)
        startAtmosphericLogButton = view.findViewById(R.id.startAtmosphericLogButton)
        locationsRecyclerView = view.findViewById(R.id.locationsRecyclerView)
    }

    private fun selectTab(tab: RocketDryTab) {
        suppressToggleChanges = true
        toggleGroup.check(
            when (tab) {
                RocketDryTab.EQUIPMENT -> R.id.equipmentButton
                RocketDryTab.MOISTURE -> R.id.moistureButton
            }
        )
        viewModel.setCurrentTab(tab)
        updateToggleStyles(tab)
        showTab(tab)
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

        // RP-FR-032: the totals button is the single equipment entry point. For serialized-mode
        // companies TotalEquipmentFragment forwards to the company pool (mirrors iOS); the old
        // interim long-press to the pool has been removed.
        equipmentTotalsOpenButton.setOnClickListener {
            Log.d(TAG, "📦 Equipment totals Open tapped - navigating to TotalEquipmentFragment")
            navigateToTotalEquipment()
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
            Log.d(TAG, "➕ Add Exterior Space card tapped - opening room type picker")
            val action = RocketDryFragmentDirections
                .actionRocketDryFragmentToRoomTypePickerFragment(
                    projectId = args.projectId,
                    mode = RoomTypePickerMode.EXTERIOR.name
                )
            findNavController().navigate(action)
        }

        addExternalLogButton.setOnClickListener {
            Log.d(TAG, "➕ Add external atmospheric log tapped (header button)")
            showAddExternalLogDialog()
        }

        externalLogEmptyCard.setOnClickListener {
            Log.d(TAG, "➕ Add external atmospheric log tapped (empty state card)")
            showAddExternalLogDialog()
        }

        startAtmosphericLogButton.setOnClickListener {
            Log.d(TAG, "➕ Add external atmospheric log tapped (start button)")
            showAddExternalLogDialog()
        }

        externalLogSummaryCard.setOnClickListener {
            Log.d(TAG, "📋 External log summary card tapped - navigating to list")
            navigateToExternalLogs()
        }

        externalLogAddButton.setOnClickListener {
            Log.d(TAG, "➕ Add external atmospheric log tapped (card add button)")
            showAddExternalLogDialog()
        }
    }

    private fun onTabSelected(tab: RocketDryTab) {
        viewModel.setCurrentTab(tab)
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
        // RP-FR-033: render mode-appropriate equipment content whenever the Equipment tab becomes
        // active — otherwise switching to it (vs landing on it) leaves the legacy totals card at its
        // XML-default visibility, showing count-based content to a serialized company.
        if (tab == RocketDryTab.EQUIPMENT) {
            renderEquipmentContent(viewModel.equipmentMode.value)
        }
    }

    private fun setupRecyclerViews() {
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
                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            is RocketDryUiState.Ready -> renderState(state)
                            RocketDryUiState.Loading -> showLoadingState()
                        }
                    }
                }
                // RP-FR-033: observe tri-state equipment mode and log equip_ui for diagnostics.
                // Tab is always visible; content swaps based on mode (OFF→legacy, ON→serialized,
                // UNKNOWN→hold placeholder). Mid-session flip is handled reactively.
                launch {
                    viewModel.equipmentMode.collect { mode ->
                        android.util.Log.d(TAG, "equip_ui: equipment mode=$mode")
                        if (viewModel.currentTab.value == RocketDryTab.EQUIPMENT) {
                            renderEquipmentContent(mode)
                        }
                    }
                }
                // RP-FR-033: observe serialized equipment data for ON mode
                launch {
                    viewModel.serializedEquipmentByRoom.collect { levels ->
                        if (viewModel.equipmentMode.value == SerializedEquipmentMode.ON &&
                            viewModel.currentTab.value == RocketDryTab.EQUIPMENT) {
                            equipmentLevelAdapter.submitLevels(levels)
                        }
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        projectAddress.text = getString(R.string.loading_project)
        externalLogEmptyCard.isVisible = false
        externalLogSummaryCard.isVisible = false
        locationLevelAdapter.submitLevels(emptyList())
        equipmentLevelAdapter.submitLevels(emptyList())
        equipmentTotalCount.text = getString(R.string.loading_project)
        equipmentStatusBreakdown.text = ""
        latestReadyState = null
        viewModel.currentTab.value?.let { showTab(it) }
        // RP-FR-033: during loading, show the UNKNOWN placeholder if Equipment tab is active
        if (viewModel.currentTab.value == RocketDryTab.EQUIPMENT) {
            renderEquipmentContent(SerializedEquipmentMode.UNKNOWN)
        }
    }

    private fun renderState(state: RocketDryUiState.Ready) {
        latestReadyState = state
        projectAddress.text = state.projectAddress
        Log.d(TAG, "📍 renderState: locationLevels=${state.locationLevels.size}, equipmentLevels=${state.equipmentLevels.size}, latestExternalLog=${state.latestExternalLog != null}")
        state.locationLevels.forEach { level ->
            Log.d(TAG, "📍 Level '${level.levelName}': ${level.locations.size} rooms")
        }
        renderExternalLogSummary(state.latestExternalLog, state.externalLogCount)
        locationLevelAdapter.submitLevels(state.locationLevels)
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
        viewModel.currentTab.value?.let { showTab(it) }
        // RP-FR-033: render equipment content based on current mode
        if (viewModel.currentTab.value == RocketDryTab.EQUIPMENT) {
            renderEquipmentContent(viewModel.equipmentMode.value)
        }
    }

    /**
     * RP-FR-033: Render the equipment tab content based on tri-state mode.
     * OFF → legacy views; ON → serialized views; UNKNOWN → hold placeholder.
     */
    private fun renderEquipmentContent(mode: SerializedEquipmentMode) {
        when (mode) {
            SerializedEquipmentMode.OFF -> {
                // Legacy mode: show count-based totals and status breakdown
                equipmentTotalsCard.isVisible = true
                equipmentUnknownPlaceholder.isVisible = false
                latestReadyState?.let { state ->
                    equipmentLevelAdapter.submitLevels(state.equipmentLevels)
                }
            }
            SerializedEquipmentMode.ON -> {
                // Serialized mode: hide legacy totals/status, show per-room deployed counts
                equipmentTotalsCard.isVisible = false
                equipmentUnknownPlaceholder.isVisible = false
                equipmentLevelAdapter.submitLevels(viewModel.serializedEquipmentByRoom.value)
            }
            SerializedEquipmentMode.UNKNOWN -> {
                // Unknown mode: show placeholder, hide legacy content
                equipmentTotalsCard.isVisible = false
                equipmentUnknownPlaceholder.isVisible = true
                equipmentLevelAdapter.submitLevels(emptyList())
            }
        }
    }

    private fun renderExternalLogSummary(latestLog: AtmosphericLogItem?, logCount: Int) {
        val hasLogs = latestLog != null
        externalLogEmptyCard.isVisible = !hasLogs
        externalLogSummaryCard.isVisible = hasLogs

        if (latestLog != null) {
            // Format: "#1, Jan 30, 12:44pm" where 1 is the count (showing most recent)
            val headerText = if (logCount > 0) {
                "#$logCount, ${latestLog.dateTime.substringAfter(", ").ifEmpty { latestLog.dateTime }}"
            } else {
                latestLog.dateTime
            }
            externalLogDateTime.text = headerText
            externalLogHumidity.text = latestLog.humidity.toInt().toString()
            externalLogTemperature.text = latestLog.temperature.toInt().toString()
            externalLogPressure.text = latestLog.pressure.toInt().toString()
            externalLogWindSpeed.text = latestLog.windSpeed.toInt().toString()
        }
    }

    private fun showAddExternalLogDialog() {
        val now = Date()
        val title = getString(
            R.string.rocketdry_external_log_title,
            formatLogDate(now)
        )

        var currentHumidity: Double? = null
        var currentTemperature: Double? = null
        var currentPressure: Double? = null
        var currentWindSpeed: Double? = null

        showAtmosphericLogDialogWithValueTracking(
            title = title,
            areaLabel = getString(R.string.rocketdry_atmos_room_external),
            photoCallback = object : AtmosphericLogPhotoCallback {
                override fun onTakePhotoRequested(callback: (Uri?) -> Unit) {
                    launchCamera(currentHumidity, currentTemperature, currentPressure, currentWindSpeed)
                }
            },
            onValuesChanged = { h, t, p, w ->
                currentHumidity = h
                currentTemperature = t
                currentPressure = p
                currentWindSpeed = w
            }
        ) { humidity, temperature, pressure, windSpeed, photoLocalPath ->
            Log.d(TAG, "📩 External atmospheric log submitted: rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed photo=$photoLocalPath")
            viewModel.addExternalAtmosphericLog(
                humidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                photoLocalPath = photoLocalPath
            )
        }
    }

    private fun formatLogDate(date: Date): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
        return formatted
            .replace("AM", "am")
            .replace("PM", "pm")
    }

    private fun showAddLogDialogWithPhoto(pending: PendingLogCapture, photoPath: String?) {
        val now = Date()
        val title = getString(
            R.string.rocketdry_external_log_title,
            formatLogDate(now)
        )

        showAtmosphericLogDialogWithValues(
            title = title,
            areaLabel = getString(R.string.rocketdry_atmos_room_external),
            initialHumidity = pending.humidity,
            initialTemperature = pending.temperature,
            initialPressure = pending.pressure,
            initialWindSpeed = pending.windSpeed,
            initialPhotoPath = photoPath,
            photoCallback = object : AtmosphericLogPhotoCallback {
                override fun onTakePhotoRequested(callback: (Uri?) -> Unit) {
                    launchCamera(pending.humidity, pending.temperature, pending.pressure, pending.windSpeed)
                }
            }
        ) { humidity, temperature, pressure, windSpeed, finalPhotoPath ->
            Log.d(TAG, "📩 External atmospheric log submitted: rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed photo=$finalPhotoPath")
            viewModel.addExternalAtmosphericLog(
                humidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                photoLocalPath = finalPhotoPath
            )
        }
    }

    private fun launchCamera(humidity: Double?, temperature: Double?, pressure: Double?, windSpeed: Double?) {
        // Prevent double navigation if already navigating to camera
        val currentDestId = findNavController().currentDestination?.id
        if (currentDestId != R.id.rocketDryFragment) {
            Log.d(TAG, "📷 Already navigated away, skipping camera launch")
            return
        }
        // Clear any old photo result before navigating to prevent stale data triggering callback
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.remove<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
        viewModel.savePendingCapture(humidity, temperature, pressure, windSpeed)
        Log.d(TAG, "📷 Navigating to camera screen")
        findNavController().navigate(
            RocketDryFragmentDirections
                .actionRocketDryFragmentToSinglePhotoCaptureFragment()
        )
    }

    private fun navigateToTotalEquipment() {
        val action = RocketDryFragmentDirections
            .actionRocketDryFragmentToTotalEquipmentFragment(
                projectId = args.projectId
            )
        findNavController().navigate(action)
    }

    private fun navigateToExternalLogs() {
        Log.d(TAG, "➡️ Navigating to external atmospheric logs")
        val action = RocketDryFragmentDirections
            .actionRocketDryFragmentToExternalAtmosphericLogsFragment(
                projectId = args.projectId
            )
        findNavController().navigate(action)
    }

}
