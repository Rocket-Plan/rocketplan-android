package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.ProjectStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * RP-FR-034 — pure, testable project-search predicate. [query] must already be trimmed + lowercased.
 * Matches iOS (`ProjectListPageView`): address (title), project name (alias), and RP number
 * (projectCode), case-insensitive substring.
 */
internal fun projectMatchesQuery(project: ProjectListItem, query: String): Boolean =
    project.title.lowercase().contains(query) ||
        project.projectCode.lowercase().contains(query) ||
        (project.alias?.lowercase()?.contains(query) == true)

class ProjectListFragment : Fragment() {

    companion object {
        private const val ARG_TAB_KEY = "tab_key"
        private const val TAB_MY_PROJECTS = "my_projects"

        fun newMyProjectsInstance(): ProjectListFragment =
            ProjectListFragment().apply {
                arguments = bundleOf(ARG_TAB_KEY to TAB_MY_PROJECTS)
            }

        fun newStatusInstance(status: ProjectStatus): ProjectListFragment =
            ProjectListFragment().apply {
                arguments = bundleOf(ARG_TAB_KEY to status.apiValue)
            }
    }

    private val viewModel: ProjectsViewModel by activityViewModels()
    private lateinit var adapter: ProjectsAdapter
    private var tabKey: String = TAB_MY_PROJECTS
    private var statusFilter: ProjectStatus? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var emptyStateLayout: View
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabKey = arguments?.getString(ARG_TAB_KEY) ?: TAB_MY_PROJECTS
        statusFilter = ProjectStatus.fromApiValue(tabKey)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_project_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.projectsRecyclerView)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout)
        progressBar = view.findViewById(R.id.progressBar)

        setupAdapter()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupAdapter() {
        adapter = ProjectsAdapter { project ->
            onProjectClick(project)
        }
        recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshProjects()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // RP-FR-034: fold the shared search query into the state so the list filters live.
                combine(viewModel.uiState, viewModel.searchQuery) { state, query -> state to query }
                    .collect { (state, query) ->
                        when (state) {
                            is ProjectsUiState.Loading -> {
                                progressBar.isVisible = true
                                recyclerView.isVisible = false
                                emptyStateLayout.isVisible = false
                            }
                            is ProjectsUiState.Success -> {
                                progressBar.isVisible = false

                                val projects = if (query.isBlank()) {
                                    // No search: show just this tab's category.
                                    when {
                                        tabKey == TAB_MY_PROJECTS -> state.myProjects
                                        statusFilter != null -> state.projectsByStatus[statusFilter] ?: emptyList()
                                        else -> emptyList()
                                    }
                                } else {
                                    // RP-FR-034: global search — match across EVERY category (any status
                                    // + My Projects), deduped, so a query finds any project from any tab.
                                    (state.myProjects + state.projectsByStatus.values.flatten())
                                        .distinctBy { it.projectId }
                                        .let { filterBySearch(it, query) }
                                }

                                if (projects.isEmpty()) {
                                    recyclerView.isVisible = false
                                    emptyStateLayout.isVisible = true
                                } else {
                                    recyclerView.isVisible = true
                                    emptyStateLayout.isVisible = false
                                    adapter.submitList(projects)
                                }
                            }
                            is ProjectsUiState.Error -> {
                                progressBar.isVisible = false
                                recyclerView.isVisible = false
                                emptyStateLayout.isVisible = true
                                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
            }
        }

        viewModel.isRefreshing.observe(viewLifecycleOwner) { isRefreshing ->
            swipeRefreshLayout.isRefreshing = isRefreshing
        }
    }

    /**
     * RP-FR-034: client-side project filter (iOS parity — matches address / name / RP number).
     * Case-insensitive substring; blank query returns the full list. Works offline.
     */
    private fun filterBySearch(projects: List<ProjectListItem>, query: String): List<ProjectListItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return projects
        return projects.filter { projectMatchesQuery(it, q) }
    }

    private fun onProjectClick(project: ProjectListItem) {
        viewModel.prioritizeProject(project.projectId)
        // Navigate to project detail
        val action = ProjectsFragmentDirections.actionNavProjectsToProjectLandingFragment(project.projectId)
        val navController = requireParentFragment().findNavController()
        if (navController.currentDestination?.id == R.id.nav_projects) {
            navController.navigate(action)
        }
    }
}
