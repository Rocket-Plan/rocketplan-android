package com.example.rocketplan_android.ui.conflict

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.repository.ConflictItem
import com.example.rocketplan_android.data.repository.ConflictResolution
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment displaying the list of unresolved sync conflicts.
 */
class ConflictListFragment : Fragment() {

    private val viewModel: ConflictListViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var batchActionsCard: MaterialCardView
    private lateinit var keepAllLocalButton: MaterialButton
    private lateinit var keepAllServerButton: MaterialButton
    private lateinit var dismissAllButton: MaterialButton
    private lateinit var conflictRecyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var loadingState: FrameLayout

    private lateinit var adapter: ConflictListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_conflict_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar = view.findViewById(R.id.toolbar)
        batchActionsCard = view.findViewById(R.id.batchActionsCard)
        keepAllLocalButton = view.findViewById(R.id.keepAllLocalButton)
        keepAllServerButton = view.findViewById(R.id.keepAllServerButton)
        dismissAllButton = view.findViewById(R.id.dismissAllButton)
        conflictRecyclerView = view.findViewById(R.id.conflictRecyclerView)
        emptyState = view.findViewById(R.id.emptyState)
        loadingState = view.findViewById(R.id.loadingState)

        setupToolbar()
        setupRecyclerView()
        setupBatchActions()
        observeUiState()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        adapter = ConflictListAdapter { conflict ->
            showConflictDetailDialog(conflict)
        }
        conflictRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        conflictRecyclerView.adapter = adapter
    }

    private fun setupBatchActions() {
        keepAllLocalButton.setOnClickListener {
            showBatchResolutionConfirmation(ConflictResolution.KEEP_LOCAL)
        }

        keepAllServerButton.setOnClickListener {
            showBatchResolutionConfirmation(ConflictResolution.KEEP_SERVER)
        }

        dismissAllButton.setOnClickListener {
            showBatchResolutionConfirmation(ConflictResolution.DISMISS)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    when (state) {
                        is ConflictListUiState.Loading -> {
                            loadingState.isVisible = true
                            conflictRecyclerView.isVisible = false
                            emptyState.isVisible = false
                            batchActionsCard.isVisible = false
                        }
                        is ConflictListUiState.Success -> {
                            loadingState.isVisible = false
                            if (state.conflicts.isEmpty()) {
                                conflictRecyclerView.isVisible = false
                                emptyState.isVisible = true
                                batchActionsCard.isVisible = false
                            } else {
                                conflictRecyclerView.isVisible = true
                                emptyState.isVisible = false
                                batchActionsCard.isVisible = state.conflicts.size > 1
                                adapter.submitList(state.conflicts)
                            }
                        }
                        is ConflictListUiState.Error -> {
                            loadingState.isVisible = false
                            conflictRecyclerView.isVisible = false
                            emptyState.isVisible = true
                            batchActionsCard.isVisible = false
                            Toast.makeText(
                                requireContext(),
                                state.message,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.resolving.collectLatest { resolving ->
                    keepAllLocalButton.isEnabled = !resolving
                    keepAllServerButton.isEnabled = !resolving
                    dismissAllButton.isEnabled = !resolving
                }
            }
        }
    }

    private fun showConflictDetailDialog(conflict: ConflictItem) {
        ConflictDetailDialog.newInstance(conflict.conflictId).show(
            childFragmentManager,
            ConflictDetailDialog.TAG
        )
    }

    private fun showBatchResolutionConfirmation(resolution: ConflictResolution) {
        val message = when (resolution) {
            ConflictResolution.KEEP_LOCAL -> getString(R.string.conflict_batch_keep_local_message)
            ConflictResolution.KEEP_SERVER -> getString(R.string.conflict_batch_keep_server_message)
            ConflictResolution.DISMISS -> getString(R.string.conflict_batch_dismiss_message)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.conflict_batch_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.conflict_confirm) { _, _ ->
                viewModel.resolveAll(resolution)
                Toast.makeText(
                    requireContext(),
                    R.string.conflict_resolving_all,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
