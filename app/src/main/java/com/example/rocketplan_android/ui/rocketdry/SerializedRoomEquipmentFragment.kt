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
        onPrimary = { assetId -> viewModel.checkOut(assetId) }
    )
    private val poolAdapter = SerializedEquipmentAdapter(
        onPrimary = { assetId -> viewModel.deployFromPool(assetId) },
        onSecondary = { assetId -> viewModel.retire(assetId) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_serialized_room_equipment, container, false)

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
            // Registering a new unit requires selecting a company catalog item
            // (review #6: catalogUuid is mandatory, no fallback). Catalog integration
            // is a follow-up; the write path (registerEquipmentAssetOffline) is ready.
            Toast.makeText(requireContext(), R.string.serialized_equipment_register_pending, Toast.LENGTH_LONG).show()
        }
        view.findViewById<MaterialButton>(R.id.serializedRetryButton).setOnClickListener { viewModel.retry() }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: SerializedRoomUiState) {
        loading.isVisible = state is SerializedRoomUiState.Loading
        content.isVisible = state is SerializedRoomUiState.Ready
        retry.isVisible = state is SerializedRoomUiState.Unavailable
        when (state) {
            is SerializedRoomUiState.Ready -> {
                roomTitle.text = state.roomName
                val deployed = state.deployed.map {
                    SerializedRowUi(it.assetId, it.name, it.detail, getString(R.string.serialized_equipment_check_out))
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
            is SerializedRoomUiState.LegacyMode -> findNavController().popBackStack()
            else -> Unit
        }
    }
}
