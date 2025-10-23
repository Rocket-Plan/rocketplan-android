package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.rocketplan_android.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class ProjectsFragment : Fragment() {

    private val viewModel: ProjectsViewModel by activityViewModels()

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var userInitials: TextView
    private lateinit var helpButton: ImageView
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

        tabLayout = view.findViewById(R.id.tabLayout)
        viewPager = view.findViewById(R.id.viewPager)
        userInitials = view.findViewById(R.id.userInitials)
        helpButton = view.findViewById(R.id.helpButton)
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

        helpButton.setOnClickListener {
            // TODO: Navigate to help screen
            Toast.makeText(context, "Help", Toast.LENGTH_SHORT).show()
        }

        fabNewProject.setOnClickListener {
            // TODO: Navigate to create project screen
            Toast.makeText(context, "Create New Project", Toast.LENGTH_SHORT).show()
        }

        userInitials.setOnClickListener {
            // TODO: Navigate to profile/settings
            Toast.makeText(context, "User Profile", Toast.LENGTH_SHORT).show()
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
