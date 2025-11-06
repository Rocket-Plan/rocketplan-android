package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
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
import kotlinx.coroutines.launch

/**
 * Lightweight landing screen that presents the primary project actions before
 * drilling into deeper sections like RocketScan.
 */
class ProjectLandingFragment : Fragment() {

    private val args: ProjectLandingFragmentArgs by navArgs()

    private val viewModel: ProjectLandingViewModel by viewModels {
        ProjectLandingViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var menuButton: ImageButton
    private lateinit var projectTitle: TextView
    private lateinit var aliasAction: TextView
    private lateinit var projectCode: TextView
    private lateinit var statusBadge: TextView
    private lateinit var statusContainer: View
    private lateinit var projectInfoCard: View
    private lateinit var projectActionsContainer: View
    private lateinit var emptyState: TextView

    private lateinit var addProjectInfoCard: View
    private lateinit var addProjectInfoIcon: ImageView
    private lateinit var rocketScanCard: View
    private lateinit var rocketScanIcon: ImageView
    private lateinit var allNotesCard: View
    private lateinit var allNotesIcon: ImageView
    private lateinit var allNotesSubtitle: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_landing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        bindListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        menuButton = root.findViewById(R.id.menuButton)
        projectTitle = root.findViewById(R.id.projectTitle)
        aliasAction = root.findViewById(R.id.projectAliasAction)
        projectCode = root.findViewById(R.id.projectCode)
        statusBadge = root.findViewById(R.id.projectStatusBadge)
        statusContainer = root.findViewById(R.id.projectStatusContainer)
        projectInfoCard = root.findViewById(R.id.projectInfoCard)
        projectActionsContainer = root.findViewById(R.id.projectActionsContainer)
        emptyState = root.findViewById(R.id.projectEmptyState)

        addProjectInfoCard = root.findViewById(R.id.addProjectInfoCard)
        addProjectInfoIcon = root.findViewById(R.id.addProjectInfoIcon)
        rocketScanCard = root.findViewById(R.id.rocketScanCard)
        rocketScanIcon = root.findViewById(R.id.rocketScanIcon)
        allNotesCard = root.findViewById(R.id.allNotesCard)
        allNotesIcon = root.findViewById(R.id.allNotesIcon)
        allNotesSubtitle = root.findViewById(R.id.allNotesSubtitle)

    }

    private fun bindListeners() {
        menuButton.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.menu), Toast.LENGTH_SHORT).show()
        }
        aliasAction.setOnClickListener {
            if (aliasAction.isEnabled) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.project_alias_coming_soon),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        addProjectInfoCard.setOnClickListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.add_project_info_coming_soon),
                Toast.LENGTH_SHORT
            ).show()
        }
        rocketScanCard.setOnClickListener {
            val action = ProjectLandingFragmentDirections
                .actionProjectLandingFragmentToRocketScanFragment(args.projectId)
            findNavController().navigate(action)
        }
        allNotesCard.setOnClickListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.all_notes_coming_soon),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        ProjectLandingUiState.Loading -> showLoadingState()
                        is ProjectLandingUiState.Ready -> renderState(state.summary)
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        projectInfoCard.isVisible = false
        projectActionsContainer.isVisible = false
        emptyState.isVisible = true
        emptyState.text = getString(R.string.loading_project)
    }

    private fun renderState(summary: ProjectLandingSummary) {
        projectInfoCard.isVisible = true
        projectActionsContainer.isVisible = true
        emptyState.isVisible = false

        projectTitle.text = summary.projectTitle
        projectCode.isVisible = summary.projectCode.isNotBlank()
        projectCode.text = summary.projectCode

        val aliasText = summary.aliasText ?: getString(R.string.add_alias)
        aliasAction.text = aliasText
        aliasAction.isEnabled = summary.aliasIsActionable
        aliasAction.isClickable = summary.aliasIsActionable
        val aliasColor = if (summary.aliasIsActionable) {
            requireContext().getColor(R.color.main_purple)
        } else {
            requireContext().getColor(R.color.light_text_rp)
        }
        aliasAction.setTextColor(aliasColor)

        if (summary.statusLabel != null) {
            statusContainer.isVisible = true
            statusBadge.text = summary.statusLabel
        } else {
            statusContainer.isVisible = false
        }

        allNotesSubtitle.text = getString(
            R.string.project_notes_subtitle_with_count,
            summary.noteCount
        )
    }
}
