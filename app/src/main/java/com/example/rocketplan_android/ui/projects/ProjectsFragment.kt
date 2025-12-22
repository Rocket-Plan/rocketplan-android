package com.example.rocketplan_android.ui.projects

import android.content.res.ColorStateList
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.repository.AuthRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    private lateinit var assemblyUploadBubble: View
    private lateinit var assemblyUploadIconProgress: CircularProgressIndicator
    private lateinit var assemblyUploadIconCounter: TextView
    private lateinit var assemblyUploadSubtitle: TextView
    private lateinit var assemblyUploadPhotos: TextView
    private lateinit var assemblyUploadStatus: TextView
    private lateinit var assemblyUploadProgress: LinearProgressIndicator
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
        assemblyUploadBubble = view.findViewById(R.id.assemblyUploadBubble)
        assemblyUploadIconProgress = view.findViewById(R.id.assemblyUploadIconProgress)
        assemblyUploadIconCounter = view.findViewById(R.id.assemblyUploadIconCounter)
        assemblyUploadSubtitle = view.findViewById(R.id.assemblyUploadSubtitle)
        assemblyUploadPhotos = view.findViewById(R.id.assemblyUploadPhotos)
        assemblyUploadStatus = view.findViewById(R.id.assemblyUploadStatus)
        assemblyUploadProgress = view.findViewById(R.id.assemblyUploadProgress)

        setupViewPager()
        setupUserInterface()
        observeAssemblyUploads()
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

        assemblyUploadBubble.setOnClickListener {
            findNavController().navigate(R.id.imageProcessorAssembliesFragment)
        }

        userInitials.setOnClickListener {
            showProfileMenu(it)
        }
    }

    private fun observeAssemblyUploads() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeAssemblyUpload.collectLatest { state ->
                    if (state == null) {
                        assemblyUploadBubble.isVisible = false
                        return@collectLatest
                    }

                    assemblyUploadBubble.isVisible = true
                    val roomLabel = state.roomName ?: getString(R.string.projects_uploading_project_level)
                    val totalPhotos = state.totalPhotos.coerceAtLeast(0)
                    val completedPhotos = state.completedPhotos.coerceAtLeast(0)
                    val cappedCompletedPhotos = if (totalPhotos > 0) {
                        completedPhotos.coerceAtMost(totalPhotos)
                    } else {
                        completedPhotos
                    }
                    assemblyUploadSubtitle.text = getString(
                        R.string.projects_uploading_destination,
                        state.projectName,
                        roomLabel
                    )
                    assemblyUploadPhotos.text = getString(
                        R.string.projects_uploading_photo_count,
                        cappedCompletedPhotos,
                        totalPhotos
                    )
                    assemblyUploadProgress.setProgressCompat(state.progressPercent, true)
                    assemblyUploadIconCounter.text = getString(
                        R.string.projects_uploading_photo_counter,
                        cappedCompletedPhotos,
                        totalPhotos
                    )

                    if (totalPhotos == 0) {
                        assemblyUploadIconProgress.isIndeterminate = true
                        assemblyUploadIconProgress.progress = 0
                    } else {
                        assemblyUploadIconProgress.isIndeterminate = false
                        val spinnerProgress = ((cappedCompletedPhotos.toDouble() / totalPhotos.toDouble()) * 100)
                            .roundToInt()
                            .coerceIn(0, 100)
                        assemblyUploadIconProgress.setProgressCompat(spinnerProgress, true)
                    }

                    val isWaitingForRoom = state.status == AssemblyStatus.WAITING_FOR_ROOM
                    val statusText = when {
                        isWaitingForRoom -> getString(R.string.projects_uploading_waiting_for_room)
                        state.isPaused -> getString(R.string.projects_uploading_paused)
                        else -> getString(R.string.projects_uploading_progress, state.progressPercent)
                    }
                    assemblyUploadStatus.text = statusText

                    val (chipColor, textColor) = when {
                        isWaitingForRoom -> {
                            ContextCompat.getColor(requireContext(), R.color.team_member_tag) to
                                ContextCompat.getColor(requireContext(), R.color.team_member_tag_dark)
                        }
                        state.isPaused -> {
                            ContextCompat.getColor(requireContext(), R.color.error_fill) to
                                ContextCompat.getColor(requireContext(), R.color.dark_red)
                        }
                        else -> {
                            ContextCompat.getColor(requireContext(), R.color.light_purple) to
                                ContextCompat.getColor(requireContext(), R.color.main_purple)
                        }
                    }
                    ViewCompat.setBackgroundTintList(
                        assemblyUploadStatus,
                        ColorStateList.valueOf(chipColor)
                    )
                    assemblyUploadStatus.setTextColor(textColor)
                }
            }
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
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_company_info -> {
                        findNavController().navigate(R.id.companyInfoFragment)
                        true
                    }
                    R.id.action_terms_and_conditions -> {
                        findNavController().navigate(R.id.termsAndConditionsFragment)
                        true
                    }
                    R.id.action_about -> {
                        findNavController().navigate(R.id.aboutFragment)
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
