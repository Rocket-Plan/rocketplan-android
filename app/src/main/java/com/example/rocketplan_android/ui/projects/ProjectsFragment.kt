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
import com.example.rocketplan_android.data.repository.AuthRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class ProjectsFragment : Fragment() {

    companion object {
        private const val TAG = "ProjectsFragment"
    }

    private val viewModel: ProjectsViewModel by activityViewModels()
    private lateinit var rocketPlanApp: RocketPlanApplication
    private lateinit var authRepository: AuthRepository

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var userInitials: TextView
    private lateinit var refreshButton: ImageView
    private lateinit var fabNewProject: FloatingActionButton

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
        val adapter = ProjectsPagerAdapter(this)
        viewPager.adapter = adapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "My Projects"
                1 -> "WIP"
                else -> "Tab $position"
            }
        }.attach()
    }

    private fun setupUserInterface() {
        // TODO: Get user initials from auth/user data
        userInitials.text = "JB"

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

    private fun showProfileMenu(anchor: View) {
        PopupMenu(requireContext(), anchor, Gravity.END).apply {
            menuInflater.inflate(R.menu.profile_menu, menu)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
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

    private class ProjectsPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ProjectListFragment.newInstance(ProjectListFragment.TAB_MY_PROJECTS)
                1 -> ProjectListFragment.newInstance(ProjectListFragment.TAB_WIP)
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}
