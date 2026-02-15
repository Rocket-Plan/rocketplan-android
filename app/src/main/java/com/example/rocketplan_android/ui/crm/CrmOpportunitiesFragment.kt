package com.example.rocketplan_android.ui.crm

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Color
import android.os.Bundle
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
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
import com.example.rocketplan_android.databinding.FragmentCrmOpportunitiesBinding
import kotlinx.coroutines.launch

class CrmOpportunitiesFragment : Fragment() {

    private var _binding: FragmentCrmOpportunitiesBinding? = null
    private val binding get() = _binding!!

    val viewModel: CrmOpportunitiesViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmOpportunitiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        // Prevent SwipeRefreshLayout from intercepting horizontal scroll gestures
        binding.boardScrollView.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    binding.swipeRefresh.isEnabled = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    binding.swipeRefresh.isEnabled = true
                }
            }
            false
        }

        setupStatusChips()
        observeState()
        observeEvents()
    }

    private fun setupStatusChips() {
        val chipToStatus = mapOf(
            binding.chipOpen to "open",
            binding.chipWon to "won",
            binding.chipLost to "lost",
            binding.chipAbandoned to "abandoned"
        )

        chipToStatus.forEach { (chip, status) ->
            chip.setOnCheckedChangeListener { _, _ ->
                viewModel.toggleStatus(status)
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    binding.loadingIndicator.isVisible = state.isLoading
                    binding.errorText.isVisible = state.error != null
                    binding.errorText.text = state.error

                    // Pipeline selector
                    if (state.pipelines.isNotEmpty()) {
                        setupPipelineSelector(state)
                        binding.pipelineSelector.isVisible = true
                        binding.statusChipGroup.isVisible = true
                    } else {
                        binding.pipelineSelector.isVisible = false
                        binding.statusChipGroup.isVisible = false
                    }

                    // Kanban board
                    if (!state.isLoading && state.error == null) {
                        binding.boardScrollView.isVisible = true
                        buildKanbanBoard(state.columns)
                    } else {
                        binding.boardScrollView.isVisible = false
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is CrmOpportunitiesEvent.ShowError -> {
                            android.widget.Toast.makeText(requireContext(), event.message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        is CrmOpportunitiesEvent.StageMoved -> { /* UI already updated optimistically */ }
                    }
                }
            }
        }
    }

    private var lastPipelineSetup: List<String>? = null

    private fun setupPipelineSelector(state: CrmOpportunitiesUiState) {
        val pipelineNames = state.pipelines.mapNotNull { it.name }
        if (pipelineNames == lastPipelineSetup) return
        lastPipelineSetup = pipelineNames

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            pipelineNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.pipelineSelector.adapter = adapter

        val selectedIndex = state.pipelines.indexOfFirst { it.id == state.selectedPipelineId }
        if (selectedIndex >= 0) {
            binding.pipelineSelector.setSelection(selectedIndex)
        }

        binding.pipelineSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val currentPipelines = viewModel.uiState.value.pipelines
                val pipelineId = currentPipelines.getOrNull(position)?.id ?: return
                if (pipelineId != viewModel.uiState.value.selectedPipelineId) {
                    viewModel.selectPipeline(pipelineId)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun confirmDeleteOpportunity(card: OpportunityCard) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_delete_opportunity_title)
            .setMessage(R.string.crm_delete_opportunity_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteOpportunity(card.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun navigateToOpportunityDetail(opportunityId: String) {
        try {
            val action = CrmContactsFragmentDirections
                .actionCrmContactsToCrmOpportunityDetail(opportunityId)
            parentFragment?.findNavController()?.navigate(action)
        } catch (e: Exception) {
            android.util.Log.w("CrmOpportunities", "Navigation to opportunity detail failed", e)
        }
    }

    private fun buildKanbanBoard(columns: List<KanbanColumn>) {
        val scrollX = binding.boardScrollView.scrollX
        binding.columnsContainer.removeAllViews()

        if (columns.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = getString(R.string.crm_no_opportunities)
                setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.light_text_rp))
                textSize = 14f
                setPadding(32, 64, 32, 64)
            }
            binding.columnsContainer.addView(emptyView)
            return
        }

        val inflater = LayoutInflater.from(requireContext())

        for (column in columns) {
            val columnView = inflater.inflate(R.layout.item_kanban_column, binding.columnsContainer, false)

            val headerView = columnView.findViewById<TextView>(R.id.columnHeader)
            val countView = columnView.findViewById<TextView>(R.id.columnCount)
            val totalView = columnView.findViewById<TextView>(R.id.columnTotal)
            val recyclerView = columnView.findViewById<RecyclerView>(R.id.columnRecyclerView)
            val emptyText = columnView.findViewById<TextView>(R.id.columnEmpty)

            headerView.text = column.stageName
            countView.text = column.opportunities.size.toString()

            if (column.monetaryTotal > 0) {
                totalView.isVisible = true
                totalView.text = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US).format(column.monetaryTotal)
            } else {
                totalView.isVisible = false
            }

            // Set up drag target on the entire column
            val card = columnView as com.google.android.material.card.MaterialCardView
            val defaultStrokeColor = card.strokeColor
            val defaultStrokeWidth = card.strokeWidth

            columnView.setOnDragListener { v, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                    }
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        // Highlight column with purple border and slight scale
                        card.strokeColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.main_purple)
                        card.strokeWidth = (3 * resources.displayMetrics.density).toInt()
                        AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.02f),
                                ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.02f)
                            )
                            duration = 150
                        }.start()
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        // Restore column
                        card.strokeColor = defaultStrokeColor
                        card.strokeWidth = defaultStrokeWidth
                        AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 1.02f, 1f),
                                ObjectAnimator.ofFloat(v, "scaleY", 1.02f, 1f)
                            )
                            duration = 150
                        }.start()
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        // Drop animation — bounce
                        card.strokeColor = defaultStrokeColor
                        card.strokeWidth = defaultStrokeWidth
                        AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 1.02f, 1f),
                                ObjectAnimator.ofFloat(v, "scaleY", 1.02f, 1f)
                            )
                            duration = 200
                            interpolator = OvershootInterpolator(2f)
                        }.start()
                        val opportunityId = event.clipData.getItemAt(0).text.toString()
                        viewModel.moveOpportunityToStage(opportunityId, column.stageId)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        // Restore column and source card
                        card.strokeColor = defaultStrokeColor
                        card.strokeWidth = defaultStrokeWidth
                        v.scaleX = 1f
                        v.scaleY = 1f
                        // Restore the dragged card's alpha
                        (event.localState as? View)?.let { draggedView ->
                            draggedView.alpha = 1f
                            draggedView.scaleX = 1f
                            draggedView.scaleY = 1f
                        }
                        true
                    }
                    else -> false
                }
            }

            recyclerView.isNestedScrollingEnabled = false

            if (column.opportunities.isEmpty()) {
                recyclerView.isVisible = false
                emptyText.isVisible = true
            } else {
                recyclerView.isVisible = true
                emptyText.isVisible = false
                recyclerView.layoutManager = LinearLayoutManager(requireContext())
                val adapter = OpportunityCardAdapter(
                    onCardClick = { card -> navigateToOpportunityDetail(card.id) },
                    onDeleteClick = { card -> confirmDeleteOpportunity(card) }
                )
                recyclerView.adapter = adapter
                adapter.submitList(column.opportunities)
            }

            binding.columnsContainer.addView(columnView)
        }

        // Restore horizontal scroll position after rebuild
        binding.boardScrollView.post { binding.boardScrollView.scrollTo(scrollX, 0) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
