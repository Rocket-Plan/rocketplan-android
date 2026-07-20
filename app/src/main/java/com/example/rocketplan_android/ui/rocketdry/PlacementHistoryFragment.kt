package com.example.rocketplan_android.ui.rocketdry

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentSerializedPlacementHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * RP-FR-028 — read-only placement-history screen for a serialized unit. Reached from the asset
 * detail screen's "View history" action. Mirrors [SerializedAssetDetailFragment] (ViewBinding
 * idiom, repeatOnLifecycle STARTED, sealed UiState render).
 */
class PlacementHistoryFragment : Fragment() {

    private val args: PlacementHistoryFragmentArgs by navArgs()
    private val viewModel: PlacementHistoryViewModel by viewModels {
        PlacementHistoryViewModel.provideFactory(requireActivity().application, args.assetLocalId)
    }

    private var _binding: FragmentSerializedPlacementHistoryBinding? = null
    private val binding get() = _binding!!

    private val adapter = PlacementHistoryAdapter(
        onEditDates = { showEditDatesDialog(it) },
        onDelete = { showDeleteConfirm(it) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSerializedPlacementHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * RP-FR-030 — correct a closed placement's dates. Dates are captured as UTC calendar dates
     * (a UTC [Calendar] built from the picked y/m/d) so a correction never shifts a day across
     * timezones (mirrors iOS RP-BUG-345). Pick date_in, then date_out.
     */
    private fun showEditDatesDialog(row: PlacementRowUi) {
        pickUtcDate(row.dateInUtcMillis) { dateInMillis ->
            pickUtcDate(row.dateOutUtcMillis ?: dateInMillis) { dateOutMillis ->
                viewModel.correctPlacement(row.placementId, dateInMillis, dateOutMillis)
            }
        }
    }

    private fun pickUtcDate(initialUtcMillis: Long?, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        if (initialUtcMillis != null) cal.timeInMillis = initialUtcMillis
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(year, month, day)
                }
                onPicked(picked.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showDeleteConfirm(row: PlacementRowUi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.serialized_history_delete_confirm_title)
            .setMessage(R.string.serialized_history_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.serialized_history_delete) { _, _ ->
                viewModel.deletePlacement(row.placementId)
            }
            .show()
    }

    private fun render(state: PlacementHistoryUiState) {
        binding.historyLoading.isVisible = state is PlacementHistoryUiState.Loading
        binding.historyEmpty.isVisible = state is PlacementHistoryUiState.Empty
        binding.historyList.isVisible = state is PlacementHistoryUiState.Ready
        if (state is PlacementHistoryUiState.Ready) adapter.submitList(state.rows)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.historyList.adapter = null
        _binding = null
    }
}
