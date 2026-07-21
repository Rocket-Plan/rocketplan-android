package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentSerializedAssetDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * RP-FR-027 — per-unit serialized-asset detail screen. Mirrors [SerializedAssetEditFragment]
 * (ViewBinding idiom, repeatOnLifecycle STARTED, events Toast). Reads offline-first from Room via
 * [SerializedAssetDetailViewModel]; hosts the lifecycle actions and links out to the edit screen
 * ([RP-FR-029]). "View history" navigates to the placement-history screen ([RP-FR-028]).
 */
class SerializedAssetDetailFragment : Fragment() {

    private val args: SerializedAssetDetailFragmentArgs by navArgs()
    private val viewModel: SerializedAssetDetailViewModel by viewModels {
        SerializedAssetDetailViewModel.provideFactory(requireActivity().application, args.assetLocalId)
    }

    private var _binding: FragmentSerializedAssetDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSerializedAssetDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.detailEditButton.setOnClickListener {
            findNavController().navigate(
                SerializedAssetDetailFragmentDirections
                    .actionSerializedAssetDetailFragmentToSerializedAssetEditFragment(args.assetLocalId)
            )
        }
        binding.detailHistoryButton.setOnClickListener {
            findNavController().navigate(
                SerializedAssetDetailFragmentDirections
                    .actionSerializedAssetDetailFragmentToSerializedPlacementHistoryFragment(args.assetLocalId)
            )
        }
        binding.detailCheckOutButton.setOnClickListener { viewModel.checkOut() }
        binding.detailDeployButton.setOnClickListener { showDeployDialog() }
        binding.detailMoveButton.setOnClickListener { showMoveDialog() }
        binding.detailRetireButton.setOnClickListener { confirmRetire() }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch {
                    viewModel.events.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun render(state: SerializedAssetDetailUiState) {
        binding.detailLoading.isVisible = state is SerializedAssetDetailUiState.Loading
        binding.detailNotFound.isVisible = state is SerializedAssetDetailUiState.NotFound
        binding.detailContent.isVisible = state is SerializedAssetDetailUiState.Ready

        if (state !is SerializedAssetDetailUiState.Ready) return
        val asset = state.asset

        binding.detailName.text = asset.name ?: getString(R.string.serialized_equipment_title)
        binding.detailStatusChip.text = statusLabel(asset.status)

        bindRow(binding.detailManufacturerRow, binding.detailManufacturer, asset.manufacturer)
        bindRow(binding.detailModelRow, binding.detailModel, asset.model)
        bindRow(binding.detailSerialNumberRow, binding.detailSerialNumber, asset.serialNumber)
        bindRow(binding.detailAssetTagRow, binding.detailAssetTag, asset.assetTag)
        bindRow(binding.detailVendorRow, binding.detailVendor, asset.vendor)
        bindRow(binding.detailPurchaseDateRow, binding.detailPurchaseDate, formatDateOnly(asset.purchaseDate))
        bindRow(binding.detailPurchasePriceRow, binding.detailPurchasePrice, asset.purchasePrice)
        bindRow(binding.detailWarrantyRow, binding.detailWarranty, formatDateOnly(asset.warrantyExpiresAt))
        bindRow(binding.detailRentalRateRow, binding.detailRentalRate, asset.rentalDayRate)
        bindRow(binding.detailNoteRow, binding.detailNote, asset.note)

        val placement = state.currentPlacement
        binding.detailPlacementSection.isVisible = placement != null
        placement?.let {
            bindRow(binding.detailPlacementRoomRow, binding.detailPlacementRoom, it.roomName)
            bindRow(binding.detailPlacementProjectRow, binding.detailPlacementProject, it.projectName)
            bindRow(binding.detailPlacementDateRow, binding.detailPlacementDate, it.dateIn)
        }

        // Action affordances derive from the stable status + placement state.
        val deployed = state.isDeployed
        val available = asset.status == "available"
        binding.detailCheckOutButton.isVisible = deployed
        binding.detailMoveButton.isVisible = deployed
        binding.detailDeployButton.isVisible = !deployed && available
        binding.detailRetireButton.isVisible = !deployed && asset.status != "retired"
    }

    /**
     * Purchase date / warranty are date-only concepts the API sends as UTC-midnight ISO timestamps
     * (e.g. "2026-07-09T00:00:00.000000Z"). Show just the calendar date — the UTC date part avoids a
     * timezone off-by-one that parse→local-format would introduce.
     */
    private fun formatDateOnly(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }?.substringBefore('T')

    /** Hide a label/value row when the value is blank (RP-FR-027 acceptance). */
    private fun bindRow(row: LinearLayout, valueView: TextView, value: String?) {
        val text = value?.takeIf { it.isNotBlank() }
        row.isVisible = text != null
        valueView.text = text.orEmpty()
    }

    private fun showDeployDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = viewModel.deployRoomChoices()
            if (rooms.isEmpty()) {
                Toast.makeText(requireContext(), R.string.serialized_detail_no_rooms, Toast.LENGTH_LONG).show()
                return@launch
            }
            val names = rooms.map { "${it.projectName} · ${it.roomName}" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.serialized_equipment_deploy)
                .setItems(names) { _, which -> viewModel.deploy(rooms[which].roomId, rooms[which].projectId) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showMoveDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = viewModel.moveRoomChoices()
            if (rooms.isEmpty()) {
                Toast.makeText(requireContext(), R.string.serialized_equipment_no_rooms, Toast.LENGTH_LONG).show()
                return@launch
            }
            val names = rooms.map { it.roomName }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.serialized_equipment_move)
                .setItems(names) { _, which -> viewModel.move(rooms[which].roomId) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun confirmRetire() {
        val name = (viewModel.uiState.value as? SerializedAssetDetailUiState.Ready)?.asset?.name
            ?: getString(R.string.serialized_equipment_title)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.serialized_equipment_retire)
            .setMessage(getString(R.string.serialized_equipment_retire_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.serialized_equipment_retire) { _, _ -> viewModel.retire() }
            .show()
    }

    private fun statusLabel(status: String): String = when (status) {
        "available" -> getString(R.string.serialized_pool_filter_available)
        "deployed" -> getString(R.string.serialized_pool_filter_deployed)
        "maintenance" -> getString(R.string.serialized_pool_filter_maintenance)
        "retired" -> getString(R.string.serialized_pool_filter_retired)
        else -> status
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
