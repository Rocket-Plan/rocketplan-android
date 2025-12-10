package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch

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

    private lateinit var backButton: ImageButton
    private lateinit var menuButton: ImageButton
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
        backButton = view.findViewById(R.id.backButton)
        menuButton = view.findViewById(R.id.menuButton)
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
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        menuButton.setOnClickListener {
            Toast.makeText(context, "Menu", Toast.LENGTH_SHORT).show()
        }

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
        atmosphericLogAdapter = AtmosphericLogAdapter()
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
        atmosphericLogAdapter.submitLogs(emptyList())
        locationLevelAdapter.submitLevels(emptyList())
        equipmentSummaryAdapter.submitItems(emptyList())
        equipmentLevelAdapter.submitLevels(emptyList())
        equipmentTotalCount.text = getString(R.string.loading_project)
        equipmentStatusBreakdown.text = ""
        showTab(currentTab)
    }

    private fun renderState(state: RocketDryUiState.Ready) {
        projectAddress.text = state.projectAddress
        atmosphericLogAdapter.submitLogs(state.atmosphericLogs)
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
}

private enum class RocketDryTab {
    EQUIPMENT,
    MOISTURE
}
