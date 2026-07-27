package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * RP-FR-026 — standalone, company-wide serialized-equipment pool. Reached only when the active
 * company's [com.example.rocketplan_android.data.feature.SerializedEquipmentMode] is ON; UNKNOWN
 * shows a retryable placeholder (never falls back to legacy), OFF pops back (no legacy company
 * pool exists). Mirrors [SerializedRoomEquipmentFragment] conventions (findViewById, StateFlow +
 * repeatOnLifecycle STARTED, events SharedFlow, 60s mode poll).
 */
class SerializedEquipmentPoolFragment : Fragment() {

    private val args: SerializedEquipmentPoolFragmentArgs by navArgs()
    private val viewModel: SerializedEquipmentPoolViewModel by viewModels {
        SerializedEquipmentPoolViewModel.provideFactory(requireActivity().application, args.companyId)
    }

    private lateinit var content: View
    private lateinit var loading: ProgressBar
    private lateinit var retry: View
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView

    private val adapter = SerializedEquipmentAdapter(
        onClick = { assetId -> navigateToDetail(assetId) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_serialized_equipment_pool, container, false)

    override fun onResume() {
        super.onResume()
        viewModel.refreshMode()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = view.findViewById(R.id.poolContent)
        loading = view.findViewById(R.id.poolLoading)
        retry = view.findViewById(R.id.poolRetry)
        list = view.findViewById(R.id.poolList)
        empty = view.findViewById(R.id.poolEmpty)

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        view.findViewById<MaterialButton>(R.id.poolRegisterButton).setOnClickListener { showRegisterDialog() }
        view.findViewById<MaterialButton>(R.id.poolRetryButton).setOnClickListener { viewModel.retry() }

        view.findViewById<TextInputEditText>(R.id.poolSearchInput).doAfterTextChanged {
            viewModel.setSearchQuery(it?.toString().orEmpty())
        }

        view.findViewById<ChipGroup>(R.id.poolStatusFilter).setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.poolFilterAvailable -> PoolStatusFilter.AVAILABLE
                R.id.poolFilterDeployed -> PoolStatusFilter.DEPLOYED
                R.id.poolFilterMaintenance -> PoolStatusFilter.MAINTENANCE
                else -> PoolStatusFilter.ALL
            }
            viewModel.setStatusFilter(filter)
        }

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
                    viewModel.register(choice.name, choice.catalogUuid, null)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun render(state: SerializedPoolUiState) {
        loading.isVisible = state is SerializedPoolUiState.Loading
        content.isVisible = state is SerializedPoolUiState.Ready
        retry.isVisible = state is SerializedPoolUiState.Unavailable
        when (state) {
            is SerializedPoolUiState.Ready -> {
                val rows = state.items.map {
                    SerializedRowUi(
                        assetId = it.assetId,
                        title = it.name,
                        subtitle = listOfNotNull(it.detail.takeIf(String::isNotBlank), statusLabel(it.status))
                            .joinToString(" · ")
                    )
                }
                adapter.submitList(rows)
                empty.isVisible = rows.isEmpty()
            }
            // Flag flipped OFF — no legacy company-wide pool exists, so leave this screen.
            // RP-BUG-370: guard so a re-emission after the pop doesn't act on a stale destination.
            is SerializedPoolUiState.Disabled -> {
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.serializedEquipmentPoolFragment) nav.navigateUp()
            }
            else -> Unit
        }
    }

    /** RP-FR-027: open the per-unit detail screen (the pool's primary "Details" action). */
    private fun navigateToDetail(assetId: Long) {
        // RP-BUG-370: whole-row tap target — guard against double-navigate on a fast double-tap.
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.serializedEquipmentPoolFragment) return
        nav.navigate(
            SerializedEquipmentPoolFragmentDirections
                .actionSerializedEquipmentPoolFragmentToSerializedAssetDetailFragment(assetId)
        )
    }

    private fun statusLabel(status: String): String = when (status) {
        "available" -> getString(R.string.serialized_pool_filter_available)
        "deployed" -> getString(R.string.serialized_pool_filter_deployed)
        "maintenance" -> getString(R.string.serialized_pool_filter_maintenance)
        "retired" -> getString(R.string.serialized_pool_filter_retired)
        else -> status
    }
}
