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
        private const val KEY_CURRENT_TAB = "current_tab"
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
    private lateinit var currentTab: RocketDryTab
    private var latestReadyState: RocketDryUiState.Ready? = null

    // Photo capture callback - stored while navigating to camera
    private var pendingPhotoCallback: ((Uri?) -> Unit)? = null

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
        // Restore tab from savedInstanceState if available, otherwise use initial
        val savedTab = savedInstanceState?.getString(KEY_CURRENT_TAB)
        val tabToSelect = when {
            savedTab != null -> if (savedTab == "MOISTURE") RocketDryTab.MOISTURE else RocketDryTab.EQUIPMENT
            else -> initialTab
        }
        selectTab(tabToSelect)
        setupClickListeners()
        setupRecyclerViews()
        observeViewModel()
        observePhotoResult()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::currentTab.isInitialized) {
            outState.putString(KEY_CURRENT_TAB, currentTab.name)
        }
    }

    private fun observePhotoResult() {
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
            ?.observe(viewLifecycleOwner) { photoPath ->
                if (!photoPath.isNullOrBlank()) {
                    Log.d(TAG, "📸 Received photo from camera: $photoPath")
                    val file = File(photoPath)
                    if (file.exists()) {
                        pendingPhotoCallback?.invoke(Uri.fromFile(file))
                    } else {
                        pendingPhotoCallback?.invoke(null)
                    }
                    pendingPhotoCallback = null
                    // Clear the result to avoid re-processing
                    findNavController().currentBackStackEntry?.savedStateHandle
                        ?.remove<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
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
        currentTab = tab
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
        externalLogEmptyCard.isVisible = false
        externalLogSummaryCard.isVisible = false
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
        Log.d(TAG, "📍 renderState: locationLevels=${state.locationLevels.size}, equipmentLevels=${state.equipmentLevels.size}, latestExternalLog=${state.latestExternalLog != null}")
        state.locationLevels.forEach { level ->
            Log.d(TAG, "📍 Level '${level.levelName}': ${level.locations.size} rooms")
        }
        renderExternalLogSummary(state.latestExternalLog, state.externalLogCount)
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

        val photoCallback = object : AtmosphericLogPhotoCallback {
            override fun onTakePhotoRequested(callback: (Uri?) -> Unit) {
                launchCamera(callback)
            }
        }

        showAtmosphericLogDialog(
            title = title,
            areaLabel = getString(R.string.rocketdry_atmos_room_external),
            photoCallback = photoCallback
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

    private fun launchCamera(callback: (Uri?) -> Unit) {
        // Prevent double navigation if already navigating to camera
        val currentDestId = findNavController().currentDestination?.id
        if (currentDestId != R.id.rocketDryFragment) {
            Log.d(TAG, "📷 Already navigated away, skipping camera launch")
            return
        }
        // Clear any old photo result before navigating to prevent stale data triggering callback
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.remove<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
        pendingPhotoCallback = callback
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

private enum class RocketDryTab {
    EQUIPMENT,
    MOISTURE
}
