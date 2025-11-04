package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.rocketplan_android.R
import kotlinx.coroutines.launch

class ProjectListFragment : Fragment() {

    companion object {
        private const val ARG_TAB_TYPE = "tab_type"
        const val TAB_MY_PROJECTS = 0
        const val TAB_WIP = 1

        fun newInstance(tabType: Int): ProjectListFragment {
            return ProjectListFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TAB_TYPE, tabType)
                }
            }
        }
    }

    private val viewModel: ProjectsViewModel by activityViewModels()
    private lateinit var adapter: ProjectsAdapter
    private var tabType: Int = TAB_MY_PROJECTS

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var emptyStateLayout: View
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabType = arguments?.getInt(ARG_TAB_TYPE) ?: TAB_MY_PROJECTS
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
            viewModel.uiState.collect { state ->
                when (state) {
                    is ProjectsUiState.Loading -> {
                        progressBar.isVisible = true
                        recyclerView.isVisible = false
                        emptyStateLayout.isVisible = false
                    }
                    is ProjectsUiState.Success -> {
                        progressBar.isVisible = false

                        val projects = when (tabType) {
                            TAB_MY_PROJECTS -> state.myProjects
                            TAB_WIP -> state.wipProjects
                            else -> emptyList()
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

        viewModel.isRefreshing.observe(viewLifecycleOwner) { isRefreshing ->
            swipeRefreshLayout.isRefreshing = isRefreshing
        }
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
