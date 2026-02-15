package com.example.rocketplan_android.ui.crm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.CrmOpportunityDto
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.databinding.FragmentCrmOpportunityDetailBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

class CrmOpportunityDetailFragment : Fragment() {

    private var _binding: FragmentCrmOpportunityDetailBinding? = null
    private val binding get() = _binding!!

    private val args: CrmOpportunityDetailFragmentArgs by navArgs()
    private val viewModel: CrmOpportunityDetailViewModel by viewModels()

    private var hasNavigatedAway = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmOpportunityDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.editButton.setOnClickListener {
            hasNavigatedAway = true
            val action = CrmOpportunityDetailFragmentDirections
                .actionCrmOpportunityDetailToCrmOpportunityForm(args.opportunityId, null)
            findNavController().navigate(action)
        }

        binding.deleteButton.setOnClickListener {
            confirmDelete()
        }

        observeState()
        observeEvents()

        viewModel.loadOpportunity(args.opportunityId)
    }

    override fun onResume() {
        super.onResume()
        // Only reload when returning from another screen (e.g. edit form)
        if (hasNavigatedAway) {
            hasNavigatedAway = false
            viewModel.loadOpportunity(args.opportunityId)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.loadingIndicator.isVisible = state.isLoading
                    binding.contentScrollView.isVisible = !state.isLoading && state.opportunity != null

                    state.opportunity?.let { bindOpportunity(it, state) }
                }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is CrmOpportunityDetailEvent.OpportunityNotFound -> {
                            Toast.makeText(requireContext(), "Failed to load opportunity", Toast.LENGTH_SHORT).show()
                            findNavController().popBackStack()
                        }
                        is CrmOpportunityDetailEvent.OpportunityDeleted -> {
                            Toast.makeText(requireContext(), "Opportunity deleted", Toast.LENGTH_SHORT).show()
                            findNavController().popBackStack()
                        }
                        is CrmOpportunityDetailEvent.StageMoved -> {
                            Toast.makeText(requireContext(), R.string.crm_stage_updated, Toast.LENGTH_SHORT).show()
                        }
                        is CrmOpportunityDetailEvent.ShowError -> {
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun bindOpportunity(opp: CrmOpportunityDto, state: CrmOpportunityDetailUiState) {
        binding.opportunityName.text = opp.name ?: "Untitled"

        // Status badge
        if (!opp.status.isNullOrBlank()) {
            binding.opportunityStatus.text = opp.status.replaceFirstChar { it.uppercaseChar() }
            binding.opportunityStatus.isVisible = true
        }

        // Contact
        val contactName = opp.contact?.let {
            listOfNotNull(
                it.firstName?.trim()?.takeIf { n -> n.isNotBlank() },
                it.lastName?.trim()?.takeIf { n -> n.isNotBlank() }
            ).joinToString(" ").takeIf { n -> n.isNotBlank() }
        }
        binding.opportunityContact.text = contactName ?: "\u2014"

        // Pipeline
        binding.opportunityPipeline.text = opp.pipeline?.name ?: "\u2014"

        // Stage — make clickable if we have stages to pick from
        val stageName = opp.stage?.name ?: "\u2014"
        if (state.pipelineStages.isNotEmpty()) {
            binding.opportunityStage.text = "$stageName \u25BE" // ▾ dropdown hint
            binding.opportunityStage.setOnClickListener { showStagePicker(opp, state) }
            binding.opportunityStage.isClickable = true
        } else {
            binding.opportunityStage.text = stageName
            binding.opportunityStage.setOnClickListener(null)
            binding.opportunityStage.isClickable = false
        }

        // Monetary value
        if (opp.monetaryValue != null && opp.monetaryValue > 0) {
            binding.opportunityValue.text = NumberFormat.getCurrencyInstance(Locale.US).format(opp.monetaryValue)
        } else {
            binding.opportunityValue.text = "\u2014"
        }

        // Source
        binding.opportunitySource.text = opp.source?.takeIf { it.isNotBlank() } ?: "\u2014"

        // Date added
        binding.opportunityDateAdded.text = formatDate(opp.dateAdded) ?: formatDate(opp.createdAt) ?: "\u2014"

        // Custom fields — show all definitions, with saved values where available
        CrmCustomFieldRenderer.render(
            requireContext(),
            binding.customFieldsContainer,
            state.customFieldDefinitions,
            opp.customFields
        )
    }

    private fun showStagePicker(opp: CrmOpportunityDto, state: CrmOpportunityDetailUiState) {
        val stages = state.pipelineStages
        val stageNames = stages.mapNotNull { it.name }.toTypedArray()
        val currentIndex = stages.indexOfFirst { it.id == opp.pipelineStageId }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_move_to_stage)
            .setSingleChoiceItems(stageNames, currentIndex) { dialog, which ->
                val selectedStage = stages.getOrNull(which)
                if (selectedStage != null && selectedStage.id != null && selectedStage.id != opp.pipelineStageId) {
                    viewModel.moveToStage(opp.id ?: return@setSingleChoiceItems, selectedStage.id)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_delete_opportunity_title)
            .setMessage(R.string.crm_delete_opportunity_message)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteOpportunity(args.opportunityId) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatDate(dateStr: String?): String? {
        if (dateStr.isNullOrBlank()) return null
        val date = DateUtils.parseApiDate(dateStr) ?: return dateStr.take(10)
        return SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
