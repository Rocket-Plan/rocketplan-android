package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.databinding.FragmentSerializedPlacementHistoryBinding
import kotlinx.coroutines.launch

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

    private val adapter = PlacementHistoryAdapter()

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
