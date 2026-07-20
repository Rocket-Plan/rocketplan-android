package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
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
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * RP-FR-019 (Phase 2b) — serialized equipment for a room, reached only when the
 * company's [com.example.rocketplan_android.data.feature.SerializedEquipmentMode]
 * is ON or UNKNOWN (the OFF gate stays on the legacy [EquipmentRoomFragment]).
 *
 * Scope: view the room's deployed units + the company pool, deploy from pool,
 * check out, and retire. Register-new-unit + move need a company catalog picker
 * and a room picker respectively (follow-ups) — the sync layer already supports
 * both.
 */
class SerializedRoomEquipmentFragment : Fragment() {

    private val args: SerializedRoomEquipmentFragmentArgs by navArgs()
    private val viewModel: SerializedRoomEquipmentViewModel by viewModels {
        SerializedRoomEquipmentViewModel.provideFactory(
            requireActivity().application, args.projectId, args.roomId
        )
    }

    private lateinit var content: View
    private lateinit var loading: ProgressBar
    private lateinit var retry: View
    private lateinit var roomTitle: TextView
    private lateinit var deployedList: RecyclerView
    private lateinit var poolList: RecyclerView
    private lateinit var deployedEmpty: TextView
    private lateinit var poolEmpty: TextView

    private val deployedAdapter = SerializedEquipmentAdapter(
        onPrimary = { assetId -> viewModel.checkOut(assetId) },
        onSecondary = { assetId -> showMoveDialog(assetId) },
        onEdit = { assetId -> navigateToEdit(assetId) }
    )
    private val poolAdapter = SerializedEquipmentAdapter(
        onPrimary = { assetId -> viewModel.deployFromPool(assetId) },
        onSecondary = { assetId -> confirmRetire(assetId) },
        onEdit = { assetId -> navigateToEdit(assetId) }
    )

    private var latestPool: List<PoolAssetItem> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_serialized_room_equipment, container, false)

    override fun onResume() {
        super.onResume()
        // Review #2: pick up a backend flag flip when the screen returns to the foreground.
        viewModel.refreshMode()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = view.findViewById(R.id.serializedContent)
        loading = view.findViewById(R.id.serializedLoading)
        retry = view.findViewById(R.id.serializedRetry)
        roomTitle = view.findViewById(R.id.serializedRoomTitle)
        deployedList = view.findViewById(R.id.serializedDeployedList)
        poolList = view.findViewById(R.id.serializedPoolList)
        deployedEmpty = view.findViewById(R.id.serializedDeployedEmpty)
        poolEmpty = view.findViewById(R.id.serializedPoolEmpty)

        deployedList.layoutManager = LinearLayoutManager(context)
        deployedList.adapter = deployedAdapter
        poolList.layoutManager = LinearLayoutManager(context)
        poolList.adapter = poolAdapter

        view.findViewById<MaterialButton>(R.id.serializedRegisterButton).setOnClickListener {
            showRegisterDialog()
        }
        view.findViewById<MaterialButton>(R.id.serializedRetryButton).setOnClickListener { viewModel.retry() }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                // Review #5: surface rejected/failed actions.
                launch {
                    viewModel.events.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                }
                // Continuous backend-flip invalidation: while foregrounded, re-fetch the mode
                // periodically so an emergency rollback (OFF/UNKNOWN) is picked up without user action.
                launch {
                    while (true) {
                        kotlinx.coroutines.delay(60_000)
                        viewModel.refreshMode()
                    }
                }
            }
        }
    }

    /** Register a new serialized unit — pick a catalog item (supplies the required catalog_uuid). */
    private fun showRegisterDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val choices = viewModel.catalogChoices()
            if (choices.isEmpty()) {
                Toast.makeText(requireContext(), R.string.serialized_equipment_no_catalog, Toast.LENGTH_LONG).show()
                return@launch
            }
            val names = choices.map { it.name }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.serialized_equipment_register_cta)
                .setItems(names) { _, which ->
                    val choice = choices[which]
                    viewModel.registerAndDeploy(choice.name, choice.catalogUuid, null)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Move a deployed unit to another room in this project. */
    private fun showMoveDialog(assetId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = viewModel.roomChoices()
            if (rooms.isEmpty()) {
                Toast.makeText(requireContext(), R.string.serialized_equipment_no_rooms, Toast.LENGTH_LONG).show()
                return@launch
            }
            val names = rooms.map { it.name }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.serialized_equipment_move)
                .setItems(names) { _, which -> viewModel.move(assetId, rooms[which].roomId) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Review #6: retiring is destructive — confirm and identify the unit first. */
    private fun confirmRetire(assetId: Long) {
        val item = latestPool.firstOrNull { it.assetId == assetId }
        val identity = item?.let { listOfNotNull(it.name, it.detail.takeIf(String::isNotBlank)).joinToString(" — ") }
            ?: getString(R.string.serialized_equipment_title)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.serialized_equipment_retire)
            .setMessage(getString(R.string.serialized_equipment_retire_confirm, identity))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.serialized_equipment_retire) { _, _ -> viewModel.retire(assetId) }
            .show()
    }

    // TODO(RP-FR-027/RP-FR-028): add a "Details" entry point from the room rows into
    // SerializedAssetDetailFragment. The row's title-tap is currently claimed by Edit and the
    // two action buttons by deploy/check-out/move/retire, so a dedicated affordance needs an
    // item_serialized_equipment layout/adapter change — deferred as entry-point work.

    /** RP-FR-029: open the edit screen for this asset. */
    private fun navigateToEdit(assetId: Long) {
        val action = SerializedRoomEquipmentFragmentDirections
            .actionSerializedRoomEquipmentFragmentToSerializedAssetEditFragment(assetId)
        findNavController().navigate(action)
    }

    private fun render(state: SerializedRoomUiState) {
        loading.isVisible = state is SerializedRoomUiState.Loading
        content.isVisible = state is SerializedRoomUiState.Ready
        retry.isVisible = state is SerializedRoomUiState.Unavailable
        when (state) {
            is SerializedRoomUiState.Ready -> {
                roomTitle.text = state.roomName
                latestPool = state.pool
                val deployed = state.deployed.map {
                    SerializedRowUi(
                        it.assetId, it.name, it.detail,
                        getString(R.string.serialized_equipment_check_out),
                        getString(R.string.serialized_equipment_move)
                    )
                }
                val pool = state.pool.map {
                    SerializedRowUi(
                        it.assetId, it.name, it.detail,
                        getString(R.string.serialized_equipment_deploy),
                        getString(R.string.serialized_equipment_retire)
                    )
                }
                deployedAdapter.submitList(deployed)
                poolAdapter.submitList(pool)
                deployedEmpty.isVisible = deployed.isEmpty()
                poolEmpty.isVisible = pool.isEmpty()
            }
            // Flag flipped OFF while open — fall back to the legacy screen.
            is SerializedRoomUiState.LegacyMode -> {
                val action = SerializedRoomEquipmentFragmentDirections
                    .actionSerializedRoomEquipmentFragmentToEquipmentRoomFragment(
                        projectId = viewModel.projectId,
                        roomId = viewModel.roomId
                    )
                findNavController().navigate(action)
            }
            else -> Unit
        }
    }
}
