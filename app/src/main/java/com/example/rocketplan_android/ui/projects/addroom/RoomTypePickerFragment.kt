package com.example.rocketplan_android.ui.projects.addroom

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentRoomTypePickerBinding
import kotlinx.coroutines.launch
import java.util.Locale

class RoomTypePickerFragment : Fragment() {

    private val args: RoomTypePickerFragmentArgs by navArgs()

    private var _binding: FragmentRoomTypePickerBinding? = null
    private val binding get() = _binding!!

    private val pickerMode: RoomTypePickerMode by lazy {
        RoomTypePickerMode.fromName(args.mode)
    }

    private val viewModel: RoomTypePickerViewModel by viewModels {
        RoomTypePickerViewModel.Factory(
            requireActivity().application,
            args.projectId,
            pickerMode
        )
    }

    private lateinit var adapter: RoomTypeAdapter
    private var currentQuery: String = ""
    private var latestItems: List<RoomTypeUiModel> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoomTypePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().title = getString(pickerMode.titleRes)
        binding.roomTypeHeader.text = getString(pickerMode.titleRes)
        setupList()
        setupSearch()
        observeUiState()
        observeEvents()
    }

    override fun onDestroyView() {
        binding.roomTypeRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupList() {
        adapter = RoomTypeAdapter { option ->
            viewModel.createRoom(option)
        }
        binding.roomTypeRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.roomTypeRecyclerView.adapter = adapter
        binding.roomTypeRecyclerView.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
    }

    private fun setupSearch() {
        binding.roomTypeSearchInput.doOnTextChanged { text, _, _, _ ->
            currentQuery = text?.toString().orEmpty()
            submitFilteredItems()
        }
        binding.roomTypeSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.root.clearFocus()
            }
            false
        }
        binding.roomTypeRetryButton.setOnClickListener { viewModel.refresh(force = true) }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    latestItems = state.items
                    renderState(state)
                }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is RoomTypePickerEvent.RoomCreated -> {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.room_type_selected_toast, event.roomName),
                                Toast.LENGTH_SHORT
                            ).show()
                            findNavController().navigateUp()
                        }
                        is RoomTypePickerEvent.RoomCreationFailed -> {
                            Toast.makeText(
                                requireContext(),
                                event.message ?: getString(R.string.room_type_error_generic),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: RoomTypePickerUiState) {
        binding.roomTypeLoadingIndicator.isVisible =
            (state.isLoading && state.items.isEmpty()) || state.isCreating
        binding.roomTypeErrorState.isVisible =
            state.errorMessage != null && state.items.isEmpty() && !state.isCreating
        binding.roomTypeErrorState.text = state.errorMessage
        binding.roomTypeRetryButton.isVisible = binding.roomTypeErrorState.isVisible
        binding.roomTypeEmptyState.isVisible =
            !state.isLoading && !state.isCreating && state.items.isEmpty() && state.errorMessage == null
        binding.roomTypeSearchLayout.isEnabled = !state.isCreating
        binding.roomTypeSearchInput.isEnabled = !state.isCreating
        binding.roomTypeRecyclerView.isEnabled = !state.isCreating
        submitFilteredItems()
    }

    private fun submitFilteredItems() {
        val normalizedQuery = currentQuery.trim().lowercase(Locale.US)
        val filtered = latestItems.filter { item ->
            normalizedQuery.isBlank() || item.displayName.lowercase(Locale.US).contains(normalizedQuery)
        }
        val grouped = filtered
            .groupBy { it.category }
            .toSortedMap(compareBy { it.order })
            .flatMap { (category, items) ->
                if (items.isEmpty()) emptyList()
                else listOf(RoomTypeListItem.Header(category.displayName)) +
                    items.sortedBy { it.displayName.lowercase(Locale.US) }
                        .map { RoomTypeListItem.Option(it) }
            }
        adapter.submitList(grouped)
        val hasOptions = grouped.any { it is RoomTypeListItem.Option }
        binding.roomTypeRecyclerView.isVisible = hasOptions
        binding.roomTypeEmptyState.isVisible = !binding.roomTypeLoadingIndicator.isVisible && !binding.roomTypeErrorState.isVisible && !hasOptions
    }
}
