package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.rocketplan_android.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

/**
 * Lightweight container that mirrors the iOS Project/Loss Info entry point.
 * Hosts three tabs (Property, Loss, Claims) so we can start wiring the flow
 * without blocking on data plumbing.
 */
class ProjectLossInfoFragment : Fragment() {

    private val args: ProjectLossInfoFragmentArgs by navArgs()
    private val viewModel: ProjectLossInfoViewModel by viewModels {
        ProjectLossInfoViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var backButton: ImageButton
    private lateinit var titleView: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_loss_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backButton = view.findViewById(R.id.backButton)
        titleView = view.findViewById(R.id.lossInfoToolbarTitle)
        tabLayout = view.findViewById(R.id.lossInfoTabLayout)
        viewPager = view.findViewById(R.id.lossInfoViewPager)

        backButton.setOnClickListener { findNavController().navigateUp() }

        viewPager.adapter = ProjectLossInfoPagerAdapter(this, args.projectId)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.loss_info_tab_property)
                1 -> getString(R.string.loss_info_tab_loss)
                else -> getString(R.string.loss_info_tab_claims)
            }
        }.attach()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    titleView.text = state.projectTitle
                        ?: getString(R.string.add_project_info_title)
                }
            }
        }
    }
}
