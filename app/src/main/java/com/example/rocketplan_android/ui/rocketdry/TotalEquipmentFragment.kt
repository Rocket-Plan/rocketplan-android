package com.example.rocketplan_android.ui.rocketdry

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TotalEquipmentFragment : Fragment() {

    companion object {
        private const val TAG = "TotalEquipmentFragment"
    }

    private val args: TotalEquipmentFragmentArgs by navArgs()
    private val viewModel: TotalEquipmentViewModel by viewModels {
        TotalEquipmentViewModel.provideFactory(
            requireActivity().application,
            args.projectId
        )
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var locationTotalRecyclerView: RecyclerView
    private lateinit var locationTotalEmptyState: TextView
    private lateinit var roomBreakdownRecyclerView: RecyclerView
    private lateinit var roomBreakdownEmptyState: TextView
    private lateinit var breakdownPerRoomHeader: TextView

    private lateinit var locationTotalAdapter: LocationTotalEquipmentAdapter
    private lateinit var roomBreakdownAdapter: RoomBreakdownAdapter

    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_total_equipment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupToolbar()
        setupRecyclers()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        toolbar = root.findViewById(R.id.toolbar)
        locationTotalRecyclerView = root.findViewById(R.id.locationTotalRecyclerView)
        locationTotalEmptyState = root.findViewById(R.id.locationTotalEmptyState)
        roomBreakdownRecyclerView = root.findViewById(R.id.roomBreakdownRecyclerView)
        roomBreakdownEmptyState = root.findViewById(R.id.roomBreakdownEmptyState)
        breakdownPerRoomHeader = root.findViewById(R.id.breakdownPerRoomHeader)
    }

    private fun setupToolbar() {
        // Title will be set in render() with the project address
    }

    private fun setupRecyclers() {
        // Location Total adapter
        locationTotalAdapter = LocationTotalEquipmentAdapter()
        locationTotalRecyclerView.layoutManager = LinearLayoutManager(context)
        locationTotalRecyclerView.adapter = locationTotalAdapter

        // Room Breakdown adapter
        roomBreakdownAdapter = RoomBreakdownAdapter(
            onIncrease = { item -> viewModel.changeQuantity(item, 1) },
            onDecrease = { item -> viewModel.changeQuantity(item, -1) },
            onStartDateClick = { item -> openDatePicker(item.startDate) { viewModel.updateStartDate(item, it) } },
            onEndDateClick = { item -> openDatePicker(item.endDate ?: item.startDate) { viewModel.updateEndDate(item, it) } },
            onDelete = { item -> confirmDelete(item) },
            dateFormatter = dateFormatter
        )
        roomBreakdownRecyclerView.layoutManager = LinearLayoutManager(context)
        roomBreakdownRecyclerView.adapter = roomBreakdownAdapter
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: TotalEquipmentUiState) {
        when (state) {
            is TotalEquipmentUiState.Loading -> {
                locationTotalEmptyState.isVisible = false
                roomBreakdownEmptyState.isVisible = false
            }

            is TotalEquipmentUiState.Ready -> {
                // Update toolbar with project address
                toolbar.title = state.projectAddress

                // Location Total section
                val hasTypeSummaries = state.equipmentByType.isNotEmpty()
                locationTotalRecyclerView.isVisible = hasTypeSummaries
                locationTotalEmptyState.isVisible = !hasTypeSummaries
                if (hasTypeSummaries) {
                    locationTotalAdapter.submitList(state.equipmentByType)
                }

                // Room Breakdown section
                val hasRoomEquipment = state.roomBreakdowns.isNotEmpty()
                roomBreakdownRecyclerView.isVisible = hasRoomEquipment
                roomBreakdownEmptyState.isVisible = !hasRoomEquipment
                breakdownPerRoomHeader.isVisible = true
                if (hasRoomEquipment) {
                    roomBreakdownAdapter.submitList(state.roomBreakdowns)
                }
            }
        }
    }

    private fun confirmDelete(item: RoomEquipmentItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.equipment_room_delete))
            .setMessage(getString(R.string.equipment_room_delete_confirm, item.typeLabel))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteEquipment(item)
                Toast.makeText(
                    requireContext(),
                    R.string.equipment_room_deleted_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun openDatePicker(initialDate: Date?, onSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance()
        if (initialDate != null) calendar.time = initialDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth, 0, 0, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                onSelected(calendar.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
