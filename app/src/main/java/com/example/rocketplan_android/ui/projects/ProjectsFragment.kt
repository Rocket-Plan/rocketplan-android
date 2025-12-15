package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.repository.AuthRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class ProjectsFragment : Fragment() {

    companion object {
        private const val TAG = "ProjectsFragment"
        private const val ARG_INITIAL_TAB = "initialTab"
        private const val TAB_MY_PROJECTS_KEY = "my_projects"
    }

    private val viewModel: ProjectsViewModel by activityViewModels()
    private lateinit var rocketPlanApp: RocketPlanApplication
    private lateinit var authRepository: AuthRepository

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var userInitials: TextView
    private lateinit var refreshButton: ImageView
    private lateinit var fabNewProject: FloatingActionButton
    private lateinit var tabs: List<ProjectTab>
    private var initialTabKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialTabKey = arguments?.getString(ARG_INITIAL_TAB)?.takeIf { it.isNotBlank() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_projects, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rocketPlanApp = requireActivity().application as RocketPlanApplication
        authRepository = rocketPlanApp.authRepository

        tabLayout = view.findViewById(R.id.tabLayout)
        viewPager = view.findViewById(R.id.viewPager)
        userInitials = view.findViewById(R.id.userInitials)
        refreshButton = view.findViewById(R.id.refreshButton)
        fabNewProject = view.findViewById(R.id.fabNewProject)

        setupViewPager()
        setupUserInterface()
    }

    private fun setupViewPager() {
        tabs = buildList {
            add(ProjectTab.MyProjects)
            val statusTabs = listOf(ProjectStatus.WIP) +
                ProjectStatus.orderedStatuses.filterNot { it == ProjectStatus.WIP }
            addAll(statusTabs.map { ProjectTab.Status(it) })
        }

        val adapter = ProjectsPagerAdapter(this, tabs)
        viewPager.adapter = adapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (val tabItem = tabs[position]) {
                ProjectTab.MyProjects -> tab.text = getString(R.string.projects_tab_my_projects)
                is ProjectTab.Status -> tab.text = getString(tabItem.status.labelRes)
            }
        }.attach()

        applyInitialTabSelection()
    }

    private fun setupUserInterface() {
        loadUserInitials()

        refreshButton.setOnClickListener {
            viewModel.refreshProjects()
            Toast.makeText(context, R.string.refreshing, Toast.LENGTH_SHORT).show()
        }

        fabNewProject.setOnClickListener {
            findNavController().navigate(R.id.action_nav_projects_to_createProjectFragment)
        }

        userInitials.setOnClickListener {
            showProfileMenu(it)
        }
    }

    private fun loadUserInitials() {
        viewLifecycleOwner.lifecycleScope.launch {
            val savedEmail = authRepository.getSavedEmail()
            val initials = savedEmail
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { email ->
                    email.split("@").firstOrNull()
                        ?.split(".", "_", "-")
                        ?.filter { it.isNotBlank() }
                        ?.map { it.trim().firstOrNull()?.uppercaseChar() }
                        ?.filterNotNull()
                        ?.joinToString("")
                }
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.projects_tab_my_projects).take(2).uppercase()
            userInitials.text = initials
        }
    }

    private fun applyInitialTabSelection() {
        val key = initialTabKey?.lowercase() ?: return
        val status = ProjectStatus.fromApiValue(key)
        val targetIndex = when {
            key == TAB_MY_PROJECTS_KEY -> 0
            status != null -> tabs.indexOfFirst { tab ->
                tab is ProjectTab.Status && tab.status == status
            }
            else -> -1
        }
        if (targetIndex >= 0) {
            initialTabKey = null
            viewPager.post {
                viewPager.currentItem = targetIndex
            }
        }
    }

    private fun showProfileMenu(anchor: View) {
        PopupMenu(requireContext(), anchor, Gravity.END).apply {
            menuInflater.inflate(R.menu.profile_menu, menu)
            menu.findItem(R.id.action_test_flir)?.isVisible = BuildConfig.HAS_FLIR_SUPPORT
            menu.findItem(R.id.action_test_flir_ir_only)?.isVisible = BuildConfig.HAS_FLIR_SUPPORT
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_company_info -> {
                        findNavController().navigate(R.id.companyInfoFragment)
                        true
                    }
                    R.id.action_sync_status -> {
                        findNavController().navigate(R.id.syncStatusFragment)
                        true
                    }
                    R.id.action_image_processor_assemblies -> {
                        findNavController().navigate(R.id.imageProcessorAssembliesFragment)
                        true
                    }
                    R.id.action_reload_image_processor_config -> {
                        findNavController().navigate(R.id.imageProcessorConfigFragment)
                        true
                    }
                    R.id.action_test_flir -> {
                        findNavController().navigate(R.id.flirTestFragment)
                        true
                    }
                    R.id.action_test_flir_ir_only -> {
                        findNavController().navigate(R.id.flirIrPreviewFragment)
                        true
                    }
                    R.id.action_switch_company -> {
                        showCompanyPicker()
                        true
                    }
                    R.id.action_sign_out -> {
                        performSignOut()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showCompanyPicker() {
        lifecycleScope.launch {
            val result = authRepository.getUserCompanies()
            result.onFailure { error ->
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.error_loading_companies),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val companies = result.getOrDefault(emptyList())
            if (companies.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_no_companies_found),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val names = companies.map { it.name?.takeIf { name -> name.isNotBlank() } ?: getString(R.string.unknown_company, it.id) }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.switch_company_title)
                .setItems(names.toTypedArray()) { dialog, which ->
                    val company = companies[which]
                    dialog.dismiss()
                    switchCompany(company.id, names[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun switchCompany(companyId: Long, name: String) {
        lifecycleScope.launch {
            authRepository.setActiveCompany(companyId)
            viewModel.refreshProjects()
            Toast.makeText(
                requireContext(),
                getString(R.string.switched_company_to, name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun performSignOut() {
        lifecycleScope.launch {
            try {
                if (BuildConfig.ENABLE_LOGGING) {
                    Log.d(TAG, "User signing out...")
                }

                authRepository.logout()
                rocketPlanApp.syncQueueManager.clear()

                // Navigate back to email check fragment
                findNavController().navigate(R.id.action_nav_projects_to_emailCheckFragment)

                Toast.makeText(context, R.string.action_sign_out, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error during logout", e)
                Toast.makeText(
                    context,
                    "Failed to sign out: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private class ProjectsPagerAdapter(
        fragment: Fragment,
        private val tabs: List<ProjectTab>
    ) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = tabs.size

        override fun createFragment(position: Int): Fragment {
            return when (val tab = tabs[position]) {
                ProjectTab.MyProjects -> ProjectListFragment.newMyProjectsInstance()
                is ProjectTab.Status -> ProjectListFragment.newStatusInstance(tab.status)
            }
        }
    }

    private sealed class ProjectTab {
        object MyProjects : ProjectTab()
        data class Status(val status: ProjectStatus) : ProjectTab()
    }
}
