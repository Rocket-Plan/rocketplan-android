package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class ProjectDetailFragment : Fragment() {

    companion object {
        private const val TAG = "ProjectDetailFragment"
    }

    private val viewModel: ProjectsViewModel by activityViewModels()
    private val args: ProjectDetailFragmentArgs by navArgs()

    private lateinit var backButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var projectAddress: TextView
    private lateinit var wipBadge: TextView
    private lateinit var addAliasButton: TextView
    private lateinit var projectNumber: TextView
    private lateinit var addProjectInfoCard: MaterialCardView
    private lateinit var rocketScanCard: MaterialCardView
    private lateinit var rocketDryCard: MaterialCardView
    private lateinit var projectNotesCard: MaterialCardView
    private lateinit var crewCard: MaterialCardView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_project_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupClickListeners()
        observeViewModel()
    }

    private fun initializeViews(view: View) {
        backButton = view.findViewById(R.id.backButton)
        menuButton = view.findViewById(R.id.menuButton)
        projectAddress = view.findViewById(R.id.projectAddress)
        wipBadge = view.findViewById(R.id.wipBadge)
        addAliasButton = view.findViewById(R.id.addAliasButton)
        projectNumber = view.findViewById(R.id.projectNumber)
        addProjectInfoCard = view.findViewById(R.id.addProjectInfoCard)
        rocketScanCard = view.findViewById(R.id.rocketScanCard)
        rocketDryCard = view.findViewById(R.id.rocketDryCard)
        projectNotesCard = view.findViewById(R.id.projectNotesCard)
        crewCard = view.findViewById(R.id.crewCard)
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        menuButton.setOnClickListener {
            // TODO: Show project menu
            Toast.makeText(context, "Project Menu", Toast.LENGTH_SHORT).show()
        }

        addAliasButton.setOnClickListener {
            // TODO: Navigate to add alias screen
            Toast.makeText(context, "Add Alias", Toast.LENGTH_SHORT).show()
        }

        addProjectInfoCard.setOnClickListener {
            // TODO: Navigate to project/loss info screen
            Toast.makeText(context, "Add Project/Loss Info", Toast.LENGTH_SHORT).show()
        }

        rocketScanCard.setOnClickListener {
            // TODO: Navigate to RocketScan screen
            Toast.makeText(context, "RocketScan", Toast.LENGTH_SHORT).show()
        }

        rocketDryCard.setOnClickListener {
            // TODO: Navigate to RocketDry screen
            Toast.makeText(context, "RocketDry", Toast.LENGTH_SHORT).show()
        }

        projectNotesCard.setOnClickListener {
            // TODO: Navigate to project notes screen
            Toast.makeText(context, "All Project Notes", Toast.LENGTH_SHORT).show()
        }

        crewCard.setOnClickListener {
            // TODO: Navigate to crew management screen
            Toast.makeText(context, "Crew", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is ProjectsUiState.Success -> {
                        // Find the project in the combined list
                        val project = (state.myProjects + state.wipProjects)
                            .find { it.projectId == args.projectId }

                        project?.let {
                            updateProjectDetails(it)
                        }
                    }
                    else -> {
                        // Handle loading or error states if needed
                    }
                }
            }
        }
    }

    private fun updateProjectDetails(project: ProjectListItem) {
        projectAddress.text = project.title
        projectNumber.text = project.projectCode

        // Show WIP badge if project status is WIP or draft
        val isWip = project.status.equals("wip", ignoreCase = true) ||
                project.status.equals("draft", ignoreCase = true)
        wipBadge.isVisible = isWip
    }
}
